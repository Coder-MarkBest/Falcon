package com.falcon.benchmark

import android.os.Bundle
import com.falcon.ipc.Falcon
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.service.IpcReply
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.TransportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Round-trip test exercising the REAL KSP-generated Proxy and Dispatcher in a single JVM process.
 *
 * Verifies:
 *   (a) Bundle typed codec (BundleCodec put/get) correctly encodes each parameter type.
 *   (b) methodId constants are consistent between Proxy and Dispatcher (same hash, same int).
 *   (c) Zero-reflection dispatch — the generated when() switch in the Dispatcher handles all IDs.
 *   (d) @IpcEvent Flow round-trips via subscribe/unsubscribe fake transport.
 *   (e) @IpcCallback round-trips via invokeCallback fake transport.
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
        override fun ticks() = flowOf(1, 2, 3)
        override fun fetch(id: Int, reply: IpcReply<String>) {
            reply.onResult("v$id")
        }
    }

    private lateinit var proxy: BenchmarkFalconService_Proxy

    @Before
    fun setUp() {
        // The generated event proxy (ticks()) reads event buffer config from
        // Falcon.getInstance(); initialize a minimal Falcon so it can resolve.
        Falcon.init(RuntimeEnvironment.getApplication())

        val dispatcher = IBenchmarkFalconService_Dispatcher(impl)
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val jobs = mutableMapOf<IIpcEventCallback, Job>()

        val transport = object : IpcTransport {
            override val maxPayloadSize: Int get() = 1 shl 20 // 1 MB

            override fun invoke(envelope: IpcEnvelope): TransportResult {
                val args = envelope.argsBundle ?: Bundle()
                val result = dispatcher.dispatch(envelope.methodId, args)
                return TransportResult.Success(result)
            }

            override fun invokeCallback(envelope: IpcEnvelope, reply: IIpcEventCallback) {
                dispatcher.invokeCallback(envelope.methodId, envelope.argsBundle ?: Bundle()) { env ->
                    reply.onEvent(env.copy(requestId = envelope.requestId))
                }
            }

            override fun subscribe(eventKey: String, callback: IIpcEventCallback) {
                val methodId = eventKey.substringAfter("#").toInt()
                val flow = dispatcher.eventFlow(methodId) ?: return
                jobs[callback] = collectScope.launch {
                    flow.collect { b ->
                        callback.onEvent(
                            IpcEnvelope(serviceKey = eventKey, method = "__event__", argsBundle = b)
                        )
                    }
                }
            }

            override fun unsubscribe(eventKey: String, callback: IIpcEventCallback) {
                jobs.remove(callback)?.cancel()
            }
        }

        proxy = BenchmarkFalconService_Proxy(
            transport = transport,
            serviceKey = "com.falcon.benchmark.IBenchmarkFalconService"
        )
    }

    // ── @IpcMethod tests ──────────────────────────────────────────────────────

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

    // ── @IpcCallback tests ────────────────────────────────────────────────────

    @Test
    fun `callback round trips`() {
        val results = mutableListOf<String>()
        proxy.fetch(42, object : IpcReply<String> {
            override fun onResult(data: String) {
                results.add(data)
            }
        })
        assertEquals(listOf("v42"), results)
    }

    // ── @IpcEvent tests ───────────────────────────────────────────────────────

    @Test
    fun `event flow round trips`() {
        val collected = runBlocking {
            proxy.ticks().take(3).toList()
        }
        assertEquals(listOf(1, 2, 3), collected)
    }

    @After
    fun tearDown() {
        // Reset the Falcon singleton so each test gets a clean instance.
        try { Falcon.getInstance().shutdown() } catch (_: Exception) {}
    }
}
