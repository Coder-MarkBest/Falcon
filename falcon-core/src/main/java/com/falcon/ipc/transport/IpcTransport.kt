package com.falcon.ipc.transport

import com.falcon.ipc.protocol.IpcEnvelope

sealed class TransportResult {
    data class Success(val data: Any?) : TransportResult()
    data class Error(val code: Int, val message: String) : TransportResult()
}

interface IpcTransport {
    fun invoke(envelope: IpcEnvelope): TransportResult
    val maxPayloadSize: Int

    fun subscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
        throw UnsupportedOperationException("subscribe not supported by this transport")
    }

    fun unsubscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
        throw UnsupportedOperationException("unsubscribe not supported by this transport")
    }

    fun invokeCallback(envelope: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
        throw UnsupportedOperationException("invokeCallback not supported by this transport")
    }
}
