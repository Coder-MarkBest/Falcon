package com.falcon.ipc.util

import android.util.Log

object FalconLogger {
    private const val TAG_PREFIX = "Falcon:"
    @Volatile var enabled: Boolean = false

    fun d(module: String, message: String) {
        if (enabled) Log.d("$TAG_PREFIX$module", message)
    }

    fun i(module: String, message: String) {
        if (enabled) Log.i("$TAG_PREFIX$module", message)
    }

    fun w(module: String, message: String) {
        if (enabled) Log.w("$TAG_PREFIX$module", message)
    }

    fun e(module: String, message: String, throwable: Throwable? = null) {
        if (enabled) {
            if (throwable != null) Log.e("$TAG_PREFIX$module", message, throwable)
            else Log.e("$TAG_PREFIX$module", message)
        }
    }
}
