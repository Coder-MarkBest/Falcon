package com.falcon.ipc.security

import com.falcon.ipc.util.FalconLogger

data class AccessRule(
    val allowList: Set<String> = emptySet(),
    val denyList: Set<String> = emptySet()
)

class PermissionChecker(
    private val accessRules: Map<String, AccessRule>,
    private val defaultAllow: Boolean = true
) {
    fun check(serviceKey: String, callerProcess: String): Boolean {
        val rule = accessRules[serviceKey] ?: return defaultAllow

        if (rule.denyList.contains(callerProcess)) {
            FalconLogger.w("Security", "Denied: $callerProcess → $serviceKey (denyList)")
            return false
        }

        if (rule.allowList.isNotEmpty() && !rule.allowList.contains(callerProcess)) {
            FalconLogger.w("Security", "Denied: $callerProcess → $serviceKey (not in allowList)")
            return false
        }

        return true
    }
}
