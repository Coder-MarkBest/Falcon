package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IpcThreadPoolTest {

    private lateinit var pool: IpcThreadPool

    @After
    fun teardown() {
        if (::pool.isInitialized) pool.shutdown()
    }

    @Test
    fun `executes tasks`() {
        pool = IpcThreadPool()
        val latch = CountDownLatch(1)
        val executed = AtomicInteger(0)

        pool.submit {
            executed.incrementAndGet()
            latch.countDown()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, executed.get())
    }

    @Test
    fun `runs multiple tasks concurrently`() {
        pool = IpcThreadPool(ThreadPoolConfig(corePoolSize = 4, maxPoolSize = 4))
        val latch = CountDownLatch(4)
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        repeat(4) {
            pool.submit {
                val c = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { max -> maxOf(max, c) }
                Thread.sleep(100)
                concurrent.decrementAndGet()
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(maxConcurrent.get() > 1) // Some ran concurrently
    }

    @Test
    fun `priority queue processes high priority first`() {
        pool = IpcThreadPool(ThreadPoolConfig(
            corePoolSize = 1,
            maxPoolSize = 1,
            priorityQueue = true
        ))

        val results = mutableListOf<String>()
        val gate = CountDownLatch(1)

        // Block the single thread
        pool.submit(IpcPriority.DIAGNOSTIC) {
            gate.await()
        }

        // Queue tasks with different priorities
        pool.submit(IpcPriority.DIAGNOSTIC) { synchronized(results) { results.add("diag") } }
        pool.submit(IpcPriority.SAFETY) { synchronized(results) { results.add("safety") } }
        pool.submit(IpcPriority.MEDIA) { synchronized(results) { results.add("media") } }

        // Release the gate
        gate.countDown()
        Thread.sleep(500) // Wait for all to complete

        // Safety should be first after the blocking task
        if (results.size >= 2) {
            assertEquals("safety", results[0])
        }
    }

    @Test
    fun `submitCallable returns result`() {
        pool = IpcThreadPool()
        val future = pool.submitCallable { 42 }
        assertEquals(42, future.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `pool size respects config`() {
        pool = IpcThreadPool(ThreadPoolConfig(corePoolSize = 2, maxPoolSize = 3))

        val latch = CountDownLatch(3)
        repeat(3) {
            pool.submit {
                Thread.sleep(200)
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        assertTrue(pool.getPoolSize() <= 3)
    }

    @Test
    fun `shutdown completes gracefully`() {
        pool = IpcThreadPool()
        pool.submit { Thread.sleep(100) }
        pool.shutdown()
        // Should not throw
    }
}
