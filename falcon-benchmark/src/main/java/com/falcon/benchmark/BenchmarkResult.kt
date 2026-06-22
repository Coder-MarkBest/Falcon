package com.falcon.benchmark

import org.json.JSONArray
import org.json.JSONObject

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

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("dataSize", dataSize)
        put("iterations", iterations)
        put("totalMs", totalMs)
        put("avgMs", avgMs)
        put("minMs", minMs)
        put("maxMs", maxMs)
        put("p50Ms", p50Ms)
        put("p95Ms", p95Ms)
        put("p99Ms", p99Ms)
    }

    companion object {
        fun toJsonArray(results: List<BenchmarkResult>): JSONArray =
            JSONArray().apply { results.forEach { put(it.toJson()) } }

        /** Build a full comparison report as formatted text (same as on-screen). */
        fun comparisonReport(results: List<BenchmarkResult>): String = buildString {
            appendLine("====== IPC BENCHMARK COMPARISON ======")
            appendLine()
            results.groupBy { it.dataSize }.forEach { (size, benchmarks) ->
                appendLine("--- $size ---")
                appendLine("${"Method".padEnd(25)} ${"Avg(ms)".padStart(10)} ${"P50(ms)".padStart(10)} ${"P99(ms)".padStart(10)}")
                appendLine("-".repeat(60))
                benchmarks.sortedBy { it.avgMs }.forEach { r ->
                    appendLine("${r.name.padEnd(25)} ${"%.3f".format(r.avgMs).padStart(10)} ${"%.3f".format(r.p50Ms).padStart(10)} ${"%.3f".format(r.p99Ms).padStart(10)}")
                }
                appendLine()
            }
            appendLine("Caveats: thread models differ (AIDL/Falcon=binder pool, Messenger=background Handler,")
            appendLine("ContentProvider=binder+SQLite, Broadcast=via AMS, not request/reply). Numbers are")
            appendLine("only comparable within one run on the same device.")
        }
    }
}
