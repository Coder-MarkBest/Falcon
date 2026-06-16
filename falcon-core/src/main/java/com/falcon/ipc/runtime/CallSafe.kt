package com.falcon.ipc.runtime

import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcResult
import com.falcon.ipc.service.IpcService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Type-safe IPC call wrapper that catches all failure modes into IpcResult.
 *
 * Usage:
 * ```
 * when (val result = falcon.callSafe<INavService, Location> { it.getCurrentLocation() }) {
 *     is IpcResult.Success -> updateMap(result.data)
 *     is IpcResult.Failure -> showError(result.message)
 *     is IpcResult.Timeout -> showError("Timed out")
 *     is IpcResult.ServiceUnavailable -> showError("Service not available")
 * }
 * ```
 */
suspend inline fun <reified S : IpcService, T> FalconManager.callSafe(
    timeoutMs: Long = 5000L,
    crossinline block: suspend (S) -> T
): IpcResult<T> {
    val service = getService(S::class)
        ?: return IpcResult.ServiceUnavailable

    return try {
        val result = withTimeoutOrNull(timeoutMs) {
            block(service)
        }

        if (result == null && timeoutMs > 0) {
            IpcResult.Timeout
        } else {
            @Suppress("UNCHECKED_CAST")
            IpcResult.Success(result as T)
        }
    } catch (e: TimeoutCancellationException) {
        IpcResult.Timeout
    } catch (e: IllegalStateException) {
        IpcResult.Failure(ErrorCode.SERVICE_NOT_FOUND, e.message ?: "Service not found", e)
    } catch (e: SecurityException) {
        IpcResult.Failure(ErrorCode.PERMISSION_DENIED, e.message ?: "Permission denied", e)
    } catch (e: Exception) {
        IpcResult.Failure(ErrorCode.UNKNOWN, e.message ?: "Unknown error", e)
    }
}

/**
 * Non-suspend variant for callback-style usage.
 */
inline fun <reified S : IpcService, T> FalconManager.callSafeOrNull(
    crossinline block: (S) -> T
): T? {
    val service = getService(S::class) ?: return null
    return try {
        block(service)
    } catch (e: Exception) {
        null
    }
}
