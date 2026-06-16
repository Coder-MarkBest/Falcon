package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DiagnosticEntry(
    val timestamp: Long,
    val serviceKey: String,
    val method: String,
    val latencyMs: Long,
    val success: Boolean,
    val transportType: String,
    val requestId: String
)

class DiagnosticsManager {
    private val enabled = AtomicBoolean(false)
    private val entries = ConcurrentLinkedQueue<DiagnosticEntry>()
    private val maxEntries = 10_000
    private val entryCount = AtomicLong(0)
    private var dumpDir: File? = null

    fun enable(dumpDirectory: File? = null) {
        enabled.set(true)
        dumpDir = dumpDirectory
        FalconLogger.i("Diag", "Diagnostics enabled")
    }

    fun disable() {
        enabled.set(false)
        FalconLogger.i("Diag", "Diagnostics disabled")
    }

    fun isEnabled(): Boolean = enabled.get()

    fun record(entry: DiagnosticEntry) {
        if (!enabled.get()) return

        entries.add(entry)
        entryCount.incrementAndGet()

        // Trim if over limit
        while (entries.size > maxEntries) {
            entries.poll()
        }

        // Dump to file if configured
        dumpDir?.let { dir ->
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "falcon_diag_${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.log")
            file.appendText("${entry.timestamp}|${entry.serviceKey}|${entry.method}|${entry.latencyMs}|${entry.success}|${entry.transportType}|${entry.requestId}\n")
        }
    }

    fun getEntries(): List<DiagnosticEntry> = entries.toList()

    fun getEntryCount(): Long = entryCount.get()

    fun getRecentEntries(count: Int): List<DiagnosticEntry> {
        return entries.toList().takeLast(count)
    }

    fun clear() {
        entries.clear()
        entryCount.set(0)
    }

    fun getStats(): Map<String, DiagnosticStats> {
        val grouped = entries.groupBy { "${it.serviceKey}#${it.method}" }
        return grouped.mapValues { (_, entries) ->
            DiagnosticStats(
                callCount = entries.size.toLong(),
                successCount = entries.count { it.success }.toLong(),
                failCount = entries.count { !it.success }.toLong(),
                avgLatencyMs = entries.map { it.latencyMs }.average()
            )
        }
    }

    data class DiagnosticStats(
        val callCount: Long,
        val successCount: Long,
        val failCount: Long,
        val avgLatencyMs: Double
    )
}
