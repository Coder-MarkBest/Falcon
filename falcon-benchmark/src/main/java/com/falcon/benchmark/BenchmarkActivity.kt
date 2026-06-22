package com.falcon.benchmark

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.falcon.benchmark.aidl.IBenchmarkService
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.transport.BinderTransport
import com.falcon.ipc.transport.IpcTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class BenchmarkActivity : AppCompatActivity() {

    companion object {
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
    private var messengerTest: MessengerTest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        bindServices()
    }

    // ── UI ─────────────────────────────────────────────────────────────────

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        resultText = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = "Connecting to remote services..."
        }
        root.addView(resultText)

        return ScrollView(this).apply { addView(root) }
    }

    // ── Service binding ────────────────────────────────────────────────────

    private fun bindServices() {
        // Connection timeout
        handler.postDelayed({
            if (connectedCount.get() < expectedConnections) {
                val missing = buildString {
                    if (aidlService == null) appendLine("  - AIDL (BenchmarkHostService)")
                    if (messengerBinder == null) appendLine("  - Messenger (BenchmarkMessengerService)")
                    if (falconTransport == null) appendLine("  - Falcon (IpcHostService)")
                }
                resultText.text = "FAILED: ${connectedCount.get()}/$expectedConnections connected after ${CONNECT_TIMEOUT_MS / 1000}s\n\nMissing:\n$missing"
            }
        }, CONNECT_TIMEOUT_MS)

        // 1. AIDL
        bindService(Intent(this, BenchmarkHostService::class.java), serviceConn { binder ->
            aidlService = IBenchmarkService.Stub.asInterface(binder)
        }, Context.BIND_AUTO_CREATE)

        // 2. Messenger
        bindService(Intent(this, BenchmarkMessengerService::class.java), serviceConn { binder ->
            messengerBinder = Messenger(binder)
        }, Context.BIND_AUTO_CREATE)

        // 3. Falcon
        bindService(Intent("com.falcon.ipc.HOST_SERVICE").setPackage(packageName), serviceConn { binder ->
            falconTransport = BinderTransport(IIpcHost.Stub.asInterface(binder))
        }, Context.BIND_AUTO_CREATE)
    }

    private fun serviceConn(onConnected: (IBinder) -> Unit) = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            onConnected(binder)
            if (connectedCount.incrementAndGet() == expectedConnections) {
                handler.removeCallbacksAndMessages(null)
                runBenchmarks()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {}
        override fun onNullBinding(name: ComponentName) {}
    }

    // ── Benchmarks ─────────────────────────────────────────────────────────

    private fun runBenchmarks() {
        lifecycleScope.launch(Dispatchers.Default) {
            updateUi("Starting benchmarks...")

            // ── Cold-call measurement (MUST run first) ─────────────────────
            // The first invoke() after bind is the only genuinely cold call:
            // JIT not yet compiled, Binder thread/caches not yet warm. Measuring
            // this AFTER the warmup benchmarks (as the old code did) reported a
            // steady-state number mislabelled as "cold" — so it runs up front now.
            val cold = mutableListOf<String>()
            runCatching {
                val ms = BenchmarkRunner.runCold { AidlTest().apply { setService(aidlService!!) }.echoSmall() }
                cold.add("AIDL  cold small:  ${"%.3f".format(ms)}ms")
            }
            runCatching {
                val ms = BenchmarkRunner.runCold { FalconTest(falconTransport!!).echoSmall() }
                cold.add("Falcon cold small: ${"%.3f".format(ms)}ms")
            }
            if (cold.isNotEmpty()) {
                results += BenchmarkResult(
                    name = "Cold call", dataSize = cold.joinToString("\n  "),
                    iterations = 1, totalMs = 0, avgMs = 0.0,
                    minMs = 0.0, maxMs = 0.0, p50Ms = 0.0, p95Ms = 0.0, p99Ms = 0.0
                )
            }

            // ── Sequential single-threaded ─────────────────────────────────

            runBench("AIDL") {
                val t = AidlTest().apply { setService(aidlService!!) }
                results += t.runSmallDataBenchmark()
                results += t.runMediumDataBenchmark()
                results += t.runLargeDataBenchmark()
            }

            runBench("Messenger") {
                messengerTest = MessengerTest().apply { setup(messengerBinder!!) }
                results += messengerTest!!.runSmallDataBenchmark()
                results += messengerTest!!.runMediumDataBenchmark()
                results += messengerTest!!.runLargeDataBenchmark()
                messengerTest!!.teardown()
            }

            runBench("ContentProvider") {
                val c = ContentProviderTest(this@BenchmarkActivity)
                results += c.runSmallDataBenchmark()
                results += c.runMediumDataBenchmark()
                results += c.runLargeDataBenchmark()
            }

            runBench("Falcon") {
                val f = FalconTest(falconTransport!!)
                results += f.runSmallDataBenchmark()
                results += f.runMediumDataBenchmark()
                results += f.runLargeDataBenchmark()
            }

            runBench("Broadcast") {
                val b = BroadcastTest(this@BenchmarkActivity).apply { register() }
                results += b.runSmallDataBenchmark()
                results += b.runMediumDataBenchmark()
                results += b.runLargeDataBenchmark()
                b.unregister()
            }

            // ── Concurrent (4 threads) ─────────────────────────────────────

            updateUi("Running concurrent benchmarks (4 threads)...")

            runBench("AIDL(4t)") {
                val t = AidlTest().apply { setService(aidlService!!) }
                results += BenchmarkRunner.runConcurrent("AIDL", "Small (21 bytes)", iterations = 1000, threads = 4) {
                    t.echoSmall()
                }
            }
            runCatching {
                val f = FalconTest(falconTransport!!)
                results += BenchmarkRunner.runConcurrent("Falcon", "Small (21 bytes)", iterations = 1000, threads = 4) {
                    f.echoSmall()
                }
            }

            printComparison()
        }
    }

    private suspend fun runBench(label: String, block: suspend () -> Unit) {
        updateUi("$label...")
        runCatching { block() }.onFailure {
            android.util.Log.e("Bench", "$label failed", it)
            results += na(label, it)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun na(name: String, e: Throwable): BenchmarkResult = BenchmarkResult(
        name = name, dataSize = "N/A", iterations = 0, totalMs = 0,
        avgMs = -1.0, minMs = -1.0, maxMs = -1.0, p50Ms = -1.0, p95Ms = -1.0, p99Ms = -1.0
    )

    private suspend fun updateUi(msg: String) {
        withContext(Dispatchers.Main) {
            resultText.text = buildString {
                appendLine(msg)
                appendLine()
                results.forEach { appendLine(it.toDisplayString()) }
            }
        }
    }

    private suspend fun printComparison() {
        val report = BenchmarkResult.comparisonReport(results)

        // Save to file
        val file = saveResultsToFile(report, results)

        withContext(Dispatchers.Main) {
            resultText.text = buildString {
                append(report)
                appendLine()
                if (file != null) {
                    appendLine("✓ Results saved to: ${file.absolutePath}")
                } else {
                    appendLine("✗ Could not save results to file (storage unavailable)")
                }
            }
        }
    }

    private fun saveResultsToFile(report: String, results: List<BenchmarkResult>): File? {
        return try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: filesDir
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "falcon_benchmark_$ts.txt")
            file.writeText(buildString {
                appendLine(report)
                appendLine()
                appendLine("=== RAW JSON ===")
                appendLine(BenchmarkResult.toJsonArray(results).toString(2))
            })
            file
        } catch (e: Exception) {
            android.util.Log.e("Bench", "Failed to save results", e)
            null
        }
    }
}
