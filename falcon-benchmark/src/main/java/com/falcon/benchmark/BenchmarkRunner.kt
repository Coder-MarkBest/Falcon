package com.falcon.benchmark

import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

        return buildResult(name, dataSize, iterations, timings)
    }

    /**
     * Measure the cold-call latency: time the very first invocation without warmup.
     * Returns the single-call latency in ms (no statistics — just the raw number).
     */
    fun runCold(
        block: () -> Unit
    ): Double {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        val ns = SystemClock.elapsedRealtimeNanos() - start
        return ns / 1_000_000.0
    }

    /**
     * Concurrent benchmark: run [iterations] calls spread across [threads] threads.
     * Each thread runs iterations/threads calls. Measures wall-clock throughput.
     */
    fun runConcurrent(
        name: String,
        dataSize: String,
        iterations: Int = 1000,
        threads: Int = 4,
        warmup: Int = 50,
        block: () -> Unit
    ): BenchmarkResult {
        // Single-threaded warmup
        repeat(warmup) { block() }

        val perThread = iterations / threads
        val executor = Executors.newFixedThreadPool(threads)
        val timings = LongArray(iterations)

        val startWall = SystemClock.elapsedRealtimeNanos()
        val futures = (0 until threads).map { threadIdx ->
            executor.submit<Unit> {
                val offset = threadIdx * perThread
                for (i in 0 until perThread) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    block()
                    timings[offset + i] = SystemClock.elapsedRealtimeNanos() - start
                }
            }
        }
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        val wallNs = SystemClock.elapsedRealtimeNanos() - startWall
        executor.shutdown()

        val result = buildResult("$name (${threads}t)", dataSize, iterations, timings)
        // Add wall-clock throughput to the dataSize label
        return result.copy(
            dataSize = "$dataSize wall=${"%.1f".format(wallNs / 1_000_000.0)}ms"
        )
    }

    fun generateSmallData(): String = "Hello Falcon IPC"

    fun generateMediumData(sizeKb: Int = 16): ByteArray = ByteArray(sizeKb * 1024) { it.toByte() }

    fun generateLargeData(sizeKb: Int = 256): ByteArray = ByteArray(sizeKb * 1024) { it.toByte() }

    // ── internal ───────────────────────────────────────────────────────────

    private fun buildResult(
        name: String, dataSize: String, iterations: Int, timings: LongArray
    ): BenchmarkResult {
        timings.sort()
        val totalNs = timings.sum()
        val toMs = 1_000_000.0
        return BenchmarkResult(
            name = name, dataSize = dataSize, iterations = iterations,
            totalMs = (totalNs / toMs).toLong(),
            avgMs = (totalNs.toDouble() / iterations) / toMs,
            minMs = timings.first().toDouble() / toMs,
            maxMs = timings.last().toDouble() / toMs,
            p50Ms = timings[iterations / 2].toDouble() / toMs,
            p95Ms = timings[(iterations * 0.95).toInt().coerceAtMost(iterations - 1)].toDouble() / toMs,
            p99Ms = timings[(iterations * 0.99).toInt().coerceAtMost(iterations - 1)].toDouble() / toMs
        )
    }
}
