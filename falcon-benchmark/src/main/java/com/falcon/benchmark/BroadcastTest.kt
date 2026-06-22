package com.falcon.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real cross-process round-trip over AMS: request broadcast → remote receiver → reply broadcast. */
class BroadcastTest(private val context: Context) {
    companion object {
        const val ACTION_REQUEST = "com.falcon.benchmark.BENCH_REQUEST"
        const val ACTION_REPLY = "com.falcon.benchmark.BENCH_REPLY"
        const val EXTRA_ID = "id"
        const val EXTRA_DATA = "data"
        private const val TIMEOUT_S = 10L
    }

    private val ids = AtomicInteger(0)
    @Volatile private var latch: CountDownLatch? = null

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { latch?.countDown() }
    }

    fun register() {
        val filter = IntentFilter(ACTION_REPLY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(replyReceiver, filter)
        }
    }

    fun unregister() { runCatching { context.unregisterReceiver(replyReceiver) } }

    private fun roundTrip(data: ByteArray) {
        latch = CountDownLatch(1)
        context.sendBroadcast(Intent(ACTION_REQUEST).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ID, ids.incrementAndGet())
            putExtra(EXTRA_DATA, data)
        })
        latch?.await(TIMEOUT_S, TimeUnit.SECONDS)
    }

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData().toByteArray()
        return BenchmarkRunner.run("Broadcast", "Small (${data.size} bytes)", iterations = 300,
            block = { roundTrip(data) })
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateMediumData(16)
        return BenchmarkRunner.run("Broadcast", "Medium (${data.size} bytes)", iterations = 200,
            block = { roundTrip(data) })
    }

    fun runLargeDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateLargeData(256)
        return BenchmarkRunner.run("Broadcast", "Large (${data.size} bytes)", iterations = 100,
            block = { roundTrip(data) })
    }
}
