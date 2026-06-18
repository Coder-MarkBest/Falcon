package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.transport.IpcTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object EventProxy {

    fun <T> typedRemoteFlow(
        eventKey: String,
        transport: IpcTransport,
        decode: (Bundle) -> T
    ): Flow<T> = callbackFlow {
        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) {
                trySend(decode(event.argsBundle ?: android.os.Bundle()))
            }

            override fun getEventKey(): String = eventKey
        }
        transport.subscribe(eventKey, callback)
        awaitClose { transport.unsubscribe(eventKey, callback) }
    }
}
