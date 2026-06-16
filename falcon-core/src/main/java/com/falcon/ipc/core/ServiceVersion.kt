package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap

data class ServiceVersion(
    val major: Int,
    val minor: Int
) : Comparable<ServiceVersion> {
    override fun compareTo(other: ServiceVersion): Int {
        val majorCmp = major.compareTo(other.major)
        return if (majorCmp != 0) majorCmp else minor.compareTo(other.minor)
    }

    override fun toString(): String = "$major.$minor"

    companion object {
        val DEFAULT = ServiceVersion(1, 0)

        fun parse(versionStr: String): ServiceVersion {
            require(versionStr.isNotBlank()) { "Version string must not be blank" }
            val parts = versionStr.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid major version: ${versionStr}")
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return ServiceVersion(major, minor)
        }
    }
}

class ServiceVersionRegistry {
    private val versions = ConcurrentHashMap<String, ServiceVersion>()

    fun register(serviceKey: String, version: ServiceVersion) {
        versions[serviceKey] = version
        FalconLogger.d("Version", "Registered $serviceKey v$version")
    }

    fun getVersion(serviceKey: String): ServiceVersion {
        return versions[serviceKey] ?: ServiceVersion.DEFAULT
    }

    fun isCompatible(serviceKey: String, clientVersion: ServiceVersion): Boolean {
        val serverVersion = getVersion(serviceKey)
        // Compatible if same major version and server minor >= client minor
        return serverVersion.major == clientVersion.major &&
               serverVersion.minor >= clientVersion.minor
    }

    fun negotiateVersion(serviceKey: String, clientVersion: ServiceVersion): ServiceVersion {
        val serverVersion = getVersion(serviceKey)
        // Use the lower minor version for compatibility
        return ServiceVersion(
            major = minOf(serverVersion.major, clientVersion.major),
            minor = minOf(serverVersion.minor, clientVersion.minor)
        )
    }
}
