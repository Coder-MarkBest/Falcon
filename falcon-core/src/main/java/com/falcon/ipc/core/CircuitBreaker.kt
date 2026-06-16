package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class CircuitState {
    CLOSED,      // Normal operation
    OPEN,        // Tripped, rejecting calls
    HALF_OPEN    // Testing if service recovered
}

data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,
    val openDurationMs: Long = 30_000,
    val halfOpenMaxCalls: Int = 1
)

class CircuitBreaker(
    private val config: CircuitBreakerConfig = CircuitBreakerConfig()
) {
    private val circuits = ConcurrentHashMap<String, CircuitStateHolder>()

    internal class CircuitStateHolder {
        @Volatile var state: CircuitState = CircuitState.CLOSED
        val failureCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val lastFailureTime = AtomicLong(0)
        val halfOpenCalls = AtomicInteger(0)
    }

    /**
     * Check if a call to this service is allowed.
     * Returns true if the call should proceed, false if circuit is open.
     */
    fun allowCall(serviceKey: String): Boolean {
        val circuit = circuits.getOrPut(serviceKey) { CircuitStateHolder() }

        return when (circuit.state) {
            CircuitState.CLOSED -> true

            CircuitState.OPEN -> {
                val elapsed = System.currentTimeMillis() - circuit.lastFailureTime.get()
                if (elapsed >= config.openDurationMs) {
                    // Transition to HALF_OPEN
                    circuit.state = CircuitState.HALF_OPEN
                    circuit.halfOpenCalls.set(0)
                    FalconLogger.d("Circuit", "HALF_OPEN: $serviceKey")
                    true
                } else {
                    FalconLogger.w("Circuit", "OPEN (rejecting): $serviceKey")
                    false
                }
            }

            CircuitState.HALF_OPEN -> {
                if (circuit.halfOpenCalls.incrementAndGet() <= config.halfOpenMaxCalls) {
                    true
                } else {
                    false
                }
            }
        }
    }

    /**
     * Record a successful call.
     */
    fun recordSuccess(serviceKey: String) {
        val circuit = circuits[serviceKey] ?: return

        if (circuit.state == CircuitState.HALF_OPEN) {
            circuit.state = CircuitState.CLOSED
            circuit.failureCount.set(0)
            circuit.successCount.incrementAndGet()
            FalconLogger.d("Circuit", "CLOSED (recovered): $serviceKey")
        } else {
            circuit.successCount.incrementAndGet()
            circuit.failureCount.set(0) // Reset on success
        }
    }

    /**
     * Record a failed call.
     */
    fun recordFailure(serviceKey: String) {
        val circuit = circuits.getOrPut(serviceKey) { CircuitStateHolder() }
        circuit.lastFailureTime.set(System.currentTimeMillis())

        if (circuit.state == CircuitState.HALF_OPEN) {
            circuit.state = CircuitState.OPEN
            FalconLogger.w("Circuit", "OPEN (half-open failed): $serviceKey")
            return
        }

        val failures = circuit.failureCount.incrementAndGet()
        if (failures >= config.failureThreshold) {
            circuit.state = CircuitState.OPEN
            FalconLogger.w("Circuit", "OPEN (threshold $failures): $serviceKey")
        }
    }

    fun getState(serviceKey: String): CircuitState {
        return circuits[serviceKey]?.state ?: CircuitState.CLOSED
    }

    fun getFailureCount(serviceKey: String): Int {
        return circuits[serviceKey]?.failureCount?.get() ?: 0
    }

    fun reset(serviceKey: String) {
        circuits.remove(serviceKey)
    }

    fun resetAll() {
        circuits.clear()
    }
}
