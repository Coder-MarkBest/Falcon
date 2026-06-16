package com.falcon.ipc.core

import android.os.Build
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.SharedMemoryTransport
import com.falcon.ipc.transport.TransportResult
import com.falcon.ipc.transport.TransportSelector
import com.falcon.ipc.service.IpcService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ProxyFactory {

    @Suppress("UNCHECKED_CAST")
    fun <T : IpcService> create(
        serviceClass: Class<T>,
        serviceKey: String,
        transport: IpcTransport,
        sharedMemoryTransport: SharedMemoryTransport? = null,
        threshold: Int = 64 * 1024
    ): T {
        return Proxy.newProxyInstance(
            serviceClass.classLoader,
            arrayOf(serviceClass),
            IpcInvocationHandler(serviceKey, transport, sharedMemoryTransport, threshold)
        ) as T
    }

    private class IpcInvocationHandler(
        private val serviceKey: String,
        private val transport: IpcTransport,
        private val sharedMemoryTransport: SharedMemoryTransport? = null,
        private val threshold: Int = 64 * 1024
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            // Handle Object methods
            if (method.declaringClass == Object::class.java) {
                return method.invoke(this, *(args ?: emptyArray()))
            }

            val methodName = method.name

            // Check if it's a suspend function (last param is Continuation)
            val isSuspend = method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"

            val actualArgs: Array<Any?> = if (isSuspend && args != null) {
                args.dropLast(1).toTypedArray()  // Remove Continuation parameter
            } else if (args != null) {
                @Suppress("UNCHECKED_CAST")
                (args as Array<Any?>)
            } else {
                emptyArray()
            }

            val actualParamTypes = if (isSuspend) {
                method.parameterTypes.dropLast(1).toTypedArray()
            } else {
                method.parameterTypes
            }

            if (isSuspend && args != null) {
                val continuation = args.last() as Continuation<Any?>
                // Execute IPC call and resume continuation
                try {
                    val result = executeIpcCall(methodName, actualArgs, actualParamTypes, method)
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
                return kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }

            return executeIpcCall(methodName, actualArgs, actualParamTypes, method)
        }

        private fun executeIpcCall(
            methodName: String,
            args: Array<Any?>,
            paramTypes: Array<Class<*>>,
            method: Method
        ): Any? {
            val serializedArgs = IpcSerializer.serializeArgs(args)
            val envelope = if (sharedMemoryTransport != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                && TransportSelector.shouldUseSharedMemory(serializedArgs.size, threshold)) {
                val shm = sharedMemoryTransport.writeToShared(serializedArgs)
                if (shm != null) IpcEnvelope(serviceKey = serviceKey, method = methodName, args = null,
                    largePayload = true, sharedMemory = shm)
                else IpcEnvelope(serviceKey = serviceKey, method = methodName, args = serializedArgs)
            } else {
                IpcEnvelope(serviceKey = serviceKey, method = methodName, args = serializedArgs)
            }

            val result = transport.invoke(envelope)

            return when (result) {
                is TransportResult.Success -> {
                    val data = result.data
                    if (data is ByteArray) {
                        IpcSerializer.deserializeResult(data, method.returnType)
                    } else {
                        data
                    }
                }
                is TransportResult.Error -> {
                    throw RuntimeException("IPC error [${result.code}]: ${result.message}")
                }
            }
        }
    }
}
