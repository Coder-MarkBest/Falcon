package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages per-call timeouts and supports cancellation by requestId.
 */
class TimeoutController {

    private val pendingCalls = ConcurrentHashMap<String, Job>()
    private val timeoutCount = AtomicLong(0)
    private val cancelCount = AtomicLong(0)

    /**
     * Execute a block with timeout. Returns null on timeout.
     * The block's Job is tracked by requestId for external cancellation.
     */
    suspend fun <T> withTimeout(
        requestId: String,
        timeoutMs: Long,
        block: suspend CoroutineScope.() -> T
    ): T? {
        return coroutineScope {
            val deferred = async { block() }
            pendingCalls[requestId] = deferred

            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    deferred.await()
                }

                if (result == null) {
                    timeoutCount.incrementAndGet()
                    FalconLogger.w("Timeout", "Call timed out: $requestId (${timeoutMs}ms)")
                    deferred.cancelAndJoin()
                }

                result
            } finally {
                pendingCalls.remove(requestId)
            }
        }
    }

    /**
     * Simpler timeout that doesn't need Deferred.
     */
    suspend fun <T> withSimpleTimeout(
        requestId: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): T? {
        pendingCalls[requestId] = coroutineScope { coroutineContext.job }

        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            timeoutCount.incrementAndGet()
            FalconLogger.w("Timeout", "Call timed out: $requestId (${timeoutMs}ms)")
            null
        } finally {
            pendingCalls.remove(requestId)
        }
    }

    /**
     * Cancel a pending call by requestId.
     */
    fun cancel(requestId: String): Boolean {
        val job = pendingCalls.remove(requestId)
        if (job != null) {
            job.cancel()
            cancelCount.incrementAndGet()
            FalconLogger.d("Timeout", "Call cancelled: $requestId")
            return true
        }
        return false
    }

    fun getTimeoutCount(): Long = timeoutCount.get()
    fun getCancelCount(): Long = cancelCount.get()
    fun getPendingCount(): Int = pendingCalls.size
}
