package com.falcon.ipc.util

import android.app.ActivityManager
import android.content.Context
import android.os.Process

object ProcessUtils {

    private var cachedProcessName: String? = null

    fun getCurrentProcessName(context: Context): String {
        cachedProcessName?.let { return it }

        val pid = Process.myPid()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = am.runningAppProcesses
            .firstOrNull { it.pid == pid }
            ?.processName
            ?: context.packageName

        cachedProcessName = name
        return name
    }

    fun isMainProcess(context: Context): Boolean {
        return getCurrentProcessName(context) == context.packageName
    }
}
