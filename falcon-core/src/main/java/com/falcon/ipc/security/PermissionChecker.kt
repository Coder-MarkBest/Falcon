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
    /**
     * Check whether [callerProcess] (or [callerPackage] as fallback) is allowed
     * to access [serviceKey].
     *
     * Process name is checked first for fine-grained per-process control.
     * Package name is used as a fallback for backwards compatibility.
     */
    fun check(serviceKey: String, callerPackage: String, callerProcess: String = callerPackage): Boolean {
        val rule = accessRules[serviceKey] ?: return defaultAllow

        // DenyList: reject if either process name OR package name is denied
        if (rule.denyList.contains(callerProcess) || rule.denyList.contains(callerPackage)) {
            FalconLogger.w("Security", "Denied: $callerProcess → $serviceKey (denyList)")
            return false
        }

        // AllowList: allow if either process name OR package name is in the list
        if (rule.allowList.isNotEmpty() &&
            !rule.allowList.contains(callerProcess) &&
            !rule.allowList.contains(callerPackage)) {
            FalconLogger.w("Security", "Denied: $callerProcess → $serviceKey (not in allowList)")
            return false
        }

        return true
    }
}
