package com.falcon.ipc.monitor

/**
 * Per-service-method call statistics.
 *
 * **Latency metrics:**
 * - [avgLatencyMs] — arithmetic mean across all recorded calls
 * - [maxLatencyMs] — single highest recorded latency (NOT a percentile;
 *   can be skewed by GC pauses or one-time outliers; for true tail-latency
 *   analysis, use an external histogram/metrics pipeline)
 */
data class IpcCallStats(
    val serviceName: String,
    val methodName: String,
    var callCount: Long = 0,
    var successCount: Long = 0,
    var failCount: Long = 0,
    var totalLatencyMs: Long = 0,
    var maxLatencyMs: Long = 0,
    var lastCallTime: Long = 0
) {
    val avgLatencyMs: Float
        get() = if (callCount > 0) totalLatencyMs.toFloat() / callCount else 0f
}
