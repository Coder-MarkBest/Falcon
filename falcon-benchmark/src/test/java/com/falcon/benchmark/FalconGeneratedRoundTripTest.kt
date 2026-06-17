package com.falcon.benchmark

import android.os.Bundle
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.TransportResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip test exercising the REAL KSP-generated Proxy and Dispatcher in a single JVM process.
 *
 * Verifies:
 *   (a) Bundle typed codec (BundleCodec put/get) correctly encodes each parameter type.
 *   (b) methodId constants are consistent between Proxy and Dispatcher (same hash, same int).
 *   (c) Zero-reflection dispatch — the generated when() switch in the Dispatcher handles all IDs.
 *
 * NOTE: This is a single-process JVM test (no Binder crossing). True cross-process verification
 * (Binder transport across separate Linux processes) requires a device/emulator running the APK and
 * is out of scope for this unit test suite.
 */
@RunWith(RobolectricTestRunner::class)
class FalconGeneratedRoundTripTest {

    /** Minimal server-side implementation of the benchmark service. */
    private val impl = object : IBenchmarkFalconService {
        override fun echoString(input: String): String = input
        override fun computeSum(from: Int, to: Int): Long = (from + to).toLong()
        override fun echoBytes(data: ByteArray): ByteArray = data
    }

    private lateinit var proxy: BenchmarkFalconService_Proxy

    @Before
    fun setUp() {
        val dispatcher = IBenchmarkFalconService_Dispatcher(impl)

        val transport = object : IpcTransport {
            override val maxPayloadSize: Int get() = 1 shl 20 // 1 MB

            override fun invoke(envelope: IpcEnvelope): TransportResult {
                val args = envelope.argsBundle ?: Bundle()
                val result = dispatcher.dispatch(envelope.methodId, args)
                return TransportResult.Success(result)
            }
        }

        proxy = BenchmarkFalconService_Proxy(
            transport = transport,
            serviceKey = "com.falcon.benchmark.IBenchmarkFalconService"
        )
    }

    @Test
    fun `echoString round-trips a non-empty string`() {
        assertEquals("hello IPC", proxy.echoString("hello IPC"))
    }

    @Test
    fun `echoString round-trips an empty string`() {
        assertEquals("", proxy.echoString(""))
    }

    @Test
    fun `computeSum returns correct Long sum`() {
        assertEquals(7L, proxy.computeSum(3, 4))
    }

    @Test
    fun `computeSum handles negative operands`() {
        assertEquals(-1L, proxy.computeSum(-5, 4))
    }

    @Test
    fun `computeSum handles zero`() {
        assertEquals(0L, proxy.computeSum(0, 0))
    }

    @Test
    fun `echoBytes round-trips a non-empty byte array`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), proxy.echoBytes(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `echoBytes round-trips an empty byte array`() {
        assertArrayEquals(byteArrayOf(), proxy.echoBytes(byteArrayOf()))
    }

    @Test
    fun `echoBytes round-trips binary data`() {
        val data = (0..255).map { it.toByte() }.toByteArray()
        assertArrayEquals(data, proxy.echoBytes(data))
    }
}
