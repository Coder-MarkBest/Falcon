package com.falcon.demo

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.service.IpcReply
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.TransportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Single-process round-trip test against the REAL KSP-generated
 * `DemoService_Proxy` and `IDemoService_Dispatcher`.
 *
 * In particular [add returns correct sum] is a regression test for a generator
 * bug where the proxy's local Bundle variable was hardcoded as `b`, colliding
 * with a method parameter named `b` (here `add(a, b)`) and producing
 * `putInt(b, "1", b)` — a Bundle where an Int was expected.
 */
@RunWith(RobolectricTestRunner::class)
class DemoRoundTripTest {

    private val impl = DemoServiceImpl()
    private lateinit var proxy: DemoService_Proxy

    @Before
    fun setUp() {
        // The generated dispatcher reads Falcon.getInstance().callTimeoutMs for suspend methods.
        com.falcon.ipc.Falcon.init(org.robolectric.RuntimeEnvironment.getApplication()) {
            timeout { callMs = 2_000 }
        }
        val dispatcher = IDemoService_Dispatcher(impl)
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val jobs = mutableMapOf<IIpcEventCallback, Job>()

        val transport = object : IpcTransport {
            override val maxPayloadSize: Int get() = 1 shl 20

            override fun invoke(envelope: IpcEnvelope): TransportResult {
                val result = dispatcher.dispatch(envelope.methodId, envelope.argsBundle ?: Bundle())
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
                        callback.onEvent(IpcEnvelope(serviceKey = eventKey, method = "__event__", argsBundle = b))
                    }
                }
            }

            override fun unsubscribe(eventKey: String, callback: IIpcEventCallback) {
                jobs.remove(callback)?.cancel()
            }
        }

        proxy = DemoService_Proxy(transport, IDemoService::class.qualifiedName!!)
    }

    @org.junit.After
    fun tearDown() {
        try { com.falcon.ipc.Falcon.getInstance().shutdown() } catch (_: Exception) {}
    }

    @Test
    fun `ping round-trips a string`() = runBlocking {
        assertTrue(proxy.ping("hi").startsWith("pong: hi"))
    }

    /** Regression: parameter named `b` must not collide with the generated Bundle local. */
    @Test
    fun `add returns correct sum`() = runBlocking {
        assertEquals(5, proxy.add(2, 3))
        assertEquals(-1, proxy.add(-4, 3))
    }

    /** Regression: params named like the generator's internal locals must not collide.
     *  If they did, the generated proxy would fail to compile (build failure). */
    @Test
    fun `collisionProbe with generator-local param names round-trips`() = runBlocking {
        // 1+2+4+8+16 = 31
        assertEquals(31, proxy.collisionProbe(1, 2, 4, 8, 16))
    }

    @Test
    fun `generated registry exposes a non-zero interface schema`() {
        val schema = com.falcon.ipc.generated.DemoFalconGeneratedRegistry
            .interfaceSchemas["com.falcon.demo.IDemoService"]
        org.junit.Assert.assertNotNull(schema)
        org.junit.Assert.assertNotEquals(0, schema)
    }

    /** Multi-app: a methodId the server build doesn't have returns METHOD_NOT_FOUND. */
    @Test
    fun `unknown methodId throws IpcException with METHOD_NOT_FOUND`() {
        val dispatcher = IDemoService_Dispatcher(impl)
        val ex = org.junit.Assert.assertThrows(com.falcon.ipc.protocol.IpcException::class.java) {
            dispatcher.dispatch(0x0BADF00D.toInt(), android.os.Bundle())
        }
        assertEquals(com.falcon.ipc.protocol.ErrorCode.METHOD_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `unknown callback id replies METHOD_NOT_FOUND`() {
        val dispatcher = IDemoService_Dispatcher(impl)
        var code = 0
        dispatcher.invokeCallback(0x0BADF00D.toInt(), android.os.Bundle()) { env ->
            if (env.isError) code = env.errorCode
        }
        assertEquals(com.falcon.ipc.protocol.ErrorCode.METHOD_NOT_FOUND, code)
    }

    @Test
    fun `unknown event id yields null flow`() {
        val dispatcher = IDemoService_Dispatcher(impl)
        org.junit.Assert.assertNull(dispatcher.eventFlow(0x0BADF00D.toInt()))
    }

    @Test
    fun `sumEach round-trips List of primitives`() = runBlocking {
        assertEquals(listOf(2L, 4L, 6L), proxy.sumEach(listOf(1, 2, 3)))
        assertEquals(emptyList<Long>(), proxy.sumEach(emptyList()))
    }

    @Test
    fun `usersByName round-trips Map with Parcelable values`() = runBlocking {
        val m = proxy.usersByName(listOf(1, 2))
        assertEquals(setOf("User#1", "User#2"), m.keys)
        assertEquals(2, m["User#2"]!!.id)
        org.junit.Assert.assertTrue(m["User#2"]!!.vip)   // 2 is even
        org.junit.Assert.assertFalse(m["User#1"]!!.vip)
    }

    @Test
    fun `getUser round-trips a Parcelable`() = runBlocking {
        val u = proxy.getUser(42)
        assertEquals(42, u.id)
        assertEquals("User#42", u.name)
        assertTrue(u.vip) // 42 is even
    }

    @Test
    fun `clock event flow emits incrementing ticks`() {
        val ticks = runBlocking { proxy.clock().take(3).toList() }
        assertEquals(listOf(0L, 1L, 2L), ticks)
    }

    @Test
    fun `download stream emits chunks`() {
        // Cross-process streams are HOT subscriptions: server-side flow completion
        // does NOT propagate to the client, so we must bound collection with take().
        // The impl emits exactly 5 chunks.
        val chunks = runBlocking { proxy.download().take(5).toList() }
        assertEquals(5, chunks.size)
        assertEquals(4 * 1024, chunks.first().size)
    }

    @Test
    fun `loadAsync callback delivers result`() {
        val results = mutableListOf<String>()
        proxy.loadAsync(7, object : IpcReply<String> {
            override fun onResult(data: String) { results.add(data) }
        })
        assertEquals(listOf("result for task #7"), results)
    }

    @Test
    fun `loadAsync callback delivers error for invalid input`() {
        var errCode = 0
        proxy.loadAsync(-1, object : IpcReply<String> {
            override fun onResult(data: String) {}
            override fun onError(code: Int, message: String) { errCode = code }
        })
        assertEquals(1, errCode)
    }
}
