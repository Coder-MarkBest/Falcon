package com.falcon.benchmark

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.falcon.benchmark.aidl.IBenchmarkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BenchmarkActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private var aidlService: IBenchmarkService? = null
    private val results = mutableListOf<BenchmarkResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_benchmark)
        resultText = findViewById(R.id.resultText)

        resultText.text = "Connecting to remote service..."

        val intent = Intent(this, BenchmarkHostService::class.java)
        bindService(intent, object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                aidlService = IBenchmarkService.Stub.asInterface(binder)
                resultText.text = "Connected. Running benchmarks..."
                runBenchmarks()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                aidlService = null
            }
        }, Context.BIND_AUTO_CREATE)
    }

    private fun runBenchmarks() {
        lifecycleScope.launch(Dispatchers.Default) {
            val aidlTest = AidlTest().apply { setService(aidlService!!) }

            results.add(aidlTest.runSmallDataBenchmark())
            updateResults("Raw AIDL small data done...")

            results.add(aidlTest.runMediumDataBenchmark())
            updateResults("Raw AIDL medium data done...")

            results.add(aidlTest.runLargeDataBenchmark())
            updateResults("All benchmarks complete!")

            printComparison()
        }
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

        val groups = results.groupBy { it.dataSize }
        groups.forEach { (size, benchmarks) ->
            sb.appendLine("--- $size ---")
            sb.appendLine("${"Method".padEnd(25)} ${"Avg(ms)".padStart(10)} ${"P50(ms)".padStart(10)} ${"P99(ms)".padStart(10)}")
            sb.appendLine("-".repeat(60))
            benchmarks.sortedBy { it.avgMs }.forEach { r ->
                sb.appendLine("${r.name.padEnd(25)} ${"%.3f".format(r.avgMs).padStart(10)} ${"%.3f".format(r.p50Ms).padStart(10)} ${"%.3f".format(r.p99Ms).padStart(10)}")
            }
            sb.appendLine()
        }

        withContext(Dispatchers.Main) {
            resultText.text = sb.toString()
        }
    }
}
