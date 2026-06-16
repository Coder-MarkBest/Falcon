package com.falcon.ipc.runtime

import com.falcon.ipc.Falcon
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.service.IpcService
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

class IpcServiceDelegate<T : IpcService>(
    private val serviceClass: KClass<T>,
    private val fallback: T? = null,
    private val cacheTtlMs: Long = 60_000L
) : ReadOnlyProperty<Any, T> {

    @Volatile private var cached: T? = null
    @Volatile private var cachedAt: Long = 0L

    override fun getValue(thisRef: Any, property: KProperty<*>): T {
        val now = System.currentTimeMillis()
        val current = cached
        if (current != null && (now - cachedAt) < cacheTtlMs) return current

        val manager = try {
            Falcon.getInstance()
        } catch (e: IllegalStateException) {
            return fallback ?: throw e
        }

        val service = manager.getService(serviceClass)
        if (service != null) {
            cached = service
            cachedAt = now
            return service
        }

        return fallback ?: throw IllegalStateException(
            "Service ${serviceClass.qualifiedName} not found and no fallback provided"
        )
    }
}

inline fun <reified T : IpcService> ipcService(fallback: T? = null): IpcServiceDelegate<T> {
    return IpcServiceDelegate(T::class, fallback)
}
