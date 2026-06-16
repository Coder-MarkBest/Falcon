package com.falcon.ipc.core

import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object EventProxy {

    /**
     * Creates a Flow that receives events from a remote process via AIDL callback.
     */
    fun remoteEventFlow(
        eventKey: String,
        subscribe: (IIpcEventCallback) -> Unit,
        unsubscribe: (IIpcEventCallback) -> Unit
    ): Flow<ByteArray> = callbackFlow {
        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) {
                val data = event.args
                if (data != null) {
                    trySend(data)
                }
            }

            override fun getEventKey(): String = eventKey
        }

        subscribe(callback)
        FalconLogger.d("EventProxy", "Subscribed to remote event: $eventKey")

        awaitClose {
            unsubscribe(callback)
            FalconLogger.d("EventProxy", "Unsubscribed from remote event: $eventKey")
        }
    }
}
