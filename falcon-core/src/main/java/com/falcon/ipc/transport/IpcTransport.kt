package com.falcon.ipc.transport

import com.falcon.ipc.protocol.IpcEnvelope

sealed class TransportResult {
    data class Success(val data: Any?) : TransportResult()
    data class Error(val code: Int, val message: String) : TransportResult()
}

interface IpcTransport {
    fun invoke(envelope: IpcEnvelope): TransportResult
    val maxPayloadSize: Int
}
