package com.falcon.ipc.protocol

/** Error codes for IPC operations, organized by category bands. */
object ErrorCode {
    const val SUCCESS = 0

    // 1000–1999: Server-side errors
    const val SERVICE_NOT_FOUND = 1001
    const val METHOD_NOT_FOUND = 1002
    const val PERMISSION_DENIED = 1003
    const val UNAUTHORIZED = 1004
    const val RATE_LIMITED = 1005

    // 2000–2999: Transport/connectivity errors
    const val TIMEOUT = 2001
    const val PEER_NOT_CONNECTED = 2002
    const val TRANSPORT_ERROR = 2003

    // 3000–3999: Serialization/encoding errors
    const val SERIALIZATION_ERROR = 3001

    const val UNKNOWN = -1

    /** True if the error is retryable (transport/connectivity, not logic/permission). */
    fun isRetryable(code: Int): Boolean = code in 2000..2999

    /** True if the error is a server-side rejection (permission, rate limit, not found). */
    fun isServerError(code: Int): Boolean = code in 1000..1999
}
