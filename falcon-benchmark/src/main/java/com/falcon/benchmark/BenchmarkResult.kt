package com.falcon.benchmark

data class BenchmarkResult(
    val name: String,
    val dataSize: String,
    val iterations: Int,
    val totalMs: Long,
    val avgMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double
) {
    fun toDisplayString(): String = buildString {
        appendLine("=== $name ($dataSize) ===")
        appendLine("  Iterations: $iterations")
        appendLine("  Total:      ${totalMs}ms")
        appendLine("  Avg:        ${"%.3f".format(avgMs)}ms")
        appendLine("  Min:        ${"%.3f".format(minMs)}ms")
        appendLine("  Max:        ${"%.3f".format(maxMs)}ms")
        appendLine("  P50:        ${"%.3f".format(p50Ms)}ms")
        appendLine("  P95:        ${"%.3f".format(p95Ms)}ms")
        appendLine("  P99:        ${"%.3f".format(p99Ms)}ms")
    }
}
