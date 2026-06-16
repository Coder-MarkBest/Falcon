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
    var statsWindowSeconds: Int = 60
)

class MonitorFacade {
    private var level: MonitorLevel = MonitorLevel.NONE
    private var config = MonitorConfig()
    private val statsMap = ConcurrentHashMap<String, IpcCallStats>()
    private val _statsFlow = MutableStateFlow<List<IpcCallStats>>(emptyList())
    private val diagnostics = DiagnosticsManager()

    fun getDiagnostics(): DiagnosticsManager = diagnostics

    fun setLevel(level: MonitorLevel) {
        this.level = level
        config = when (level) {
            MonitorLevel.NONE -> MonitorConfig(enableCallStats = false, enableTracing = false)
            MonitorLevel.BASIC -> MonitorConfig(enableCallStats = true, enableTracing = false)
            MonitorLevel.DETAILED -> MonitorConfig(enableCallStats = true, enableLatencyHistogram = true)
            MonitorLevel.FULL -> MonitorConfig(enableCallStats = true, enableTracing = true, enableLatencyHistogram = true)
        }
    }

    fun setMonitorConfig(block: MonitorConfig.() -> Unit) {
        config.block()
        FalconLogger.d("Monitor", "Config updated: stats=${config.enableCallStats} tracing=${config.enableTracing}")
    }

    fun recordCall(service: String, method: String, success: Boolean, latencyMs: Long) {
        if (!config.enableCallStats) return

        val key = "$service#$method"
        val stats = statsMap.getOrPut(key) {
            IpcCallStats(serviceName = service, methodName = method)
        }

        synchronized(stats) {
            stats.callCount++
            if (success) stats.successCount++ else stats.failCount++
            stats.totalLatencyMs += latencyMs
            if (latencyMs > stats.maxLatencyMs) stats.maxLatencyMs = latencyMs
            stats.lastCallTime = System.currentTimeMillis()
        }

        _statsFlow.value = statsMap.values.toList()

        // Also record to diagnostics
        if (diagnostics.isEnabled()) {
            diagnostics.record(DiagnosticEntry(
                timestamp = System.currentTimeMillis(),
                serviceKey = service,
                method = method,
                latencyMs = latencyMs,
                success = success,
                transportType = "BINDER",
                requestId = ""
            ))
        }
    }

    fun getStats(): List<IpcCallStats> = statsMap.values.toList()

    fun statsFlow(): Flow<List<IpcCallStats>> = _statsFlow.asStateFlow()

    fun isStatsEnabled(): Boolean = config.enableCallStats

    fun isTracingEnabled(): Boolean = config.enableTracing

    fun reset() {
        statsMap.clear()
        _statsFlow.value = emptyList()
    }
}
