package com.falcon.benchmark

import com.falcon.ipc.transport.IpcTransport

/**
 * Benchmarks the Falcon steady-state call path: generated proxy -> Bundle -> Binder ->
 * IpcHostService -> generated dispatcher -> impl. The transport is supplied by the Activity
 * (which binds IpcHostService directly), so this measures per-call latency the same way the
 * AIDL test does (pre-bound, steady state) — discovery is intentionally excluded.
 */
class FalconTest(transport: IpcTransport) {
    val proxy: IBenchmarkFalconService =
        BenchmarkFalconService_Proxy(transport, IBenchmarkFalconService::class.qualifiedName!!)

    /** Single echoString call — used by cold-call and concurrent benchmarks. */
    fun echoSmall(): String = proxy.echoString(BenchmarkRunner.generateSmallData())

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run("Falcon", "Small (${data.length} bytes)", iterations = 1000) {
            proxy.echoString(data)
        }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateMediumData(16)
        return BenchmarkRunner.run("Falcon", "Medium (${data.size} bytes)", iterations = 500) {
            proxy.echoBytes(data)
        }
    }

    fun runLargeDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateLargeData(256)
        return BenchmarkRunner.run("Falcon", "Large (${data.size} bytes)", iterations = 200) {
            proxy.echoBytes(data)
        }
    }
}
