package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

object EventProxy {

    fun <T> typedRemoteFlow(
        eventKey: String,
        transport: IpcTransport,
        capacity: Int = 64,
        overflow: BufferOverflow = BufferOverflow.DROP_OLDEST,
        decode: (Bundle) -> T
    ): Flow<T> = callbackFlow {
        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) {
                if (event.isError) {
                    close(com.falcon.ipc.protocol.IpcException(event.errorCode, event.errorMessage))
                    return
                }
                val result: ChannelResult<Unit> = trySend(decode(event.argsBundle ?: Bundle()))
                if (result.isFailure && !result.isClosed) {
                    FalconLogger.w("EventProxy", "Event buffer pressure on $eventKey (overflow=$overflow)")
                }
            }

            override fun getEventKey(): String = eventKey
        }
        transport.subscribe(eventKey, callback)
        awaitClose { transport.unsubscribe(eventKey, callback) }
    }.buffer(capacity = capacity, onBufferOverflow = overflow)
}
