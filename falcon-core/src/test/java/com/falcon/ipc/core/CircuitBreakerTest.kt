package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CircuitBreakerTest {

    private lateinit var cb: CircuitBreaker

    @Before
    fun setup() {
        cb = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 3,
            openDurationMs = 1000,
            halfOpenMaxCalls = 1
        ))
    }

    @Test
    fun `initial state is CLOSED`() {
        assertEquals(CircuitState.CLOSED, cb.getState("svc"))
    }

    @Test
    fun `allows calls in CLOSED state`() {
        assertTrue(cb.allowCall("svc"))
    }

    @Test
    fun `opens after threshold failures`() {
        cb.recordFailure("svc")
        cb.recordFailure("svc")
        assertEquals(CircuitState.CLOSED, cb.getState("svc"))

        cb.recordFailure("svc") // 3rd failure = threshold
        assertEquals(CircuitState.OPEN, cb.getState("svc"))
    }

    @Test
    fun `rejects calls in OPEN state`() {
        repeat(3) { cb.recordFailure("svc") }
        assertFalse(cb.allowCall("svc"))
    }

    @Test
    fun `transitions to HALF_OPEN after open duration`() {
        repeat(3) { cb.recordFailure("svc") }
        assertEquals(CircuitState.OPEN, cb.getState("svc"))

        // Simulate time passing by using a very short openDurationMs
        val cb2 = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 3,
            openDurationMs = 0, // immediate half-open
            halfOpenMaxCalls = 1
        ))
        repeat(3) { cb2.recordFailure("svc") }
        assertTrue(cb2.allowCall("svc")) // Should transition to HALF_OPEN
    }

    @Test
    fun `recovers to CLOSED on success in HALF_OPEN`() {
        val cb2 = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 2,
            openDurationMs = 0,
            halfOpenMaxCalls = 1
        ))
        cb2.recordFailure("svc")
        cb2.recordFailure("svc")
        cb2.allowCall("svc") // HALF_OPEN
        cb2.recordSuccess("svc")
        assertEquals(CircuitState.CLOSED, cb2.getState("svc"))
    }

    @Test
    fun `reopens on failure in HALF_OPEN`() {
        val cb2 = CircuitBreaker(CircuitBreakerConfig(
            failureThreshold = 2,
            openDurationMs = 0,
            halfOpenMaxCalls = 1
        ))
        cb2.recordFailure("svc")
        cb2.recordFailure("svc")
        cb2.allowCall("svc") // HALF_OPEN
        cb2.recordFailure("svc")
        assertEquals(CircuitState.OPEN, cb2.getState("svc"))
    }

    @Test
    fun `success resets failure count`() {
        cb.recordFailure("svc")
        cb.recordFailure("svc")
        assertEquals(2, cb.getFailureCount("svc"))

        cb.recordSuccess("svc")
        assertEquals(0, cb.getFailureCount("svc"))
    }

    @Test
    fun `reset clears circuit`() {
        repeat(3) { cb.recordFailure("svc") }
        assertEquals(CircuitState.OPEN, cb.getState("svc"))

        cb.reset("svc")
        assertEquals(CircuitState.CLOSED, cb.getState("svc"))
    }
}
