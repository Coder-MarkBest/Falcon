package com.falcon.ipc.security

import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(
    private val maxCallsPerSecond: Int = 1000,
    private val maxConcurrentCalls: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val windows = ConcurrentHashMap<Int, ArrayDeque<Long>>()
    private val concurrentCalls = ConcurrentHashMap<Int, AtomicInteger>()

    fun tryAcquire(callerPid: Int): Boolean {
        val concurrent = concurrentCalls.getOrPut(callerPid) { AtomicInteger(0) }
        if (concurrent.incrementAndGet() > maxConcurrentCalls) {
            concurrent.decrementAndGet()
            FalconLogger.w("Security", "Concurrent limit: PID=$callerPid")
            return false
        }

        val now = clock()
        val window = windows.getOrPut(callerPid) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() >= 1000L) {
                window.removeFirst()
            }
            if (window.size >= maxCallsPerSecond) {
                concurrent.decrementAndGet()
                FalconLogger.w("Security", "Rate limit: PID=$callerPid")
                return false
            }
            window.addLast(now)
        }
        return true
    }

    fun release(callerPid: Int) {
        concurrentCalls[callerPid]?.decrementAndGet()
    }
}
