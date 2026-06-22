package com.falcon.ipc.runtime

import com.falcon.ipc.core.IpcState
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed class IpcEvent<out T> {
    data class Data<T>(val value: T) : IpcEvent<T>()
    data object Disconnected : IpcEvent<Nothing>()
    data object Reconnected : IpcEvent<Nothing>()
}

/**
 * Throttle: emit at most one value per [periodMs] milliseconds.
 * Drops values that arrive during the throttle period.
 * Uses [AtomicBoolean] to eliminate the narrow race between flag check and coroutine launch.
 */
fun <T> Flow<T>.throttle(periodMs: Long): Flow<T> = channelFlow {
    val throttling = AtomicBoolean(false)
    collect { value ->
        if (throttling.compareAndSet(false, true)) {
            send(value)
            launch {
                delay(periodMs)
                throttling.set(false)
            }
        }
    }
}

/**
 * Bind flow to IPC connection state.
 * When disconnected, emits [IpcEvent.Disconnected].
 * When reconnected, emits [IpcEvent.Reconnected] then resumes data.
 *
 * The connection listener is automatically unregistered when the downstream
 * collector cancels.
 */
fun <T> Flow<T>.withConnectionState(falconManager: FalconManager): Flow<IpcEvent<T>> = callbackFlow {
    val upstream = this@withConnectionState

    val onStateChange: (IpcState, String) -> Unit = { state, _ ->
        when (state) {
            IpcState.DISCONNECTED -> trySend(IpcEvent.Disconnected)
            IpcState.CONNECTED -> trySend(IpcEvent.Reconnected)
            else -> {}
        }
    }

    falconManager.onConnectionStateChanged(onStateChange)

    // Forward data events
    try {
        upstream.collect { value ->
            send(IpcEvent.Data(value))
        }
    } catch (e: Exception) {
        FalconLogger.w("FlowExt", "withConnectionState upstream error: ${e.message}")
    }

    // Unregister on cancel/close — prevents the unbounded listener leak
    awaitClose {
        falconManager.removeConnectionStateCallback(onStateChange)
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
            FalconLogger.w("FlowExt", "Flow error, retry $retries/$maxRetries: ${e.message}")
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
