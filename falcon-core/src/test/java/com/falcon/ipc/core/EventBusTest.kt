package com.falcon.ipc.core

import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventBusTest {

    private lateinit var eventBus: EventBus

    @Before
    fun setup() {
        eventBus = EventBus()
    }

    @Test
    fun `local subscriber receives emitted event`() = runTest {
        val flow = eventBus.getLocalFlow("test.event")
        val data = "hello".toByteArray()

        val job = launch {
            val received = flow.first()
            assertArrayEquals(data, received)
        }

        // Small delay to ensure collector is ready
        kotlinx.coroutines.delay(50)
        eventBus.emit("test.event", data)
        job.join()
    }

    @Test
    fun `remote subscriber receives event via callback`() = runTest {
        var receivedData: ByteArray? = null

        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) {
                receivedData = event.args
            }
            override fun getEventKey(): String = "test.event"
        }

        eventBus.addRemoteSubscriber("test.event", callback)
        eventBus.emit("test.event", "world".toByteArray())

        assertNotNull(receivedData)
        assertArrayEquals("world".toByteArray(), receivedData)
    }

    @Test
    fun `remove remote subscriber stops delivery`() = runTest {
        var callCount = 0

        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) { callCount++ }
            override fun getEventKey(): String = "test.event"
        }

        eventBus.addRemoteSubscriber("test.event", callback)
        eventBus.emit("test.event", "first".toByteArray())
        assertEquals(1, callCount)

        eventBus.removeRemoteSubscriber("test.event", callback)
        eventBus.emit("test.event", "second".toByteArray())
        assertEquals(1, callCount) // should not increment
    }

    @Test
    fun `clear removes all subscribers`() = runTest {
        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) {}
            override fun getEventKey(): String = "test.event"
        }

        eventBus.addRemoteSubscriber("test.event", callback)
        eventBus.clear()

        // Should not crash, no subscribers
        eventBus.emit("test.event", "data".toByteArray())
    }

    @Test
    fun `multiple subscribers all receive events`() = runTest {
        var count1 = 0
        var count2 = 0

        val cb1 = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) { count1++ }
            override fun getEventKey(): String = "test.event"
        }
        val cb2 = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) { count2++ }
            override fun getEventKey(): String = "test.event"
        }

        eventBus.addRemoteSubscriber("test.event", cb1)
        eventBus.addRemoteSubscriber("test.event", cb2)
        eventBus.emit("test.event", "data".toByteArray())

        assertEquals(1, count1)
        assertEquals(1, count2)
    }
}
