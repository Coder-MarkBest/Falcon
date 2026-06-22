package com.falcon.benchmark

import com.falcon.benchmark.aidl.IBenchmarkService

class AidlTest {

    @Volatile var service: IBenchmarkService? = null
        private set

    fun setService(service: IBenchmarkService) {
        this.service = service
    }

    /** Single echoString call — used by cold-call and concurrent benchmarks. */
    fun echoSmall(): String? = service?.echoString(BenchmarkRunner.generateSmallData())

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run(
            name = "Raw AIDL",
            dataSize = "Small (${data.length} bytes)",
            iterations = 1000
        ) {
            service?.echoString(data)
        }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateMediumData(16)
        return BenchmarkRunner.run(
            name = "Raw AIDL",
            dataSize = "Medium (${data.size} bytes)",
            iterations = 500
        ) {
            service?.echoBytes(data)
        }
    }

    fun runLargeDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateLargeData(256)
        return BenchmarkRunner.run(
            name = "Raw AIDL",
            dataSize = "Large (${data.size} bytes)",
            iterations = 200
        ) {
            service?.echoBytes(data)
        }
    }
}
