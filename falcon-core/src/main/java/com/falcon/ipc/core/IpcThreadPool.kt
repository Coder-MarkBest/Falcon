package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ThreadPoolConfig(
    val corePoolSize: Int = 4,
    val maxPoolSize: Int = 8,
    val keepAliveMs: Long = 60_000,
    val queueCapacity: Int = 512
)

class IpcThreadPool(
    config: ThreadPoolConfig = ThreadPoolConfig()
) {
    private val threadCounter = AtomicInteger(0)

    // Bounded queue + CallerRunsPolicy: when the pool and queue are saturated,
    // the calling thread executes the task directly, providing natural backpressure.
    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            config.corePoolSize,
            config.maxPoolSize,
            config.keepAliveMs,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(config.queueCapacity),
            ThreadFactory { r ->
                Thread(r, "falcon-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
            },
            ThreadPoolExecutor.CallerRunsPolicy()
        )
    }

    val dispatcher: CoroutineDispatcher by lazy { executor.asCoroutineDispatcher() }

    /** Submit a task for execution. Uses CallerRunsPolicy if saturated. */
    fun submit(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (e: RejectedExecutionException) {
            FalconLogger.w("ThreadPool", "Task rejected (pool shut down?): ${e.message}")
        }
    }

    fun <T> submitCallable(block: () -> T): Future<T> = executor.submit(Callable { block() })

    fun getActiveCount(): Int = executor.activeCount
    fun getPoolSize(): Int = executor.poolSize
    fun getQueueSize(): Int = executor.queue.size
    fun getCompletedCount(): Long = executor.completedTaskCount

    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
        FalconLogger.d("ThreadPool", "Shutdown complete")
    }
}
