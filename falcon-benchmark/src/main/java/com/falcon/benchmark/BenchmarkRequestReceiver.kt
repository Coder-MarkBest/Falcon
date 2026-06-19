package com.falcon.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs in :benchmark_remote. Echoes the payload back via a reply broadcast. */
class BenchmarkRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = Intent(BroadcastTest.ACTION_REPLY).apply {
            setPackage(context.packageName)
            putExtra(BroadcastTest.EXTRA_ID, intent.getIntExtra(BroadcastTest.EXTRA_ID, -1))
            putExtra(BroadcastTest.EXTRA_DATA, intent.getByteArrayExtra(BroadcastTest.EXTRA_DATA))
        }
        context.sendBroadcast(reply)
    }
}
