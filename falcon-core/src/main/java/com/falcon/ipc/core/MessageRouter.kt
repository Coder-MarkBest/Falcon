package com.falcon.ipc.core

import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.util.FalconLogger
import java.lang.reflect.Method

class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter
) {
    fun handleLocal(envelope: IpcEnvelope, callerProcess: String): Any? {
        if (!permissionChecker.check(envelope.serviceKey, callerProcess)) {
            throw SecurityException("Permission denied: $callerProcess → ${envelope.serviceKey}")
        }

        val service = registry.getService(envelope.serviceKey)
            ?: throw IllegalStateException("Service not found: ${envelope.serviceKey}")

        val method = findMethod(service.javaClass, envelope.method)
            ?: throw IllegalStateException("Method not found: ${envelope.method}")

        val startTime = System.currentTimeMillis()

        val result = try {
            val args = deserializeArgs(envelope.args, method)
            method.isAccessible = true
            method.invoke(service, *args)
        } catch (e: Exception) {
            monitor.recordCall(envelope.serviceKey, envelope.method, false,
                System.currentTimeMillis() - startTime)
            throw e
        }

        monitor.recordCall(envelope.serviceKey, envelope.method, true,
            System.currentTimeMillis() - startTime)
        return result
    }

    private fun findMethod(clazz: Class<*>, methodName: String): Method? {
        return clazz.methods.firstOrNull { it.name == methodName }
            ?: clazz.interfaces.flatMap { it.methods.toList() }
                .firstOrNull { it.name == methodName }
    }

    private fun deserializeArgs(data: ByteArray?, method: Method): Array<Any?> {
        if (data == null || data.isEmpty()) return emptyArray()

        val params = method.parameterTypes
        val parts = String(data).split(",")

        return params.mapIndexed { index, type ->
            if (index < parts.size) convertArg(parts[index].trim(), type) else null
        }.toTypedArray()
    }

    private fun convertArg(value: String, type: Class<*>): Any? {
        return when (type) {
            Int::class.java, Integer::class.java -> value.toInt()
            Long::class.java, java.lang.Long::class.java -> value.toLong()
            Float::class.java, java.lang.Float::class.java -> value.toFloat()
            Double::class.java, java.lang.Double::class.java -> value.toDouble()
            Boolean::class.java, java.lang.Boolean::class.java -> value.toBoolean()
            String::class.java -> value
            else -> value
        }
    }
}
