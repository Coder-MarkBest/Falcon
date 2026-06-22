package com.falcon.ipc.security

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(
    private val maxCallsPerSecond: Int = 1000,
    private val maxConcurrentCalls: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val windows = ConcurrentHashMap<Int, ArrayDeque<Long>>()
    private val concurrentCalls = ConcurrentHashMap<Int, AtomicInteger>()
    private val cleanupJob: Job

    init {
        // Periodic cleanup of stale PID entries (idle for >60s with no concurrent calls)
        cleanupJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            while (isActive) {
                delay(60_000L)
                val now = clock()
                val toRemove = mutableListOf<Int>()
                windows.forEach { (pid, window) ->
                    synchronized(window) {
                        // Remove entries older than 60s
                        while (window.isNotEmpty() && now - window.first() >= 60_000L) {
                            window.removeFirst()
                        }
                        if (window.isEmpty() && (concurrentCalls[pid]?.get() ?: 0) <= 0) {
                            toRemove.add(pid)
                        }
                    }
                }
                toRemove.forEach { pid ->
                    windows.remove(pid)
                    concurrentCalls.remove(pid)
                }
                if (toRemove.isNotEmpty()) {
                    FalconLogger.d("Security", "Cleaned up ${toRemove.size} stale rate-limit entries")
                }
            }
        }
    }

    fun tryAcquire(callerPid: Int): Boolean {
        // Zero or negative limits mean "unlimited" — skip both checks
        if (maxCallsPerSecond <= 0 && maxConcurrentCalls <= 0) return true

        if (maxConcurrentCalls > 0) {
            val concurrent = concurrentCalls.getOrPut(callerPid) { AtomicInteger(0) }
            if (concurrent.incrementAndGet() > maxConcurrentCalls) {
                concurrent.decrementAndGet()
                FalconLogger.w("Security", "Concurrent limit: PID=$callerPid")
                return false
            }
        }

        if (maxCallsPerSecond <= 0) return true

        val now = clock()
        val window = windows.getOrPut(callerPid) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() >= 1000L) {
                window.removeFirst()
            }
            if (window.size >= maxCallsPerSecond) {
                if (maxConcurrentCalls > 0) {
                    concurrentCalls[callerPid]?.decrementAndGet()
                }
                FalconLogger.w("Security", "Rate limit: PID=$callerPid")
                return false
            }
            window.addLast(now)
        }
        return true
    }

    fun release(callerPid: Int) {
        if (maxConcurrentCalls > 0) {
            concurrentCalls[callerPid]?.decrementAndGet()
        }
    }

    /** Stop periodic cleanup (called on framework shutdown). */
    fun shutdown() {
        cleanupJob.cancel()
    }
}
