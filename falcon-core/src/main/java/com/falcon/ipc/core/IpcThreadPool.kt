package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

enum class IpcPriority {
    SAFETY,       // Highest: airbag, brake, steering
    NAVIGATION,   // Medium-high: GPS, route
    MEDIA,        // Medium: audio, video
    DIAGNOSTIC    // Lowest: logging, telemetry
}

data class ThreadPoolConfig(
    val corePoolSize: Int = 4,
    val maxPoolSize: Int = 8,
    val keepAliveMs: Long = 60_000,
    val priorityQueue: Boolean = false
)

class IpcThreadPool(
    private val config: ThreadPoolConfig = ThreadPoolConfig()
) {
    private val priorityCounter = AtomicInteger(0)

    private val executor: ThreadPoolExecutor by lazy {
        val queue: BlockingQueue<Runnable> = if (config.priorityQueue) {
            PriorityBlockingQueue(100, Comparator { a, b ->
                val pa = (a as? PriorityRunnable)?.priority?.ordinal ?: IpcPriority.DIAGNOSTIC.ordinal
                val pb = (b as? PriorityRunnable)?.priority?.ordinal ?: IpcPriority.DIAGNOSTIC.ordinal
                pa.compareTo(pb)
            })
        } else {
            LinkedBlockingQueue()
        }

        ThreadPoolExecutor(
            config.corePoolSize,
            config.maxPoolSize,
            config.keepAliveMs,
            TimeUnit.MILLISECONDS,
            queue,
            ThreadFactory { r ->
                Thread(r, "falcon-io-${priorityCounter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        )
    }

    val dispatcher: CoroutineDispatcher by lazy {
        executor.asCoroutineDispatcher()
    }

    fun submit(priority: IpcPriority = IpcPriority.DIAGNOSTIC, block: () -> Unit) {
        val runnable = if (config.priorityQueue) {
            PriorityRunnable(priority, block)
        } else {
            Runnable { block() }
        }
        executor.execute(runnable)
    }

    fun <T> submitCallable(priority: IpcPriority = IpcPriority.DIAGNOSTIC, block: () -> T): Future<T> {
        return executor.submit(Callable { block() })
    }

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

    private class PriorityRunnable(
        val priority: IpcPriority,
        private val block: () -> Unit
    ) : Runnable {
        override fun run() = block()
    }
}
