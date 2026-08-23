package com.falcon.ipc.core

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.IpcRequest
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcResult
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcException
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.dynamic.DynamicPayload
import com.falcon.ipc.dynamic.DynamicProtocol
import com.falcon.ipc.dynamic.DynamicRequest

class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter,
    private val callTimeoutMs: Long = 5_000
) {
    private var interceptors: List<IpcInterceptor> = emptyList()

    fun setInterceptors(interceptors: List<IpcInterceptor>) {
        this.interceptors = interceptors
    }

    fun handleLocal(envelope: IpcEnvelope, callerPackage: String, callerPid: Int, callerProcess: String = callerPackage): Any? {
        if (!rateLimiter.tryAcquire(callerPid)) {
            throw IpcException(ErrorCode.RATE_LIMITED, "Rate limit exceeded for PID=$callerPid")
        }
        try {
            if (envelope.serviceKey == DynamicProtocol.SERVICE_KEY) {
                return handleDynamic(envelope, callerPackage, callerProcess)
            }
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
                ?: throw IpcException(ErrorCode.SERVICE_NOT_FOUND, "Service not found: ${envelope.serviceKey}")

            return executeObserved(envelope.serviceKey, envelope.method, envelope.traceId) {
                dispatcher.dispatch(envelope.methodId, envelope.argsBundle ?: android.os.Bundle())
            }
        } finally {
            rateLimiter.release(callerPid)
        }
    }

    private fun handleDynamic(
        envelope: IpcEnvelope,
        callerPackage: String,
        callerProcess: String
    ): android.os.Bundle {
        val args = envelope.argsBundle ?: android.os.Bundle()
        return when (envelope.method) {
            DynamicProtocol.PROBE -> {
                val name = args.getString("name").orEmpty()
                val kind = args.getString("kind").orEmpty()
                val supported = args.getIntArray("versions")?.toList().orEmpty()
                val permissionKey = dynamicPermissionKey(kind, name)
                if (!permissionChecker.check(permissionKey, callerPackage, callerProcess)) {
                    android.os.Bundle().apply { putInt("version", 0); putInt("status", 403) }
                } else {
                    val selected = when (kind) {
                        "route" -> registry.dynamic.negotiateRoute(name, supported)
                        "topic" -> registry.dynamic.negotiateTopic(name, supported)
                        else -> null
                    }
                    android.os.Bundle().apply {
                        putInt("version", selected ?: 0)
                        putInt("status", if (selected == null) 404 else 200)
                    }
                }
            }
            DynamicProtocol.CAPABILITIES -> {
                val visible = registry.dynamic.capabilities().filter {
                    permissionChecker.check(
                        dynamicPermissionKey(it.kind.name.lowercase(), it.name),
                        callerPackage,
                        callerProcess
                    )
                }
                android.os.Bundle().apply {
                    putParcelableArrayList("items", ArrayList(visible.map { capability ->
                        android.os.Bundle().apply {
                            putString("name", capability.name)
                            putString("kind", capability.kind.name)
                            putIntArray("versions", capability.versions.sorted().toIntArray())
                        }
                    }))
                }
            }
            DynamicProtocol.CALL -> {
                val route = args.getString("route").orEmpty()
                val version = args.getInt("version")
                if (!permissionChecker.check(dynamicPermissionKey("route", route), callerPackage, callerProcess)) {
                    throw SecurityException("Permission denied: $callerProcess -> dynamic route $route")
                }
                val request = DynamicRequest(
                    route = route,
                    version = version,
                    requestId = envelope.requestId,
                    headers = DynamicPayload.fromBundle(args.getBundle("headers")),
                    body = DynamicPayload.fromBundle(args.getBundle("body")),
                    callerPackage = callerPackage,
                    callerProcess = callerProcess
                )
                executeObserved(DynamicProtocol.SERVICE_KEY, route, envelope.traceId) {
                    val response = if (callTimeoutMs > 0) {
                        kotlinx.coroutines.withTimeout(callTimeoutMs) { registry.dynamic.invoke(request) }
                    } else {
                        registry.dynamic.invoke(request)
                    }
                    DynamicProtocol.encodeResponse(response)
                } as android.os.Bundle
            }
            else -> throw IllegalArgumentException("Unknown dynamic operation: ${envelope.method}")
        }
    }

    private fun dynamicPermissionKey(kind: String, name: String): String = "dynamic.$kind.$name"

    internal fun checkDynamicAccess(
        kind: String,
        name: String,
        callerPackage: String,
        callerProcess: String
    ): Boolean = permissionChecker.check(dynamicPermissionKey(kind, name), callerPackage, callerProcess)

    internal fun checkServiceAccess(
        serviceKey: String,
        callerPackage: String,
        callerProcess: String
    ): Boolean = permissionChecker.check(serviceKey, callerPackage, callerProcess)

    internal fun tryAcquire(callerPid: Int): Boolean = rateLimiter.tryAcquire(callerPid)
    internal fun release(callerPid: Int) = rateLimiter.release(callerPid)

    private fun executeObserved(
        service: String,
        method: String,
        traceId: String?,
        action: suspend () -> Any?
    ): Any? {
        val request = IpcRequest(service = service, method = method, args = null, traceId = traceId)
        val start = android.os.SystemClock.elapsedRealtime()
        monitor.beginTrace(service, method)
        val terminal: suspend (IpcRequest) -> IpcResult<*> = {
            IpcResult.Success(action())
        }
        val chain = interceptors.foldRight(terminal) { interceptor, next ->
            { req -> interceptor.intercept(req, next) }
        }
        return try {
            val result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { chain(request) }
            when (result) {
                is IpcResult.Success -> {
                    monitor.recordCall(service, method, true,
                        android.os.SystemClock.elapsedRealtime() - start)
                    result.data
                }
                is IpcResult.Failure -> throw IpcException(result.code, result.message)
                is IpcResult.Timeout -> throw IpcException(ErrorCode.TIMEOUT, "IPC timeout")
                is IpcResult.ServiceUnavailable -> throw IpcException(ErrorCode.SERVICE_NOT_FOUND, "Service unavailable")
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            monitor.recordCall(service, method, false, android.os.SystemClock.elapsedRealtime() - start)
            throw IpcException(ErrorCode.TIMEOUT, "Call timed out after ${callTimeoutMs}ms", e)
        } catch (e: Exception) {
            monitor.recordCall(service, method, false, android.os.SystemClock.elapsedRealtime() - start)
            throw e
        } finally {
            monitor.endTrace()
        }
    }
}
