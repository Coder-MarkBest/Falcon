package com.falcon.ipc.util

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Resolves a Binder calling PID to a process name, cached. */
class CallerResolver(private val context: Context) {
    private val cache = ConcurrentHashMap<Int, String>()

    fun resolve(pid: Int): String {
        cache[pid]?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            ?: "pid:$pid"
        cache[pid] = name
        return name
    }
}
