package com.falcon.ipc.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.falcon.ipc.Falcon
import com.falcon.ipc.FalconConfig
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.security.SignatureGuard
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcException
import com.falcon.ipc.dynamic.DynamicTarget
import com.falcon.ipc.dynamic.DynamicProviderCapability
import com.falcon.ipc.dynamic.DynamicCapability
import com.falcon.ipc.dynamic.DynamicPayload
import com.falcon.ipc.dynamic.DynamicProtocol
import com.falcon.ipc.dynamic.DynamicRequest
import com.falcon.ipc.dynamic.DynamicResponse
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.transport.TransportResult
import com.falcon.ipc.util.CallerResolver
import com.falcon.ipc.util.FalconLogger
import com.falcon.ipc.util.ProcessUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

class FalconManager internal constructor(
    private val context: Context,
    internal val config: FalconConfig
) {
    /** Public accessor for the configured IPC call timeout (used by [callSafe]). */
    val callTimeoutMs: Long get() = config.timeout.callMs

    /** Event channel capacity (used by generated event/stream proxies). */
    val eventBufferCapacity: Int get() = config.event.bufferCapacity

    /** Event overflow policy (used by generated event/stream proxies). */
    val eventOverflow: kotlinx.coroutines.channels.BufferOverflow get() = config.event.onOverflow

    /** Peer app packages configured via [FalconConfig.peerPackages]. */
    val peerPackages: Set<String> get() = config.peerPackages

    val serviceRegistry = ServiceRegistry()
    val monitor = MonitorFacade().apply { setLevel(config.monitorLevel) }
    val diagnostics = DiagnosticsManager()
    internal val signatureGuard = SignatureGuard().apply { init(context, config.security.trustedSignatures) }
    internal val callerResolver = CallerResolver(context)
    private val permissionChecker = PermissionChecker(config.security.accessRules)
    private val rateLimiter = RateLimiter(
        config.security.rateLimitPerSecond,
        config.security.maxConcurrentCalls
    )
    internal val messageRouter = MessageRouter(
        serviceRegistry, monitor, permissionChecker, rateLimiter, config.timeout.callMs
    )

    private val registryUri = Uri.parse(
        "content://${context.packageName}.falcon.registry/services"
    )

    internal val threadPool = IpcThreadPool(
        ThreadPoolConfig(
            corePoolSize = config.transport.binderPoolSize,
            maxPoolSize = config.transport.binderPoolSize * 2
        )
    )

    private var peerManager: PeerManager? = null

    // Remote-proxy cache: a generated proxy is immutable (wraps a transport + service key)
    // and reusable across calls, so we build it once per (service, peer) and reuse it.
    // Without this, every getService() re-probes all peers and rebuilds the proxy.
    private data class CachedProxy(val proxy: Any, val processName: String)
    private val proxyCache = java.util.concurrent.ConcurrentHashMap<String, CachedProxy>()
    private data class CachedDynamicPeer(val peer: PeerConnection, val version: Int)
    private val dynamicPeerCache = java.util.concurrent.ConcurrentHashMap<String, CachedDynamicPeer>()

    fun start() {
        FalconLogger.enabled = true
        signatureGuard.enabled = config.security.signatureVerification
        // Propagate cross-app trusted signatures to the IpcRegistryProvider so its
        // signature enforcement (on call/query/insert) uses the same trust set as
        // the IPC call path. The Provider may have been created before Falcon.init().
        IpcRegistryProvider.trustedSignatures = config.security.trustedSignatures
        messageRouter.setInterceptors(config.interceptors)

        // Start IpcHostService so its hostBinder is available when peers call
        // ContentProvider.call("getHost").  The Service's android:process attribute
        // ensures it runs in the correct process; startService is a no-op if
        // already running.
        //
        // On Android 8+ a headless server may not have been foregrounded yet.
        // startForegroundService is allowed in this case (unlike startService);
        // IpcHostService.onCreate() immediately calls startForeground() and then
        // hide the notification with stopForeground(STOP_FOREGROUND_REMOVE).
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, IpcHostService::class.java))
            } else {
                context.startService(Intent(context, IpcHostService::class.java))
            }
        } catch (e: Exception) {
            FalconLogger.w("Falcon", "Failed to start IpcHostService: ${e.message}")
        }

        // Self registry + one per peer package. Deduplicate so a peerPackages entry that
        // accidentally names our own package doesn't double-register observers/queries.
        val uris = (listOf(registryUri) + config.peerPackages.map {
            Uri.parse("content://$it.falcon.registry/services")
        }).distinct()

        // Peer package visibility check (Android 11+ <queries>). Use PackageManager, which
        // reflects <queries> visibility WITHOUT touching the peer's signature-guarded
        // provider — so a signature mismatch can't masquerade as a missing <queries>.
        // This is a developer aid: warn loudly, never crash init (a peer may be optional
        // or not yet installed).
        val unreachable = config.peerPackages.filter { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                false
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                true
            }
        }
        if (unreachable.isNotEmpty()) {
            FalconLogger.w("Falcon", buildQueriesErrorMessage(unreachable.toSet()))
        }

        peerManager = PeerManager(
            context, uris,
            threadPool = threadPool,
            reconnectConfig = config.reconnect,
            transportConfig = config.transport
        ).also { pm ->
            // Invalidate cached proxies bound to a peer when it disconnects, so the next
            // getService() re-discovers (and rebinds to the peer's new transport).
            pm.onConnectionStateChanged { state, processName ->
                if (state == IpcState.DISCONNECTED) {
                    proxyCache.entries.removeAll { it.value.processName == processName }
                    dynamicPeerCache.entries.removeAll { it.value.peer.processName == processName }
                }
            }
            pm.start()
        }
        FalconLogger.d("Falcon", "Started in ${ProcessUtils.getCurrentProcessName(context)}")
    }

    fun <T : IpcService> register(serviceClass: KClass<T>, impl: T) {
        serviceRegistry.register(serviceClass, impl)   // legacy storage (unchanged)
        val key = serviceClass.qualifiedName
        if (key != null) {
            val factory = config.generatedRegistries.firstNotNullOfOrNull { it.dispatcherFactories[key] }
            if (factory != null) {
                serviceRegistry.registerDispatcher(key, factory(impl))
            } else {
                FalconLogger.w("Falcon", "No dispatcher factory for $key — did you call generated(XxxFalconGeneratedRegistry) in FalconConfig?")
            }
            // Record the wire-contract hash so peers can be schema-checked at discovery.
            config.generatedRegistries.firstNotNullOfOrNull { it.interfaceSchemas[key] }
                ?.let { serviceRegistry.registerSchema(key, it) }

            // Register in ContentProvider for cross-process service discovery
            try {
                context.contentResolver.insert(
                    registryUri,
                    android.content.ContentValues().apply {
                        put("service_key", key)
                        put("process_name", ProcessUtils.getCurrentProcessName(context))
                        put("pkg_name", context.packageName)
                        put("register_time", System.currentTimeMillis())
                        put("pid", android.os.Process.myPid())
                    }
                )
            } catch (e: Exception) {
                FalconLogger.w("Falcon", "Failed to register $key in service registry: ${e.message}")
            }
        }
        FalconLogger.i("Falcon", "Service registered: ${serviceClass.qualifiedName}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IpcService> getService(serviceClass: KClass<T>): T? {
        // Fast local path never blocks.
        val key = serviceClass.qualifiedName ?: return null
        serviceRegistry.getService(key)?.let { return it as T }

        // Remote path is blocking — guard the main thread.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            val msg = "Falcon.getService() does blocking IPC discovery and must not be " +
                "called on the main thread. Use getServiceSuspending() from a coroutine."
            if (config.strictThreadPolicy) throw IllegalStateException(msg)
            FalconLogger.w("Falcon", msg)
        }
        return kotlinx.coroutines.runBlocking { getServiceSuspending(serviceClass) }
    }

    /** Non-blocking discovery — call from a coroutine. Returns null if no peer serves [serviceClass]. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : IpcService> getServiceSuspending(serviceClass: KClass<T>): T? {
        val key = serviceClass.qualifiedName ?: return null
        serviceRegistry.getService(key)?.let { return it as T }

        // Cache hit: reuse the proxy as long as its peer is still connected.
        proxyCache[key]?.let { cached ->
            if (peerManager?.getConnection(cached.processName) != null) return cached.proxy as T
            proxyCache.remove(key)   // peer gone — drop and re-discover
        }

        val peers = peerManager?.getAllConnections() ?: return null
        if (peers.isEmpty()) return null

        val clientSchema = config.generatedRegistries.firstNotNullOfOrNull { it.interfaceSchemas[key] } ?: 0

        val found = withTimeoutOrNull(config.timeout.connectMs) {
            coroutineScope {
                peers.map { (_, peer) ->
                    async {
                        try {
                            kotlinx.coroutines.withTimeout(500L) {
                                val checkEnvelope = IpcEnvelope(
                                    serviceKey = "",
                                    method = "__check_service__",
                                    argsBundle = android.os.Bundle().apply {
                                        putString("key", key)
                                        putInt("schema", clientSchema)
                                    }
                                )
                                val result = peer.transport.invoke(checkEnvelope)
                                if (result is TransportResult.Success) {
                                    val statusCode = (result.data as? android.os.Bundle)?.getInt("r", 1) ?: 1
                                    when (statusCode) {
                                        0 -> peer
                                        3 -> {
                                            FalconLogger.e("Falcon", "Schema mismatch for $key on peer ${peer.processName} — " +
                                                "client and server built from different interface/Parcelable definitions; refusing to bind")
                                            null
                                        }
                                        else -> {
                                            FalconLogger.d("Falcon", "peer ${peer.processName} returned status=$statusCode for $key")
                                            null
                                        }
                                    }
                                } else null
                            }
                        } catch (e: Exception) {
                            FalconLogger.w("Falcon", "peer probe failed for ${peer.processName}: ${e.message}")
                            null
                        }
                    }
                }.mapNotNull { it.await() }.firstOrNull()
            }
        } ?: return null

        val factory = config.generatedRegistries.firstNotNullOfOrNull { it.proxyFactories[key] }
        if (factory == null) {
            FalconLogger.w("Falcon", "No proxy factory for $key — did you call generated(XxxFalconGeneratedRegistry) in FalconConfig?")
            return null
        }
        val proxy = factory(found.transport, key)
        proxyCache[key] = CachedProxy(proxy, found.processName)
        return proxy as T
    }

    fun onConnectionStateChanged(callback: (IpcState, String) -> Unit) {
        peerManager?.onConnectionStateChanged(callback)
    }

    fun removeConnectionStateCallback(callback: (IpcState, String) -> Unit) {
        peerManager?.removeConnectionStateCallback(callback)
    }

    /** Low-level accessor for tests. Returns the current peer connection map (read-only). */
    fun getPeerMap(): Map<String, PeerConnection> = peerManager?.getAllConnections() ?: emptyMap()

    /** Register a route without sharing a Kotlin interface or model class with clients. */
    fun route(
        route: String,
        version: Int = 1,
        handler: suspend (DynamicRequest) -> DynamicResponse
    ) {
        serviceRegistry.dynamic.registerRoute(route, version, handler)
        registerDynamicDiscovery("route", route)
    }

    /** Declare a versioned pub/sub topic. Call [publish] to emit values. */
    fun topic(topic: String, version: Int = 1) {
        serviceRegistry.dynamic.registerTopic(topic, version)
        registerDynamicDiscovery("topic", topic)
    }

    fun publish(topic: String, body: DynamicPayload, version: Int = 1): Boolean =
        serviceRegistry.dynamic.publish(topic, version, body)

    /**
     * HTTP-like dynamic request. The highest mutually supported protocol version is selected.
     * Neither side needs the other's Kotlin interface or data classes.
     */
    suspend fun call(
        route: String,
        supportedVersions: Set<Int> = setOf(1),
        headers: DynamicPayload = DynamicPayload.EMPTY,
        body: DynamicPayload = DynamicPayload.EMPTY
    ): DynamicResponse = callDynamic(route, supportedVersions, headers, body, null)

    /** Wait up to connectMs for this provider and route. Never fall back or retry a dispatched call. */
    suspend fun callTo(
        target: DynamicTarget,
        route: String,
        supportedVersions: Set<Int> = setOf(1),
        headers: DynamicPayload = DynamicPayload.EMPTY,
        body: DynamicPayload = DynamicPayload.EMPTY
    ): DynamicResponse = callDynamic(route, supportedVersions, headers, body, target)

    private suspend fun callDynamic(
        route: String,
        supportedVersions: Set<Int> = setOf(1),
        headers: DynamicPayload = DynamicPayload.EMPTY,
        body: DynamicPayload = DynamicPayload.EMPTY,
        target: DynamicTarget? = null
    ): DynamicResponse {
        require(supportedVersions.isNotEmpty() && supportedVersions.all { it > 0 }) {
            "supportedVersions must contain positive versions"
        }
        val localVersion = serviceRegistry.dynamic.negotiateRoute(route, supportedVersions)
        val localTarget = DynamicTarget(context.packageName, ProcessUtils.getCurrentProcessName(context))
        if (localVersion != null && (target == null || target == localTarget)) {
            val requestId = java.util.UUID.randomUUID().toString()
            val response = withContext(threadPool.dispatcher) {
                messageRouter.handleLocal(
                    dynamicCallEnvelope(route, localVersion, requestId, headers, body),
                    context.packageName,
                    android.os.Process.myPid(),
                    ProcessUtils.getCurrentProcessName(context)
                ) as android.os.Bundle
            }
            return DynamicProtocol.decodeResponse(response)
        }

        val selected = selectDynamicPeer("route", route, supportedVersions, target)
            ?: throw IpcException(
                if (target == null) ErrorCode.SERVICE_NOT_FOUND else ErrorCode.PROVIDER_NOT_READY,
                "No compatible dynamic route ready: $route (request not dispatched)"
            )
        val requestId = java.util.UUID.randomUUID().toString()
        val envelope = dynamicCallEnvelope(route, selected.second, requestId, headers, body)
        return when (val result = withContext(threadPool.dispatcher) { selected.first.transport.invoke(envelope) }) {
            is TransportResult.Success -> DynamicProtocol.decodeResponse(result.data as? android.os.Bundle ?: android.os.Bundle())
            is TransportResult.Error -> throw IpcException(result.code, result.message)
        }
    }

    /** Subscribe lazily; discovery and version negotiation happen when collection starts. */
    fun subscribe(
        topic: String,
        supportedVersions: Set<Int> = setOf(1)
    ): Flow<DynamicPayload> = flow {
        require(supportedVersions.isNotEmpty() && supportedVersions.all { it > 0 })
        val localVersion = serviceRegistry.dynamic.negotiateTopic(topic, supportedVersions)
        if (localVersion != null) {
            emitAll(serviceRegistry.dynamic.topicFlow(topic, localVersion) ?: return@flow)
            return@flow
        }
        val selected = selectDynamicPeer("topic", topic, supportedVersions)
            ?: throw IpcException(ErrorCode.SERVICE_NOT_FOUND, "No compatible dynamic topic: $topic")
        val eventKey = DynamicProtocol.TOPIC_PREFIX + android.net.Uri.encode(topic) + "#" + selected.second
        emitAll(EventProxy.typedRemoteFlow(
            eventKey,
            selected.first.transport,
            eventBufferCapacity,
            eventOverflow
        ) { DynamicPayload.fromBundle(it) })
    }

    /** Reconcile optional peer APKs installed after initialization, off the calling thread. */
    suspend fun refreshPeerDiscovery() = withContext(threadPool.dispatcher) {
        peerManager?.refreshPeersImmediately()
        Unit
    }

    /** Return locally visible and remotely advertised dynamic capabilities. */
    suspend fun dynamicCapabilities(): List<DynamicCapability> {
        return dynamicProviderCapabilities().map { it.capability }.distinct()
            .sortedWith(compareBy({ it.kind.name }, { it.name }))
    }

    /** Capabilities retain provider identity, for explicit routing and conflict resolution. */
    suspend fun dynamicProviderCapabilities(): List<DynamicProviderCapability> {
        val local = DynamicTarget(context.packageName, ProcessUtils.getCurrentProcessName(context))
        val result = serviceRegistry.dynamic.capabilities().map {
            DynamicProviderCapability(local, it)
        }.toMutableList()
        for (peer in peerManager?.getAllConnections()?.values.orEmpty()) {
            val authority = peer.registryUri.authority ?: continue
            if (!authority.endsWith(".falcon.registry")) continue
            val target = DynamicTarget(authority.removeSuffix(".falcon.registry"), peer.processName)
            val response = withContext(threadPool.dispatcher) {
                peer.transport.invoke(IpcEnvelope(
                    serviceKey = DynamicProtocol.SERVICE_KEY,
                    method = DynamicProtocol.CAPABILITIES
                ))
            }
            val bundle = (response as? TransportResult.Success)?.data as? android.os.Bundle ?: continue
            @Suppress("DEPRECATION")
            val items = bundle.getParcelableArrayList<android.os.Bundle>("items").orEmpty()
            items.forEach { item ->
                val name = item.getString("name") ?: return@forEach
                val kind = runCatching {
                    DynamicCapability.Kind.valueOf(item.getString("kind").orEmpty())
                }.getOrNull() ?: return@forEach
                result += DynamicProviderCapability(target, DynamicCapability(
                    name, kind, item.getIntArray("versions")?.toSet().orEmpty()
                ))
            }
        }
        return result.distinct()
    }

    private suspend fun selectDynamicPeer(
        kind: String,
        name: String,
        versions: Set<Int>,
        target: DynamicTarget? = null
    ): Pair<PeerConnection, Int>? {
        val cacheKey = listOf(kind, name, versions.sorted().joinToString(","), target?.packageName.orEmpty(), target?.processName.orEmpty()).joinToString("#")
        dynamicPeerCache[cacheKey]?.let { cached ->
            if (peerManager?.getConnection(cached.peer.processName) === cached.peer) {
                return cached.peer to cached.version
            }
            dynamicPeerCache.remove(cacheKey)
        }
        val selected = withTimeoutOrNull(config.timeout.connectMs) {
            var attempts = 0
            var found: Pair<PeerConnection, Int>?
            do {
                found = coroutineScope {
                    peerManager?.getAllConnections()?.values.orEmpty().filter { peer ->
                        target == null || (peer.processName == target.processName &&
                            peer.registryUri.authority == "${target.packageName}.falcon.registry")
                    }.map { peer ->
                        async {
                            val probe = IpcEnvelope(
                                serviceKey = DynamicProtocol.SERVICE_KEY,
                                method = DynamicProtocol.PROBE,
                                argsBundle = android.os.Bundle().apply {
                                    putString("kind", kind)
                                    putString("name", name)
                                    putIntArray("versions", versions.toIntArray())
                                }
                            )
                            val response = withContext(threadPool.dispatcher) { peer.transport.invoke(probe) }
                            val data = (response as? TransportResult.Success)?.data as? android.os.Bundle
                            val version = data?.getInt("version", 0) ?: 0
                            if (version > 0 && peerManager?.getConnection(peer.processName) === peer) peer to version else null
                        }
                    }.mapNotNull { it.await() }.maxByOrNull { it.second }
                }
                if (found == null && target != null) {
                    if (attempts == 0) FalconLogger.d("Dynamic", "Waiting for provider ${target.processName}, route=$name")
                    // A cold registry can defer trust before Application init; its first query may
                    // return no peers. Actively rediscover while waiting, even if no observer fires.
                    if (attempts++ % 5 == 0) peerManager?.refreshPeers()
                    delay(100)
                }
            } while (found == null && target != null)
            if (attempts > 0 && found != null) FalconLogger.d("Dynamic", "Provider ready ${target?.processName}, route=$name")
            found
        }
        if (selected != null) dynamicPeerCache[cacheKey] = CachedDynamicPeer(selected.first, selected.second)
        return selected
    }

    private fun registerDynamicDiscovery(kind: String, name: String) {
        try {
            context.contentResolver.insert(registryUri, android.content.ContentValues().apply {
                put("service_key", "dynamic:$kind:$name")
                put("process_name", ProcessUtils.getCurrentProcessName(context))
                put("pkg_name", context.packageName)
                put("register_time", System.currentTimeMillis())
                put("pid", android.os.Process.myPid())
            })
        } catch (e: Exception) {
            FalconLogger.w("Falcon", "Failed to advertise dynamic $kind $name: ${e.message}")
        }
    }

    private fun dynamicCallEnvelope(
        route: String,
        version: Int,
        requestId: String,
        headers: DynamicPayload,
        body: DynamicPayload
    ) = IpcEnvelope(
        serviceKey = DynamicProtocol.SERVICE_KEY,
        method = DynamicProtocol.CALL,
        requestId = requestId,
        argsBundle = android.os.Bundle().apply {
            putString("route", route)
            putInt("version", version)
            putBundle("headers", headers.toBundle())
            putBundle("body", body.toBundle())
        }
    )

    fun stop() {
        peerManager?.stop()
        proxyCache.clear()
        dynamicPeerCache.clear()
        rateLimiter.shutdown()
        signatureGuard.shutdown()
        diagnostics.disable()
        diagnostics.shutdownWriter()
        threadPool.shutdown()
        serviceRegistry.unregisterAll()
        try { context.stopService(Intent(context, IpcHostService::class.java)) } catch (_: Exception) {}
        FalconLogger.d("Falcon", "Stopped")
    }

    fun shutdown(timeoutMs: Long = 5000L) {
        FalconLogger.d("Falcon", "Shutting down (timeout=${timeoutMs}ms)...")
        peerManager?.stop()
        proxyCache.clear()
        dynamicPeerCache.clear()
        rateLimiter.shutdown()
        signatureGuard.shutdown()
        diagnostics.disable()
        diagnostics.shutdownWriter()
        threadPool.shutdown()
        serviceRegistry.unregisterAll()
        Falcon.instance = null
        FalconLogger.d("Falcon", "Shutdown complete")
    }

    companion object {
        fun buildQueriesErrorMessage(missingPackages: Set<String>): String = buildString {
            appendLine("Falcon: the following peer packages are not visible (not installed, or ")
            appendLine("missing from <queries>). If installed, add this to AndroidManifest.xml ")
            appendLine("inside the <manifest> element:")
            appendLine()
            appendLine("<queries>")
            for (pkg in missingPackages.sorted()) {
                appendLine("    <package android:name=\"$pkg\" />")
            }
            appendLine("</queries>")
        }
    }
}
