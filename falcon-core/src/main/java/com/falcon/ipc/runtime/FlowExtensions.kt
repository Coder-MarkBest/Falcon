package com.falcon.ipc.runtime

import com.falcon.ipc.core.IpcState
import com.falcon.ipc.core.FalconManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed class IpcEvent<out T> {
    data class Data<T>(val value: T) : IpcEvent<T>()
    data object Disconnected : IpcEvent<Nothing>()
    data object Reconnected : IpcEvent<Nothing>()
}

/**
 * Throttle: emit at most one value per [periodMs] milliseconds.
 * Drops values that arrive during the throttle period.
 */
fun <T> Flow<T>.throttle(periodMs: Long): Flow<T> = channelFlow {
    var throttling = false
    collect { value ->
        if (!throttling) {
            send(value)
            throttling = true
            launch {
                delay(periodMs)
                throttling = false
            }
        }
    }
}

/**
 * Bind flow to IPC connection state.
 * When disconnected, emits IpcEvent.Disconnected.
 * When reconnected, emits IpcEvent.Reconnected then resumes data.
 */
fun <T> Flow<T>.withConnectionState(falconManager: FalconManager): Flow<IpcEvent<T>> = channelFlow {
    val upstream = this@withConnectionState

    // Listen for connection state changes
    falconManager.onConnectionStateChanged { state, _ ->
        when (state) {
            IpcState.DISCONNECTED -> trySend(IpcEvent.Disconnected)
            IpcState.CONNECTED -> trySend(IpcEvent.Reconnected)
            else -> {}
        }
    }

    // Forward data events
    upstream.collect { value ->
        send(IpcEvent.Data(value))
    }
}

/**
 * Retry subscription when connection is re-established.
 */
fun <T> Flow<T>.retryOnReconnect(
    falconManager: FalconManager,
    maxRetries: Int = Int.MAX_VALUE
): Flow<T> = flow {
    var retries = 0
    while (retries < maxRetries) {
        try {
            collect { emit(it) }
            break // Normal completion
        } catch (e: Exception) {
            retries++
            if (retries >= maxRetries) throw e
            delay(1000L * retries.coerceAtMost(30))
        }
    }
}

/**
 * Debounce: emit a value only if no new value arrives within [timeoutMs].
 * Uses coroutine delay for virtual-time compatibility in tests.
 */
fun <T> Flow<T>.debounce(timeoutMs: Long): Flow<T> = channelFlow {
    var debounceJob: kotlinx.coroutines.Job? = null
    collect { value ->
        debounceJob?.cancel()
        debounceJob = launch {
            delay(timeoutMs)
            send(value)
        }
    }
    debounceJob?.join()
}
