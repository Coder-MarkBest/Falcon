package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ThreadPoolConfig(
    val corePoolSize: Int = 4,
    val maxPoolSize: Int = 8,
    val keepAliveMs: Long = 60_000
)

class IpcThreadPool(
    private val config: ThreadPoolConfig = ThreadPoolConfig()
) {
    private val threadCounter = AtomicInteger(0)

    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            config.corePoolSize,
            config.maxPoolSize,
            config.keepAliveMs,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { r ->
                Thread(r, "falcon-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
            }
        )
    }

    val dispatcher: CoroutineDispatcher by lazy { executor.asCoroutineDispatcher() }

    fun submit(block: () -> Unit) {
        executor.execute(block)
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
