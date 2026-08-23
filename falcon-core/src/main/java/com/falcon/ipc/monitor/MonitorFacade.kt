package com.falcon.ipc.monitor

import com.falcon.ipc.core.DiagnosticEntry
import com.falcon.ipc.core.DiagnosticsManager
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class MonitorConfig(
    var enableCallStats: Boolean = false,
    var enableTracing: Boolean = false,
    var enableLatencyHistogram: Boolean = false,
    var statsWindowSeconds: Int = 60,
    var maxLatencySamplesPerMethod: Int = 10_000
)

class MonitorFacade {
    @Volatile private var config = MonitorConfig()
    // Per-key stats + per-key latency ring buffers for time-window calculation
    private val statsMap = ConcurrentHashMap<String, IpcCallStats>()
    private val latencyWindows = ConcurrentHashMap<String, ArrayDeque<Pair<Long, Long>>>() // (timestamp, latencyMs)
    private val _statsFlow = MutableStateFlow<List<IpcCallStats>>(emptyList())
    private val diagnostics = DiagnosticsManager()
    private val traceActive = ThreadLocal<Boolean>()

    fun getDiagnostics(): DiagnosticsManager = diagnostics

    fun setLevel(level: MonitorLevel) {
        config = when (level) {
            MonitorLevel.NONE -> MonitorConfig(enableCallStats = false, enableTracing = false)
            MonitorLevel.BASIC -> MonitorConfig(enableCallStats = true, enableTracing = false)
            MonitorLevel.DETAILED -> MonitorConfig(enableCallStats = true, enableLatencyHistogram = true)
            MonitorLevel.FULL -> MonitorConfig(enableCallStats = true, enableTracing = true, enableLatencyHistogram = true)
        }
    }

    fun setMonitorConfig(block: MonitorConfig.() -> Unit) {
        config = config.copy().apply(block).also {
            require(it.statsWindowSeconds >= 0)
            require(it.maxLatencySamplesPerMethod > 0)
        }
        FalconLogger.d("Monitor", "Config updated: stats=${config.enableCallStats} tracing=${config.enableTracing}")
    }

    fun beginTrace(service: String, method: String) {
        if (!config.enableTracing) return
        android.os.Trace.beginSection("Falcon:${service.substringAfterLast('.')}#$method".take(127))
        traceActive.set(true)
    }

    fun endTrace() {
        if (traceActive.get() == true) {
            android.os.Trace.endSection()
            traceActive.remove()
        }
    }

    fun recordCall(service: String, method: String, success: Boolean, latencyMs: Long) {
        if (!config.enableCallStats) return

        val key = "$service#$method"
        val now = System.currentTimeMillis()

        val stats = statsMap.getOrPut(key) {
            IpcCallStats(serviceName = service, methodName = method)
        }

        synchronized(stats) {
            stats.callCount++
            if (success) stats.successCount++ else stats.failCount++
            stats.totalLatencyMs += latencyMs
            if (latencyMs > stats.maxLatencyMs) stats.maxLatencyMs = latencyMs
            stats.lastCallTime = now
        }

        // Time-windowed latency tracking
        val windowMs = config.statsWindowSeconds * 1000L
        if (config.enableLatencyHistogram && windowMs > 0) {
            val window = latencyWindows.getOrPut(key) { ArrayDeque() }
            synchronized(window) {
                // Prune entries outside the window
                val cutoff = now - windowMs
                while (window.isNotEmpty() && window.first().first < cutoff) {
                    window.removeFirst()
                }
                window.addLast(now to latencyMs)
                while (window.size > config.maxLatencySamplesPerMethod) window.removeFirst()
            }
        }

        // Avoid allocating/sorting a full snapshot on every call when nobody observes the Flow.
        if (_statsFlow.subscriptionCount.value > 0) _statsFlow.value = snapshotStats()

        // Also record to diagnostics
        if (diagnostics.isEnabled()) {
            diagnostics.record(DiagnosticEntry(
                timestamp = now,
                serviceKey = service,
                method = method,
                latencyMs = latencyMs,
                success = success,
                transportType = "BINDER",
                requestId = ""
            ))
        }
    }

    /** Get time-windowed average latency for a specific key (or -1 if no data). */
    fun getWindowedAvgLatencyMs(key: String): Double {
        val window = latencyWindows[key] ?: return -1.0
        synchronized(window) {
            if (window.isEmpty()) return -1.0
            return window.map { it.second }.average()
        }
    }

    fun getStats(): List<IpcCallStats> = snapshotStats()

    fun statsFlow(): Flow<List<IpcCallStats>> = _statsFlow.asStateFlow()

    fun isStatsEnabled(): Boolean = config.enableCallStats

    fun isTracingEnabled(): Boolean = config.enableTracing

    fun reset() {
        statsMap.clear()
        latencyWindows.clear()
        _statsFlow.value = emptyList()
    }

    private fun snapshotStats(): List<IpcCallStats> = statsMap.values.map { stats ->
        synchronized(stats) { stats.copy() }
    }.sortedWith(compareBy({ it.serviceName }, { it.methodName }))
}
