package com.falcon.ipc

import android.content.Context
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.service.IpcService
import kotlin.reflect.KClass

object Falcon {

    @Volatile
    internal var instance: FalconManager? = null

    fun init(context: Context, block: FalconConfig.() -> Unit = {}): FalconManager {
        return instance ?: synchronized(this) {
            instance ?: FalconManager(context.applicationContext, FalconConfig().apply(block)).also {
                it.start()
                instance = it
            }
        }
    }

    fun getInstance(): FalconManager {
        return instance ?: throw IllegalStateException(
            "Falcon not initialized. Call Falcon.init(context) first."
        )
    }
}

inline fun <reified T : IpcService> FalconManager.register(impl: T) {
    register(T::class, impl)
}

inline fun <reified T : IpcService> FalconManager.getService(): T? {
    return getService(T::class)
}

suspend inline fun <reified T : IpcService> FalconManager.getServiceSuspending(): T? {
    return getServiceSuspending(T::class)
}
