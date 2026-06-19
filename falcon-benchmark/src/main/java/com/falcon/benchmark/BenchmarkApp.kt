package com.falcon.benchmark

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import com.falcon.ipc.Falcon
import com.falcon.ipc.generated.BenchmarkFalconGeneratedRegistry
import com.falcon.ipc.register

class BenchmarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only the remote process hosts the Falcon service (where IpcHostService runs).
        if (currentProcessName().endsWith(":benchmark_remote")) {
            val falcon = Falcon.init(this) { generated(BenchmarkFalconGeneratedRegistry) }
            falcon.register(IBenchmarkFalconService::class, BenchmarkFalconServiceImpl())
        }
    }

    private fun currentProcessName(): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
    }
}

/** Falcon service implementation used by the benchmark (echo + sum). */
class BenchmarkFalconServiceImpl : IBenchmarkFalconService {
    override fun echoString(input: String): String = input
    override fun computeSum(from: Int, to: Int): Long {
        var s = 0L; for (i in from..to) s += i; return s
    }
    override fun echoBytes(data: ByteArray): ByteArray = data
    // ticks()/fetch() exist on the interface for KSP event/callback coverage; benchmark doesn't use them.
    override fun ticks(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf()
    override fun fetch(id: Int, reply: com.falcon.ipc.service.IpcReply<String>) { reply.onResult("v$id") }
}
