package com.falcon.ipc.protocol

/**
 * Exception that preserves the structured [ErrorCode] from the IPC transport layer.
 *
 * Unlike a plain [RuntimeException], this carries the numeric [errorCode] so callers
 * can programmatically distinguish between "service not found", "permission denied",
 * "timeout", etc. without parsing error messages.
 *
 * [CallSafe] extracts this code into [IpcResult.Failure].
 */
class IpcException(
    val errorCode: Int,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    override fun toString(): String =
        "IpcException(code=$errorCode, message=${message ?: "null"})"
}
