package com.falcon.ipc.util

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Resolves a Binder calling UID/PID to a package name and process name, cached. */
class CallerResolver(private val context: Context) {
    private val packageCache = ConcurrentHashMap<Int, String>()
    private val processCache = ConcurrentHashMap<Int, String>()

    fun resolve(uid: Int): String {
        packageCache[uid]?.let { return it }
        val name = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        packageCache[uid] = name
        return name
    }

    /**
     * Resolves the calling PID to a process name.
     * Returns null if the process cannot be identified (e.g., already exited).
     */
    fun resolveProcessName(pid: Int): String? {
        processCache[pid]?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val name = try {
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        } catch (e: Exception) {
            FalconLogger.w("CallerResolver", "Failed to resolve process name for PID=$pid: ${e.message}")
            null
        }
        if (name != null) processCache[pid] = name
        return name
    }

    /** Clear caches (e.g., after app update). */
    fun invalidateAll() {
        packageCache.clear()
        processCache.clear()
    }
}
