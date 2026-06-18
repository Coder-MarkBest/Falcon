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
import com.falcon.ipc.transport.TransportResult
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
    val diagnostics = DiagnosticsManager()
    internal val signatureGuard = SignatureGuard().apply { init(context, config.security.trustedSignatures) }
    internal val callerResolver = CallerResolver(context)
    private val permissionChecker = PermissionChecker(config.security.accessRules)
    private val rateLimiter = RateLimiter(
        config.security.rateLimitPerSecond,
        config.security.maxConcurrentCalls
    )
    internal val messageRouter = MessageRouter(
        serviceRegistry, monitor, permissionChecker, rateLimiter
    )

    private val registryUri = Uri.parse(
        "content://${context.packageName}.falcon.registry/services"
    )

    internal val threadPool = IpcThreadPool()

    private var peerManager: PeerManager? = null

    fun start() {
        FalconLogger.enabled = true
        messageRouter.setInterceptors(config.interceptors)
        peerManager = PeerManager(
            context, registryUri,
            threadPool = threadPool
        ).also { it.start() }
        FalconLogger.d("Falcon", "Started in ${ProcessUtils.getCurrentProcessName(context)}")
    }

    fun <T : IpcService> register(serviceClass: KClass<T>, impl: T) {
        serviceRegistry.register(serviceClass, impl)   // legacy storage (unchanged)
        val key = serviceClass.qualifiedName
        if (key != null) {
            val factory = config.generatedRegistries.firstNotNullOfOrNull { it.dispatcherFactories[key] }
            if (factory != null) serviceRegistry.registerDispatcher(key, factory(impl))
        }
        FalconLogger.i("Falcon", "Service registered: ${serviceClass.qualifiedName}")
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IpcService> getService(serviceClass: KClass<T>): T? {
        val key = serviceClass.qualifiedName ?: return null

        // 1. Check local registry first
        val local = serviceRegistry.getService(key)
        if (local != null) return local as T

        // 2. Search remote peers — only create a proxy when the probe CONFIRMS the service exists
        val peers = peerManager?.getAllConnections() ?: return null
        for ((_, peer) in peers) {
            try {
                val checkEnvelope = IpcEnvelope(
                    serviceKey = "",
                    method = "__check_service__",
                    args = key.toByteArray(Charsets.UTF_8)
                )
                val result = peer.transport.invoke(checkEnvelope)
                if (result is TransportResult.Success) {
                    val hasService = (result.data as? android.os.Bundle)?.getBoolean("r") == true
                    if (hasService) {
                        val factory = config.generatedRegistries
                            .firstNotNullOfOrNull { it.proxyFactories[key] }
                            ?: return null  // no generated proxy -> cannot build a typed remote proxy
                        @Suppress("UNCHECKED_CAST")
                        return factory(peer.transport, key) as T
                    }
                }
            } catch (e: Exception) {
                FalconLogger.w("Falcon", "peer probe failed for ${peer.processName}: ${e.message}")
            }
        }

        return null
    }

    fun onConnectionStateChanged(callback: (IpcState, String) -> Unit) {
        peerManager?.onConnectionStateChanged(callback)
    }

    fun stop() {
        peerManager?.stop()
        threadPool.shutdown()
        serviceRegistry.unregisterAll()
        FalconLogger.d("Falcon", "Stopped")
    }

    fun shutdown(timeoutMs: Long = 5000L) {
        FalconLogger.d("Falcon", "Shutting down (timeout=${timeoutMs}ms)...")
        peerManager?.stop()
        threadPool.shutdown()
        serviceRegistry.unregisterAll()
        Falcon.instance = null
        FalconLogger.d("Falcon", "Shutdown complete")
    }
}
