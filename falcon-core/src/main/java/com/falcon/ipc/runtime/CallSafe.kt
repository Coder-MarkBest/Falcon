package com.falcon.ipc.runtime

import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcException
import com.falcon.ipc.protocol.IpcResult
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Type-safe IPC call wrapper that catches all failure modes into [IpcResult].
 *
 * Usage:
 * ```
 * when (val result = falcon.callSafe<INavService, Location> { it.getCurrentLocation() }) {
 *     is IpcResult.Success    -> updateMap(result.data)
 *     is IpcResult.Failure    -> showError(result.message)
 *     is IpcResult.Timeout    -> showError("Timed out")
 *     is IpcResult.ServiceUnavailable -> showError("Service not available")
 * }
 * ```
 *
 * @param timeoutMs Timeout in milliseconds. Use 0 to disable timeout.
 * @param block The IPC call to execute on the service proxy.
 */
suspend inline fun <reified S : IpcService, T> FalconManager.callSafe(
    timeoutMs: Long = this.callTimeoutMs,
    crossinline block: suspend (S) -> T
): IpcResult<T> {
    val service = getService(S::class)
        ?: return IpcResult.ServiceUnavailable

    return try {
        val result = if (timeoutMs > 0) {
            withTimeout(timeoutMs) { block(service) }
        } else {
            block(service)
        }
        @Suppress("UNCHECKED_CAST")
        IpcResult.Success(result as T)
    } catch (e: TimeoutCancellationException) {
        IpcResult.Timeout
    } catch (e: IllegalStateException) {
        IpcResult.Failure(ErrorCode.SERVICE_NOT_FOUND, e.message ?: "Service not found", e)
    } catch (e: SecurityException) {
        IpcResult.Failure(ErrorCode.PERMISSION_DENIED, e.message ?: "Permission denied", e)
    } catch (e: IpcException) {
        IpcResult.Failure(e.errorCode, e.message ?: "IPC error", e)
    } catch (e: Exception) {
        IpcResult.Failure(ErrorCode.UNKNOWN, e.message ?: "Unknown error", e)
    }
}

/**
 * Non-suspend variant for fire-and-forget or polling usage.
 *
 * **Important:** This returns null for ALL failure modes — service unavailable,
 * timeout, permission denied, and transport errors are indistinguishable.
 * For production code, prefer [callSafe] which gives a typed [IpcResult].
 * Exceptions are logged at WARN level for diagnostics.
 */
inline fun <reified S : IpcService, T> FalconManager.callSafeOrNull(
    crossinline block: (S) -> T
): T? {
    val service = getService(S::class) ?: return null
    return try {
        block(service)
    } catch (e: Exception) {
        FalconLogger.w("CallSafe", "callSafeOrNull failed for ${S::class.simpleName}: ${e.message}")
        null
    }
}
