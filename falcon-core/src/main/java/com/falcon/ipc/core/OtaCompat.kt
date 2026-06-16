package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger

data class OtaVersionInfo(
    val serviceKey: String,
    val localVersion: ServiceVersion,
    val remoteVersion: ServiceVersion?
) {
    val isCompatible: Boolean
        get() {
            if (remoteVersion == null) return true
            return localVersion.major == remoteVersion.major
        }

    val needsDowngrade: Boolean
        get() {
            if (remoteVersion == null) return false
            return localVersion > remoteVersion
        }
}

class OtaCompatManager {
    private val localVersions = mutableMapOf<String, ServiceVersion>()
    private val remoteVersions = mutableMapOf<String, ServiceVersion>()
    private val downgradeCallbacks = mutableListOf<(String, ServiceVersion) -> Unit>()

    fun registerLocalVersion(serviceKey: String, version: ServiceVersion) {
        localVersions[serviceKey] = version
    }

    fun registerRemoteVersion(serviceKey: String, version: ServiceVersion) {
        remoteVersions[serviceKey] = version
        FalconLogger.i("OTA", "Remote version: $serviceKey v$version")

        val local = localVersions[serviceKey]
        if (local != null && local > version) {
            FalconLogger.w("OTA", "Version mismatch: local=$local remote=$version, downgrading")
            downgradeCallbacks.forEach { it(serviceKey, version) }
        }
    }

    fun getVersionInfo(serviceKey: String): OtaVersionInfo {
        return OtaVersionInfo(
            serviceKey = serviceKey,
            localVersion = localVersions[serviceKey] ?: ServiceVersion.DEFAULT,
            remoteVersion = remoteVersions[serviceKey]
        )
    }

    fun onDowngrade(callback: (String, ServiceVersion) -> Unit) {
        downgradeCallbacks.add(callback)
    }

    fun getAllVersionInfos(): List<OtaVersionInfo> {
        return localVersions.keys.map { getVersionInfo(it) }
    }
}
