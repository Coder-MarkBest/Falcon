package com.falcon.ipc.transport

import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BinderTransportTimeoutTest {

    /** A host whose invoke() blocks longer than the watchdog. */
    private val hangingHost = object : IIpcHost.Stub() {
        override fun invoke(request: IpcEnvelope): IpcEnvelope {
            Thread.sleep(2_000)
            return IpcEnvelope(requestId = request.requestId)
        }
        override fun subscribe(k: String?, c: com.falcon.ipc.aidl.IIpcEventCallback?) {}
        override fun unsubscribe(k: String?, c: com.falcon.ipc.aidl.IIpcEventCallback?) {}
        override fun getServiceInfo(): String = ""
        override fun invokeCallback(r: IpcEnvelope?, c: com.falcon.ipc.aidl.IIpcEventCallback?) {}
    }

    @Test
    fun `invoke returns TRANSPORT_ERROR when peer exceeds watchdog`() {
        val transport = BinderTransport(hangingHost, maxPayloadSize = 1 shl 20, invokeTimeoutMs = 200)
        val start = System.currentTimeMillis()
        val result = transport.invoke(IpcEnvelope(serviceKey = "x", method = "m"))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("should return well before 2s", elapsed < 1_000)
        assertTrue(result is TransportResult.Error)
        assertEquals(ErrorCode.TRANSPORT_ERROR, (result as TransportResult.Error).code)
    }
}
