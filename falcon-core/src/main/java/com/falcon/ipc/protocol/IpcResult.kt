package com.falcon.ipc.protocol

sealed class IpcResult<out T> {
    data class Success<T>(val data: T) : IpcResult<T>()
    data class Failure(
        val code: Int,
        val message: String,
        val cause: Throwable? = null
    ) : IpcResult<Nothing>()
    data object Timeout : IpcResult<Nothing>()
    data object ServiceUnavailable : IpcResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this !is Success

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        else -> default
    }
}
