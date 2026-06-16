package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger

class HotUpdateManager(
    private val versionRegistry: ServiceVersionRegistry
) {
    private val updateListeners = mutableListOf<(String, ServiceVersion) -> Unit>()

    fun registerUpdatedService(serviceKey: String, newVersion: ServiceVersion) {
        val oldVersion = versionRegistry.getVersion(serviceKey)
        if (newVersion > oldVersion) {
            versionRegistry.register(serviceKey, newVersion)
            FalconLogger.i("HotUpdate", "$serviceKey updated: $oldVersion → $newVersion")
            updateListeners.forEach { it(serviceKey, newVersion) }
        }
    }

    fun onUpdate(callback: (String, ServiceVersion) -> Unit) {
        updateListeners.add(callback)
    }

    fun getUpdateHistory(): List<Pair<String, ServiceVersion>> {
        // Simplified: just return current versions
        return emptyList()
    }
}
