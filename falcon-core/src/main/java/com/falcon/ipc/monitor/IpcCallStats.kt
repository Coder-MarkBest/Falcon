package com.falcon.ipc.monitor

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

    val p99LatencyMs: Float
        get() = maxLatencyMs.toFloat()
}
