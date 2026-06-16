package com.falcon.ipc.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Resolves a Binder calling UID to a package name, cached. */
class CallerResolver(private val context: Context) {
    private val cache = ConcurrentHashMap<Int, String>()

    fun resolve(uid: Int): String {
        cache[uid]?.let { return it }
        val name = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        cache[uid] = name
        return name
    }
}
