package com.falcon.ipc.core

import android.content.Context
import android.net.Uri
import com.falcon.ipc.Falcon
import com.falcon.ipc.FalconConfig
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.security.SignatureGuard
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.transport.SharedMemoryTransport
import com.falcon.ipc.util.CallerResolver
import com.falcon.ipc.util.FalconLogger
import com.falcon.ipc.util.ProcessUtils
import kotlin.reflect.KClass

class FalconManager internal constructor(
    private val context: Context,
    private val config: FalconConfig
) {
    val serviceRegistry = ServiceRegistry()
    val monitor = MonitorFacade().apply { setLevel(config.monitorLevel) }
    val circuitBreaker = CircuitBreaker()
    val versionRegistry = ServiceVersionRegistry()
    val otaCompat = OtaCompatManager()
    val diagnostics = DiagnosticsManager()
    internal val signatureGuard = SignatureGuard().apply { init(context) }
    internal val callerResolver = CallerResolver(context)
    private val permissionChecker = PermissionChecker(config.security.accessRules)
    private val rateLimiter = RateLimiter(
        config.security.rateLimitPerSecond,
        config.security.maxConcurrentCalls
    )
    internal val messageRouter = MessageRouter(
        serviceRegistry, monitor, permissionChecker, rateLimiter
    )
    private val sharedMemoryTransport = SharedMemoryTransport(config.transport.maxSharedMemorySize)

    private val registryUri = Uri.parse(
        "content://${context.packageName}.falcon.registry/services"
    )

    private var peerManager: PeerManager? = null

    fun start() {
        FalconLogger.enabled = true
        messageRouter.setInterceptors(config.interceptors)
        peerManager = PeerManager(context, registryUri).also { it.start() }
        FalconLogger.d("Falcon", "Started in ${ProcessUtils.getCurrentProcessName(context)}")
    }

    fun <T : IpcService> register(serviceClass: KClass<T>, impl: T) {
        serviceRegistry.register(serviceClass, impl)
        FalconLogger.i("Falcon", "Service registered: ${serviceClass.qualifiedName}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IpcService> getService(serviceClass: KClass<T>): T? {
        val key = serviceClass.qualifiedName ?: return null

        // 1. Check local registry first
        val local = serviceRegistry.getService(key)
        if (local != null) return local as T

        // 2. Search remote peers
        val peers = peerManager?.getAllConnections() ?: return null
        for ((_, peer) in peers) {
            // Check if this peer has the service
            try {
                val checkEnvelope = IpcEnvelope(
                    serviceKey = "",
                    method = "__check_service__",
                    args = key.toByteArray()
                )
                peer.transport.invoke(checkEnvelope)
                // If we got here, create a proxy
                return ProxyFactory.create(serviceClass.java, key, peer.transport)
            } catch (e: Exception) {
                continue
            }
        }

        // 3. Try creating proxy for first available peer (optimistic)
        val firstPeer = peers.values.firstOrNull()
        if (firstPeer != null) {
            return ProxyFactory.create(serviceClass.java, key, firstPeer.transport)
        }

        return null
    }

    fun onConnectionStateChanged(callback: (IpcState, String) -> Unit) {
        peerManager?.onConnectionStateChanged(callback)
    }

    fun stop() {
        peerManager?.stop()
        sharedMemoryTransport.releaseAll()
        serviceRegistry.unregisterAll()
        FalconLogger.d("Falcon", "Stopped")
    }

    fun shutdown(timeoutMs: Long = 5000L) {
        FalconLogger.d("Falcon", "Shutting down (timeout=${timeoutMs}ms)...")
        peerManager?.stop()
        sharedMemoryTransport.releaseAll()
        serviceRegistry.unregisterAll()
        Falcon.instance = null
        FalconLogger.d("Falcon", "Shutdown complete")
    }
}
