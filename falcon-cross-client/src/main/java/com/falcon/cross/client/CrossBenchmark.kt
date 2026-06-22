package com.falcon.cross.client

import android.os.SystemClock

/**
 * Self-contained latency benchmark for **true cross-APK** Falcon calls.
 *
 * Unlike the `falcon-benchmark` module — which measures intra-app cross-process
 * latency (a server in the same app's `:benchmark_remote` process) — this measures
 * the round-trip across two independently-signed APKs. That path additionally pays
 * for a real app-to-app Binder boundary and a per-call signature verification, so it
 * is the number `falcon-benchmark` structurally cannot produce.
 *
 * Steady-state only: the proxy is already discovered/bound before measuring, and a
 * warmup phase primes JIT + Binder caches, so the figures reflect per-call cost, not
 * one-time discovery.
 */
object CrossBenchmark {

    data class Stats(
        val label: String,
        val iterations: Int,
        val avgMs: Double,
        val p50Ms: Double,
        val p99Ms: Double,
        val maxMs: Double
    ) {
        override fun toString(): String =
            "%-18s n=%d  avg=%.3f  p50=%.3f  p99=%.3f  max=%.3f ms"
                .format(label, iterations, avgMs, p50Ms, p99Ms, maxMs)
    }

    /** Warm up [warmup] times, then time [iterations] calls and return percentiles. */
    suspend fun measure(
        label: String,
        iterations: Int = 300,
        warmup: Int = 50,
        block: suspend () -> Unit
    ): Stats {
        repeat(warmup) { block() }

        val timings = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            timings[i] = SystemClock.elapsedRealtimeNanos() - start
        }
        timings.sort()

        val toMs = 1_000_000.0
        return Stats(
            label = label,
            iterations = iterations,
            avgMs = timings.sum().toDouble() / iterations / toMs,
            p50Ms = timings[iterations / 2] / toMs,
            p99Ms = timings[(iterations * 0.99).toInt().coerceAtMost(iterations - 1)] / toMs,
            maxMs = timings.last() / toMs
        )
    }
}
