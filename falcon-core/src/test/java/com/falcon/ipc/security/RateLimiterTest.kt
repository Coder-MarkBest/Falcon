package com.falcon.ipc.security

import org.junit.Assert.*
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `allows calls within limit`() {
        val limiter = RateLimiter(maxCallsPerSecond = 100, maxConcurrentCalls = 10)
        for (i in 1..50) {
            assertTrue(limiter.tryAcquire(1234))
            limiter.release(1234)
        }
    }

    @Test
    fun `rejects calls exceeding concurrent limit`() {
        val limiter = RateLimiter(maxCallsPerSecond = 1000, maxConcurrentCalls = 2)
        assertTrue(limiter.tryAcquire(1234))
        assertTrue(limiter.tryAcquire(1234))
        assertFalse(limiter.tryAcquire(1234))

        limiter.release(1234)
        assertTrue(limiter.tryAcquire(1234))
    }

    @Test
    fun `different PIDs have independent limits`() {
        var now = 0L
        val limiter = RateLimiter(maxCallsPerSecond = 2, maxConcurrentCalls = 100, clock = { now })
        assertTrue(limiter.tryAcquire(1111))
        assertTrue(limiter.tryAcquire(1111))
        assertFalse(limiter.tryAcquire(1111))

        assertTrue(limiter.tryAcquire(2222))
        assertTrue(limiter.tryAcquire(2222))
        assertFalse(limiter.tryAcquire(2222))
    }

    // --- New sliding-window tests ---

    @Test
    fun `allows calls within window then blocks over limit`() {
        var now = 0L
        val rl = RateLimiter(maxCallsPerSecond = 3, maxConcurrentCalls = 100, clock = { now })
        assertTrue(rl.tryAcquire(1)); rl.release(1)
        assertTrue(rl.tryAcquire(1)); rl.release(1)
        assertTrue(rl.tryAcquire(1)); rl.release(1)
        assertFalse(rl.tryAcquire(1)) // 4th within same second
    }

    @Test
    fun `window slides - old timestamps expire`() {
        var now = 0L
        val rl = RateLimiter(maxCallsPerSecond = 2, maxConcurrentCalls = 100, clock = { now })
        assertTrue(rl.tryAcquire(1)); rl.release(1)
        assertTrue(rl.tryAcquire(1)); rl.release(1)
        assertFalse(rl.tryAcquire(1))
        now = 1001L // advance past 1s window
        assertTrue(rl.tryAcquire(1))
    }

    @Test
    fun `concurrent limit enforced and released`() {
        val rl = RateLimiter(maxCallsPerSecond = 1000, maxConcurrentCalls = 2, clock = { 0L })
        assertTrue(rl.tryAcquire(1))
        assertTrue(rl.tryAcquire(1))
        assertFalse(rl.tryAcquire(1)) // concurrent == 2 already
        rl.release(1)
        assertTrue(rl.tryAcquire(1))
    }
}
