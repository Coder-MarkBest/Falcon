package com.falcon.ipc.core

import android.content.Context
import android.net.Uri
import com.falcon.ipc.FalconConfig
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.security.SignatureGuard
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.transport.SharedMemoryTransport
import com.falcon.ipc.util.FalconLogger
import com.falcon.ipc.util.ProcessUtils
import kotlin.reflect.KClass

class FalconManager internal constructor(
    private val context: Context,
    private val config: FalconConfig
) {
    val serviceRegistry = ServiceRegistry()
    val monitor = MonitorFacade().apply { setLevel(config.monitorLevel) }
    private val signatureGuard = SignatureGuard().apply { init(context) }
    private val permissionChecker = PermissionChecker(config.security.accessRules)
    private val rateLimiter = RateLimiter(
        config.security.rateLimitPerSecond,
        config.security.maxConcurrentCalls
    )
    private val messageRouter = MessageRouter(
        serviceRegistry, monitor, permissionChecker, rateLimiter
    )
    private val sharedMemoryTransport = SharedMemoryTransport(config.transport.maxSharedMemorySize)

    private val registryUri = Uri.parse(
        "content://${context.packageName}.falcon.registry/services"
    )

    private var peerManager: PeerManager? = null

    fun start() {
        FalconLogger.enabled = true
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
        val local = serviceRegistry.getService(key)
        if (local != null) return local as T
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
}
