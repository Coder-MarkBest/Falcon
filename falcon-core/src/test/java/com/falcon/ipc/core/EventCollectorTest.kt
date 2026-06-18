package com.falcon.ipc.core

import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventCollectorTest {
    @Test fun `collects on first subscribe and stops on last`() = runBlocking {
        val collector = EventCollector()
        val flow = MutableSharedFlow<Bundle>(extraBufferCapacity = 8)
        var providerCalls = 0
        val received = mutableListOf<Bundle>()
        val provider: () -> Flow<Bundle>? = { providerCalls++; flow }

        collector.onSubscribe("k", provider) { received.add(it) }
        collector.onSubscribe("k", provider) { received.add(it) }
        delay(50)
        assertEquals(1, providerCalls)

        flow.emit(Bundle().apply { putInt("r", 7) })
        delay(50)
        assertTrue(received.isNotEmpty())

        collector.onUnsubscribe("k")
        collector.onUnsubscribe("k")
        delay(50)
        assertFalse(collector.isCollecting("k"))
    }
}
