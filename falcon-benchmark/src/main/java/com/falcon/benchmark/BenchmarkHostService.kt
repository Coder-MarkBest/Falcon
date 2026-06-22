package com.falcon.benchmark

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.falcon.benchmark.aidl.IBenchmarkService

/** AIDL benchmark host — runs in :benchmark_remote process. */
class BenchmarkHostService : Service() {

    private val binder = object : IBenchmarkService.Stub() {
        override fun echoString(input: String): String = input

        override fun echoBytes(input: ByteArray): ByteArray = input

        override fun computeSum(from: Int, to: Int): Long {
            var sum = 0L
            for (i in from..to) sum += i
            return sum
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
