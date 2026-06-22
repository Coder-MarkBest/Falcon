package com.falcon.ipc.core

import com.falcon.ipc.runtime.IpcDispatcher
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class ServiceRegistry {

    private val services = ConcurrentHashMap<String, IpcService>()
    private val dispatchers = ConcurrentHashMap<String, IpcDispatcher>()
    private val schemas = ConcurrentHashMap<String, Int>()

    fun <T : IpcService> register(serviceClass: KClass<T>, impl: T) {
        val key = serviceClass.qualifiedName
            ?: throw IllegalArgumentException("Service class must have a qualified name")
        services[key] = impl
        FalconLogger.d("Registry", "Registered: $key")
    }

    fun registerDispatcher(key: String, dispatcher: IpcDispatcher) {
        dispatchers[key] = dispatcher
    }

    /** Record the wire-contract hash for [key] (0 = unknown, skips the discovery check). */
    fun registerSchema(key: String, schemaHash: Int) {
        schemas[key] = schemaHash
    }

    fun getSchema(key: String): Int = schemas[key] ?: 0

    fun getService(key: String): IpcService? = services[key]

    fun getDispatcher(key: String): IpcDispatcher? = dispatchers[key]

    fun getAllServices(): Map<String, IpcService> = services.toMap()

    fun unregisterAll() {
        services.clear()
        dispatchers.clear()
        schemas.clear()
        FalconLogger.d("Registry", "All services unregistered")
    }
}
