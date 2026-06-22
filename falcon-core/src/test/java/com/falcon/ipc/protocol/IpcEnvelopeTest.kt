package com.falcon.ipc.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcEnvelopeTest {

    /**
     * Regression guard: the default requestId must stay empty (no per-call UUID).
     * Reintroducing UUID.randomUUID() here would add a SecureRandom call to every IPC.
     */
    @Test
    fun `default requestId is empty (no per-call UUID)`() {
        assertEquals("", IpcEnvelope().requestId)
        assertEquals("", IpcEnvelope(serviceKey = "x", method = "m", methodId = 1).requestId)
    }
}
