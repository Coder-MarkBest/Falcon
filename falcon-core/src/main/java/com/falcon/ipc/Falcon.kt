package com.falcon.ipc

import android.content.Context
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.service.IpcService
import kotlin.reflect.KClass

object Falcon {

    private var instance: FalconManager? = null

    fun init(context: Context, block: FalconConfig.() -> Unit = {}): FalconManager {
        val config = FalconConfig().apply(block)
        val manager = FalconManager(context.applicationContext, config)
        manager.start()
        instance = manager
        return manager
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
