package com.falcon.ipc.core

import com.falcon.ipc.service.IpcService
import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class ServiceRegistry {

    private val services = ConcurrentHashMap<String, IpcService>()

    fun <T : IpcService> register(serviceClass: KClass<T>, impl: T) {
        val key = serviceClass.qualifiedName
            ?: throw IllegalArgumentException("Service class must have a qualified name")
        services[key] = impl
        FalconLogger.d("Registry", "Registered: $key")
    }

    fun getService(key: String): IpcService? = services[key]

    fun getAllServices(): Map<String, IpcService> = services.toMap()

    fun unregisterAll() {
        services.clear()
        FalconLogger.d("Registry", "All services unregistered")
    }
}
