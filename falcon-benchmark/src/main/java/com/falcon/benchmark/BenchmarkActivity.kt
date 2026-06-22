package com.falcon.benchmark

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.falcon.benchmark.aidl.IBenchmarkService
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.transport.BinderTransport
import com.falcon.ipc.transport.IpcTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class BenchmarkActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Bench"
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }

    private lateinit var resultText: TextView
    private val results = mutableListOf<BenchmarkResult>()

    private var aidlService: IBenchmarkService? = null
    private var messengerBinder: Messenger? = null
    private var falconTransport: IpcTransport? = null

    private val connectedCount = AtomicInteger(0)
    private val expectedConnections = 3
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)
        resultText = findViewById(R.id.resultText)
        resultText.text = "Connecting to remote services..."

        // Connection timeout: if all services don't connect within the limit, show error
        handler.postDelayed({
            if (connectedCount.get() < expectedConnections) {
                val missing = buildString {
                    if (aidlService == null) appendLine("  - AIDL (BenchmarkHostService)")
                    if (messengerBinder == null) appendLine("  - Messenger (BenchmarkMessengerService)")
                    if (falconTransport == null) appendLine("  - Falcon (IpcHostService)")
                }
                resultText.text = buildString {
                    appendLine("FAILED: only ${connectedCount.get()}/$expectedConnections connected after ${CONNECT_TIMEOUT_MS / 1000}s")
                    appendLine()
                    appendLine("Missing:")
                    appendLine(missing)
                    appendLine()
                    appendLine("Check logcat for 'Bench' and 'Falcon' tags.")
                }
                android.util.Log.e(TAG, "Connection timeout: $expectedConnections expected, ${connectedCount.get()} connected")
            }
        }, CONNECT_TIMEOUT_MS)

        // 1. AIDL (default binder on BenchmarkHostService)
        bindService(
            Intent(this, BenchmarkHostService::class.java),
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    aidlService = IBenchmarkService.Stub.asInterface(binder)
                    android.util.Log.i(TAG, "AIDL connected ($name)")
                    onConnected()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    android.util.Log.w(TAG, "AIDL disconnected ($name)")
                    aidlService = null
                }
                override fun onNullBinding(name: ComponentName) {
                    android.util.Log.e(TAG, "AIDL null binding ($name)")
                }
            },
            Context.BIND_AUTO_CREATE
        )

        // 2. Messenger (dedicated BenchmarkMessengerService — separate from AIDL service
        //    to prevent Android from deduplicating binds to the same component)
        bindService(
            Intent(this, BenchmarkMessengerService::class.java),
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    messengerBinder = Messenger(binder)
                    android.util.Log.i(TAG, "Messenger connected ($name)")
                    onConnected()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    android.util.Log.w(TAG, "Messenger disconnected ($name)")
                    messengerBinder = null
                }
                override fun onNullBinding(name: ComponentName) {
                    android.util.Log.e(TAG, "Messenger null binding ($name)")
                }
            },
            Context.BIND_AUTO_CREATE
        )

        // 3. Falcon (bind IpcHostService directly in :benchmark_remote)
        bindService(
            Intent("com.falcon.ipc.HOST_SERVICE").setPackage(packageName),
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    falconTransport = BinderTransport(IIpcHost.Stub.asInterface(binder))
                    android.util.Log.i(TAG, "Falcon connected ($name)")
                    onConnected()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    android.util.Log.w(TAG, "Falcon disconnected ($name)")
                    falconTransport = null
                }
                override fun onNullBinding(name: ComponentName) {
                    android.util.Log.e(TAG, "Falcon null binding ($name)")
                }
            },
            Context.BIND_AUTO_CREATE
        )
    }

    private fun onConnected() {
        val count = connectedCount.incrementAndGet()
        android.util.Log.i(TAG, "Connection progress: $count/$expectedConnections")
        if (count == expectedConnections) {
            // Remove timeout callback since all connected
            handler.removeCallbacksAndMessages(null)
            runBenchmarks()
        }
    }

    private fun runBenchmarks() {
        lifecycleScope.launch(Dispatchers.Default) {
            runCatching {
                val t = AidlTest().apply { setService(aidlService!!) }
                results += t.runSmallDataBenchmark()
                results += t.runMediumDataBenchmark()
                results += t.runLargeDataBenchmark()
            }.onFailure {
                android.util.Log.e(TAG, "AIDL benchmarks failed", it)
                results += na("Raw AIDL", it)
            }
            updateResults("AIDL done...")

            runCatching {
                val m = MessengerTest().apply { setup(messengerBinder!!) }
                results += m.runSmallDataBenchmark()
                results += m.runMediumDataBenchmark()
                results += m.runLargeDataBenchmark()
            }.onFailure {
                android.util.Log.e(TAG, "Messenger benchmarks failed", it)
                results += na("Messenger", it)
            }
            updateResults("Messenger done...")

            runCatching {
                val c = ContentProviderTest(this@BenchmarkActivity)
                results += c.runSmallDataBenchmark()
                results += c.runMediumDataBenchmark()
            }.onFailure {
                android.util.Log.e(TAG, "ContentProvider benchmarks failed", it)
                results += na("ContentProvider", it)
            }
            updateResults("ContentProvider done...")

            runCatching {
                val f = FalconTest(falconTransport!!)
                results += f.runSmallDataBenchmark()
                results += f.runMediumDataBenchmark()
                results += f.runLargeDataBenchmark()
            }.onFailure {
                android.util.Log.e(TAG, "Falcon benchmarks failed", it)
                results += na("Falcon", it)
            }
            updateResults("Falcon done...")

            runCatching {
                val b = BroadcastTest(this@BenchmarkActivity).apply { register() }
                results += b.runSmallDataBenchmark()
                results += b.runMediumDataBenchmark()
                b.unregister()
            }.onFailure {
                android.util.Log.e(TAG, "Broadcast benchmarks failed", it)
                results += na("Broadcast", it)
            }

            printComparison()
        }
    }

    private fun na(name: String, e: Throwable): BenchmarkResult {
        android.util.Log.e(TAG, "$name failed", e)
        return BenchmarkResult(
            name = name, dataSize = "N/A", iterations = 0, totalMs = 0,
            avgMs = -1.0, minMs = -1.0, maxMs = -1.0, p50Ms = -1.0, p95Ms = -1.0, p99Ms = -1.0
        )
    }

    private suspend fun updateResults(status: String) {
        withContext(Dispatchers.Main) {
            resultText.text = buildString {
                appendLine(status)
                appendLine()
                results.forEach { appendLine(it.toDisplayString()) }
            }
        }
    }

    private suspend fun printComparison() {
        val sb = StringBuilder()
        sb.appendLine("====== IPC BENCHMARK COMPARISON ======")
        sb.appendLine()
        results.groupBy { it.dataSize }.forEach { (size, benchmarks) ->
            sb.appendLine("--- $size ---")
            sb.appendLine("${"Method".padEnd(25)} ${"Avg(ms)".padStart(10)} ${"P50(ms)".padStart(10)} ${"P99(ms)".padStart(10)}")
            sb.appendLine("-".repeat(60))
            benchmarks.sortedBy { it.avgMs }.forEach { r ->
                sb.appendLine("${r.name.padEnd(25)} ${"%.3f".format(r.avgMs).padStart(10)} ${"%.3f".format(r.p50Ms).padStart(10)} ${"%.3f".format(r.p99Ms).padStart(10)}")
            }
            sb.appendLine()
        }
        sb.appendLine("Caveats: thread models differ (AIDL/Falcon=binder pool, Messenger=main Handler,")
        sb.appendLine("ContentProvider=binder+SQLite, Broadcast=via AMS, not request/reply). Numbers are")
        sb.appendLine("only comparable within one run on the same device.")
        withContext(Dispatchers.Main) { resultText.text = sb.toString() }
    }
}
