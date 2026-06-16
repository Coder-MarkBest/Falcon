package com.falcon.ipc.monitor

import com.falcon.ipc.protocol.IpcResult

data class IpcRequest(
    val service: String,
    val method: String,
    val args: ByteArray?,
    val traceId: String?
)

interface IpcInterceptor {
    suspend fun intercept(
        request: IpcRequest,
        next: suspend (IpcRequest) -> IpcResult<*>
    ): IpcResult<*>
}
