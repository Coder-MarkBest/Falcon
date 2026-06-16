package com.falcon.ipc.protocol

object ErrorCode {
    const val SUCCESS = 0
    const val SERVICE_NOT_FOUND = 1001
    const val METHOD_NOT_FOUND = 1002
    const val PERMISSION_DENIED = 1003
    const val UNAUTHORIZED = 1004
    const val RATE_LIMITED = 1005
    const val TIMEOUT = 2001
    const val PEER_NOT_CONNECTED = 2002
    const val TRANSPORT_ERROR = 2003
    const val SERIALIZATION_ERROR = 3001
    const val UNKNOWN = -1
}
