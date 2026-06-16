package com.falcon.benchmark

import android.os.SystemClock

object BenchmarkRunner {

    fun run(
        name: String,
        dataSize: String,
        iterations: Int = 1000,
        warmup: Int = 100,
        block: () -> Unit
    ): BenchmarkResult {
        repeat(warmup) { block() }

        val timings = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = SystemClock.elapsedRealtimeNanos()
            block()
            timings[i] = SystemClock.elapsedRealtimeNanos() - start
        }

        timings.sort()

        val totalNs = timings.sum()
        val toMs = 1_000_000.0

        return BenchmarkResult(
            name = name,
            dataSize = dataSize,
            iterations = iterations,
            totalMs = (totalNs / toMs).toLong(),
            avgMs = (totalNs.toDouble() / iterations) / toMs,
            minMs = timings.first().toDouble() / toMs,
            maxMs = timings.last().toDouble() / toMs,
            p50Ms = timings[iterations / 2].toDouble() / toMs,
            p95Ms = timings[(iterations * 0.95).toInt().coerceAtMost(iterations - 1)].toDouble() / toMs,
            p99Ms = timings[(iterations * 0.99).toInt().coerceAtMost(iterations - 1)].toDouble() / toMs
        )
    }

    fun generateSmallData(): String = "Hello Falcon IPC"

    fun generateMediumData(sizeKb: Int = 16): ByteArray = ByteArray(sizeKb * 1024) { it.toByte() }

    fun generateLargeData(sizeKb: Int = 256): ByteArray = ByteArray(sizeKb * 1024) { it.toByte() }
}
