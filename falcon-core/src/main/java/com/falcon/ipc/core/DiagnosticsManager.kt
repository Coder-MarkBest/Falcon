package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
    @Volatile private var dumpDir: File? = null

    // Async writer: a single-thread executor drains the write queue to avoid
    // blocking the IPC hot path with synchronous file I/O.
    private val writeQueue = LinkedBlockingQueue<String>()
    @Volatile private var writerThread = createWriterThread()
    @Volatile private var writerRunning = false

    private fun createWriterThread() =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "falcon-diag-writer").apply { isDaemon = true }
        }

    fun enable(dumpDirectory: File? = null) {
        enabled.set(true)
        dumpDir = dumpDirectory
        if (dumpDirectory != null) {
            if (!writerRunning) {
                writerRunning = true
                writerThread.submit { drainWriteQueue() }
            }
        }
        FalconLogger.i("Diag", "Diagnostics enabled")
    }

    fun disable() {
        enabled.set(false)
        writerRunning = false
        // Drain remaining entries before shutdown
        try { drainRemaining() } catch (_: Exception) {}
        FalconLogger.i("Diag", "Diagnostics disabled")
    }

    /** Shut down the writer thread and release resources. Call on framework shutdown. */
    fun shutdownWriter() {
        writerRunning = false
        writerThread.shutdown()
        try {
            if (!writerThread.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                writerThread.shutdownNow()
            }
        } catch (_: InterruptedException) {
            writerThread.shutdownNow()
        }
        // Re-create for potential re-enable
        writerThread = createWriterThread()
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

        // Enqueue for async write — non-blocking
        dumpDir?.let { dir ->
            val line = "${entry.timestamp}|${entry.serviceKey}|${entry.method}|${entry.latencyMs}|${entry.success}|${entry.transportType}|${entry.requestId}"
            writeQueue.offer(line)
        }
    }

    private fun drainWriteQueue() {
        val dir = dumpDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        while (writerRunning || writeQueue.isNotEmpty()) {
            val batch = mutableListOf<String>()
            // Drain available entries (up to 100 at a time for batching)
            var line = writeQueue.poll()
            while (line != null && batch.size < 100) {
                batch.add(line)
                line = writeQueue.poll()
            }
            if (batch.isNotEmpty()) {
                try {
                    val file = File(dir, "falcon_diag_${dateFormat.format(Date())}.log")
                    file.appendText(batch.joinToString("\n", postfix = "\n"))
                } catch (e: Exception) {
                    FalconLogger.e("Diag", "Failed to write diagnostics: ${e.message}")
                }
            }
            // If queue was empty, wait briefly for more entries
            if (line == null && writerRunning) {
                try {
                    val next = writeQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (next != null) {
                        batch.add(next)
                        // Continue draining
                        var more = writeQueue.poll()
                        while (more != null && batch.size < 100) {
                            batch.add(more)
                            more = writeQueue.poll()
                        }
                        if (batch.isNotEmpty()) {
                            val file = File(dir, "falcon_diag_${dateFormat.format(Date())}.log")
                            file.appendText(batch.joinToString("\n", postfix = "\n"))
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun drainRemaining() {
        val dir = dumpDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val batch = mutableListOf<String>()
        var line = writeQueue.poll()
        while (line != null) {
            batch.add(line)
            line = writeQueue.poll()
        }
        if (batch.isNotEmpty()) {
            val file = File(dir, "falcon_diag_${dateFormat.format(Date())}.log")
            file.appendText(batch.joinToString("\n", postfix = "\n"))
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
