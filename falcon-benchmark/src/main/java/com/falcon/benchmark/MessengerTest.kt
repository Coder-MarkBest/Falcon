package com.falcon.benchmark

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MessengerTest {

    companion object {
        const val MSG_ECHO_STRING = 1
        const val MSG_ECHO_BYTES = 2
        const val KEY_DATA = "data"
        const val KEY_RESULT = "result"
    }

    private var remoteMessenger: Messenger? = null
    private var replyMessenger: Messenger? = null
    private var replyThread: HandlerThread? = null
    private var lastResult: Any? = null
    private var latch: CountDownLatch? = null

    fun setup(remote: Messenger) {
        remoteMessenger = remote
        // Use a background HandlerThread so reply handling doesn't compete
        // with the main thread — avoids adding main-thread scheduling jitter
        // to Messenger latency measurements.
        replyThread = HandlerThread("MessengerReply").apply { start() }
        replyMessenger = Messenger(Handler(replyThread!!.looper) { msg ->
            lastResult = msg.data.get(KEY_RESULT)
            latch?.countDown()
            true
        })
    }

    fun teardown() {
        replyThread?.quitSafely()
        replyThread = null
        replyMessenger = null
    }

    private fun sendAndWait(what: Int, data: Bundle): Boolean {
        latch = CountDownLatch(1)
        val msg = Message.obtain(null, what).apply {
            this.data = data
            replyTo = replyMessenger
        }
        remoteMessenger?.send(msg)
        return latch?.await(5, TimeUnit.SECONDS) == true
    }

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run("Messenger", "Small (${data.length} bytes)", iterations = 500) {
            val bundle = Bundle().apply { putString(KEY_DATA, data) }
            sendAndWait(MSG_ECHO_STRING, bundle)
        }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateMediumData(16)
        return BenchmarkRunner.run("Messenger", "Medium (${data.size} bytes)", iterations = 300) {
            val bundle = Bundle().apply { putByteArray(KEY_DATA, data) }
            sendAndWait(MSG_ECHO_BYTES, bundle)
        }
    }

    fun runLargeDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateLargeData(256)
        return BenchmarkRunner.run("Messenger", "Large (${data.size} bytes)", iterations = 200) {
            val bundle = Bundle().apply { putByteArray(KEY_DATA, data) }
            sendAndWait(MSG_ECHO_BYTES, bundle)
        }
    }
}
