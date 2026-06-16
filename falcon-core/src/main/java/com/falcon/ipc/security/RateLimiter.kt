package com.falcon.ipc.security

import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(
    private val maxCallsPerSecond: Int = 1000,
    private val maxConcurrentCalls: Int = 50
) {
    private val callCounters = ConcurrentHashMap<Int, AtomicInteger>()
    private val concurrentCalls = ConcurrentHashMap<Int, AtomicInteger>()

    fun tryAcquire(callerPid: Int): Boolean {
        val concurrent = concurrentCalls.getOrPut(callerPid) { AtomicInteger(0) }
        val newConcurrent = concurrent.incrementAndGet()
        if (newConcurrent > maxConcurrentCalls) {
            concurrent.decrementAndGet()
            FalconLogger.w("Security", "Concurrent limit: PID=$callerPid")
            return false
        }

        val counter = callCounters.getOrPut(callerPid) { AtomicInteger(0) }
        if (counter.incrementAndGet() > maxCallsPerSecond) {
            counter.decrementAndGet()
            concurrent.decrementAndGet()
            FalconLogger.w("Security", "Rate limit: PID=$callerPid")
            return false
        }

        return true
    }

    fun release(callerPid: Int) {
        concurrentCalls[callerPid]?.decrementAndGet()
    }

    fun resetCounters() {
        callCounters.values.forEach { it.set(0) }
    }
}
