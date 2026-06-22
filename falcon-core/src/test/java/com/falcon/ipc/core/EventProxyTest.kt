package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.TransportResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventProxyTest {

    /** A transport that, on subscribe, immediately pushes N integer events. */
    private fun fakeTransport(count: Int) = object : IpcTransport {
        override val maxPayloadSize: Int get() = 1 shl 20
        override fun invoke(envelope: IpcEnvelope): TransportResult = TransportResult.Success(Bundle())
        override fun subscribe(eventKey: String, callback: IIpcEventCallback) {
            for (i in 0 until count) {
                callback.onEvent(IpcEnvelope(serviceKey = eventKey,
                    argsBundle = Bundle().apply { putInt("r", i) }))
            }
        }
        override fun unsubscribe(eventKey: String, callback: IIpcEventCallback) {}
    }

    @Test
    fun `typedRemoteFlow delivers events with SUSPEND backpressure`() {
        val flow = EventProxy.typedRemoteFlow(
            "svc#1", fakeTransport(5),
            capacity = 8, overflow = BufferOverflow.SUSPEND
        ) { b -> b.getInt("r") }
        val got = runBlocking { flow.take(3).toList() }
        assertEquals(listOf(0, 1, 2), got)
    }
}
