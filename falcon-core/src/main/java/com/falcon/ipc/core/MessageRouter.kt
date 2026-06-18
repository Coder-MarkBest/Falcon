package com.falcon.ipc.core

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter

class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter
) {
    private var interceptors: List<IpcInterceptor> = emptyList()

    fun setInterceptors(interceptors: List<IpcInterceptor>) {
        this.interceptors = interceptors
    }

    fun handleLocal(envelope: IpcEnvelope, callerPackage: String, callerPid: Int): Any? {
        if (!rateLimiter.tryAcquire(callerPid)) {
            throw IllegalStateException("Rate limit exceeded for PID=$callerPid")
        }
        try {
            if (envelope.method == "__check_service__") {
                val key = String(envelope.args ?: ByteArray(0), Charsets.UTF_8)
                val allowed = permissionChecker.check(key, callerPackage)
                val exists = allowed && (registry.getDispatcher(key) != null || registry.getService(key) != null)
                return android.os.Bundle().apply { putBoolean("r", exists) }
            }

            if (!permissionChecker.check(envelope.serviceKey, callerPackage)) {
                throw SecurityException("Permission denied: $callerPackage → ${envelope.serviceKey}")
            }

            val dispatcher = registry.getDispatcher(envelope.serviceKey)
                ?: throw IllegalStateException("Service not found: ${envelope.serviceKey}")
            val startTime = System.currentTimeMillis()
            return try {
                val out = dispatcher.dispatch(envelope.methodId, envelope.argsBundle ?: android.os.Bundle())
                monitor.recordCall(envelope.serviceKey, envelope.method, true, System.currentTimeMillis() - startTime)
                out
            } catch (e: Exception) {
                monitor.recordCall(envelope.serviceKey, envelope.method, false, System.currentTimeMillis() - startTime)
                throw e
            }
        } finally {
            rateLimiter.release(callerPid)
        }
    }
}
