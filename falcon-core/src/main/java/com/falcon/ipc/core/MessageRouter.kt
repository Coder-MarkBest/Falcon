package com.falcon.ipc.core

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter
) {
    private var interceptors: List<IpcInterceptor> = emptyList()
    private val methodCache = ConcurrentHashMap<String, Method>()

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
                if (!permissionChecker.check(key, callerPackage)) return false
                return registry.getService(key) != null
            }

            if (!permissionChecker.check(envelope.serviceKey, callerPackage)) {
                throw SecurityException("Permission denied: $callerPackage → ${envelope.serviceKey}")
            }
            val service = registry.getService(envelope.serviceKey)
                ?: throw IllegalStateException("Service not found: ${envelope.serviceKey}")

            val bytes = envelope.args ?: ByteArray(0)
            val probeArgs = IpcSerializer.deserializeArgs(bytes, emptyArray())
            val method = resolveMethod(service.javaClass, envelope.method, probeArgs.size)
                ?: throw IllegalStateException("Method not found: ${envelope.method}")

            val startTime = System.currentTimeMillis()
            return try {
                val args = IpcSerializer.deserializeArgs(bytes, method.parameterTypes)
                val result = method.invoke(service, *args)
                monitor.recordCall(envelope.serviceKey, envelope.method, true, System.currentTimeMillis() - startTime)
                result
            } catch (e: Exception) {
                monitor.recordCall(envelope.serviceKey, envelope.method, false, System.currentTimeMillis() - startTime)
                throw e
            }
        } finally {
            rateLimiter.release(callerPid)
        }
    }

    private fun resolveMethod(clazz: Class<*>, methodName: String, argCount: Int): Method? {
        val key = "${clazz.name}#$methodName/$argCount"
        methodCache[key]?.let { return it }
        val found = findMethod(clazz, methodName, argCount) ?: return null
        found.isAccessible = true
        methodCache[key] = found
        return found
    }

    private fun findMethod(clazz: Class<*>, methodName: String, argCount: Int): Method? {
        return clazz.methods.filter { it.name == methodName }
            .let { candidates ->
                if (candidates.size == 1) candidates.first()
                else candidates.firstOrNull { it.parameterCount == argCount }
                    ?: candidates.firstOrNull()
            }
            ?: clazz.interfaces.flatMap { it.methods.toList() }
                .filter { it.name == methodName }
                .let { candidates ->
                    if (candidates.size == 1) candidates.first()
                    else candidates.firstOrNull { it.parameterCount == argCount }
                        ?: candidates.firstOrNull()
                }
    }
}
