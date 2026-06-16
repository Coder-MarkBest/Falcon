package com.falcon.ipc.core

import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class EventBus {

    // Local event emitters (service -> SharedFlow)
    private val localEmitters = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()

    // Remote subscribers (eventKey -> list of AIDL callbacks)
    private val remoteSubscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<IIpcEventCallback>>()

    fun getLocalFlow(eventKey: String): Flow<ByteArray> {
        return localEmitters.getOrPut(eventKey) {
            MutableSharedFlow(
                extraBufferCapacity = 64,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }.asSharedFlow()
    }

    suspend fun emit(eventKey: String, data: ByteArray) {
        // Emit to local subscribers
        localEmitters[eventKey]?.emit(data)

        // Emit to remote subscribers
        val envelope = IpcEnvelope(
            serviceKey = eventKey,
            method = "__event__",
            args = data
        )
        remoteSubscribers[eventKey]?.forEach { callback ->
            try {
                callback.onEvent(envelope)
            } catch (e: Exception) {
                FalconLogger.w("EventBus", "Failed to deliver event to remote subscriber: ${e.message}")
            }
        }
    }

    fun addRemoteSubscriber(eventKey: String, callback: IIpcEventCallback) {
        remoteSubscribers.getOrPut(eventKey) { CopyOnWriteArrayList() }.add(callback)
        FalconLogger.d("EventBus", "Remote subscriber added: $eventKey")
    }

    fun removeRemoteSubscriber(eventKey: String, callback: IIpcEventCallback) {
        remoteSubscribers[eventKey]?.remove(callback)
    }

    fun clear() {
        localEmitters.clear()
        remoteSubscribers.clear()
    }
}
