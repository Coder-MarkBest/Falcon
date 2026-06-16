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
    fun `rejects calls exceeding rate limit`() {
        val limiter = RateLimiter(maxCallsPerSecond = 5, maxConcurrentCalls = 100)
        var allowed = 0
        for (i in 1..10) {
            if (limiter.tryAcquire(1234)) allowed++
        }
        assertEquals(5, allowed)
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
        val limiter = RateLimiter(maxCallsPerSecond = 2, maxConcurrentCalls = 100)
        assertTrue(limiter.tryAcquire(1111))
        assertTrue(limiter.tryAcquire(1111))
        assertFalse(limiter.tryAcquire(1111))

        assertTrue(limiter.tryAcquire(2222))
        assertTrue(limiter.tryAcquire(2222))
        assertFalse(limiter.tryAcquire(2222))
    }

    @Test
    fun `resetCounters allows new calls`() {
        val limiter = RateLimiter(maxCallsPerSecond = 2, maxConcurrentCalls = 100)
        assertTrue(limiter.tryAcquire(1234))
        assertTrue(limiter.tryAcquire(1234))
        assertFalse(limiter.tryAcquire(1234))

        limiter.resetCounters()
        assertTrue(limiter.tryAcquire(1234))
    }
}
