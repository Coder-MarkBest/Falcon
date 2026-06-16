package com.falcon.benchmark

import android.content.ContentValues
import android.content.Context
import android.net.Uri

class ContentProviderTest(private val context: Context) {

    private val authority = "${context.packageName}.falcon.benchmark.provider"
    private val uri = Uri.parse("content://$authority/benchmark")

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run(
            name = "ContentProvider",
            dataSize = "Small (${data.length} bytes)",
            iterations = 500
        ) {
            val values = ContentValues().apply {
                put("key", "bench")
                put("value", data)
            }
            context.contentResolver.insert(uri, values)
            context.contentResolver.query(uri, null, null, null, null)?.use {
                it.moveToFirst()
            }
        }
    }
}
