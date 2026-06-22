package com.falcon.ipc.core

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.IpcRequest
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcResult
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

    fun handleLocal(envelope: IpcEnvelope, callerPackage: String, callerPid: Int, callerProcess: String = callerPackage): Any? {
        if (!rateLimiter.tryAcquire(callerPid)) {
            throw IllegalStateException("Rate limit exceeded for PID=$callerPid")
        }
        try {
            if (envelope.method == "__check_service__") {
                val key = envelope.argsBundle?.getString("key") ?: ""
                // Gate existence check behind permission — do not reveal whether
                // a service exists to callers that are not allowed to access it.
                if (!permissionChecker.check(key, callerPackage, callerProcess)) {
                    return android.os.Bundle().apply { putInt("r", 2) } // 2 = denied (uniform)
                }
                val exists = registry.getDispatcher(key) != null || registry.getService(key) != null
                if (!exists) return android.os.Bundle().apply { putInt("r", 1) } // 1 = not found
                // Wire-contract check: if both sides published a schema and they differ, the
                // peer was built from a different definition — reject to avoid silent corruption.
                val clientSchema = envelope.argsBundle?.getInt("schema", 0) ?: 0
                val serverSchema = registry.getSchema(key)
                if (clientSchema != 0 && serverSchema != 0 && clientSchema != serverSchema) {
                    return android.os.Bundle().apply { putInt("r", 3) } // 3 = schema mismatch
                }
                return android.os.Bundle().apply { putInt("r", 0) } // 0 = accessible
            }

            if (!permissionChecker.check(envelope.serviceKey, callerPackage, callerProcess)) {
                throw SecurityException("Permission denied: $callerProcess → ${envelope.serviceKey}")
            }

            val dispatcher = registry.getDispatcher(envelope.serviceKey)
                ?: throw IllegalStateException("Service not found: ${envelope.serviceKey}")

            val request = IpcRequest(
                service = envelope.serviceKey,
                method = envelope.method,
                args = null,
                traceId = envelope.traceId
            )

            val startTime = System.currentTimeMillis()

            // Build interceptor chain — innermost is the actual dispatch
            val dispatchAction: suspend (IpcRequest) -> IpcResult<*> = { req ->
                try {
                    val out = dispatcher.dispatch(envelope.methodId, envelope.argsBundle ?: android.os.Bundle())
                    monitor.recordCall(envelope.serviceKey, envelope.method, true, System.currentTimeMillis() - startTime)
                    @Suppress("UNCHECKED_CAST")
                    IpcResult.Success(out) as IpcResult<*>
                } catch (e: Exception) {
                    monitor.recordCall(envelope.serviceKey, envelope.method, false, System.currentTimeMillis() - startTime)
                    throw e
                }
            }

            // Walk the interceptor chain from last to first, wrapping each
            val chained = interceptors.foldRight(dispatchAction) { interceptor, next ->
                { req -> interceptor.intercept(req, next) }
            }

            return kotlinx.coroutines.runBlocking {
                val result = chained(request)
                when (result) {
                    is IpcResult.Success -> result.data
                    is IpcResult.Failure -> throw RuntimeException(result.message)
                    is IpcResult.Timeout -> throw RuntimeException("IPC timeout")
                    is IpcResult.ServiceUnavailable -> throw IllegalStateException("Service unavailable")
                }
            }
        } finally {
            rateLimiter.release(callerPid)
        }
    }
}
