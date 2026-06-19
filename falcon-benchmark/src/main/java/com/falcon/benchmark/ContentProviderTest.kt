package com.falcon.benchmark

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Base64

class ContentProviderTest(private val context: Context) {
    private val authority = "${context.packageName}.falcon.benchmark.provider"
    private val uri = Uri.parse("content://$authority/benchmark")

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run("ContentProvider", "Small (${data.length} bytes)", iterations = 500) {
            roundTrip(data)
        }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        // 16KB encoded as a String value column
        val data = Base64.encodeToString(BenchmarkRunner.generateMediumData(16), Base64.NO_WRAP)
        return BenchmarkRunner.run("ContentProvider", "Medium (~16384 bytes)", iterations = 300) {
            roundTrip(data)
        }
    }

    private fun roundTrip(value: String) {
        context.contentResolver.insert(uri, ContentValues().apply { put("value", value) })
        context.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() }
    }
}
