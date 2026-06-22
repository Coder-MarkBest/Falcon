package com.falcon.cross.server

import android.os.Bundle
import com.falcon.cross.shared.CrossData
import com.falcon.cross.shared.CrossService_Proxy
import com.falcon.cross.shared.ICrossService
import com.falcon.cross.shared.ICrossService_Dispatcher
import com.falcon.cross.shared.VehicleData
import com.falcon.ipc.Falcon
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Device-free round-trip test for the cross-app [ICrossService] contract, run through the
 * REAL KSP-generated [CrossService_Proxy] and [ICrossService_Dispatcher] over a fake
 * in-JVM transport (no Binder crossing).
 *
 * Complements the on-device [com.falcon.cross.client.CrossAppTest] (true two-APK Binder)
 * by giving CI coverage of the Bundle codec for this contract's richer wire types —
 * Parcelable ([VehicleData]/[CrossData]), `Map<String, *>`, nullable returns, events,
 * streams, and callbacks — against the actual server impl, [CrossServiceImpl].
 */
@RunWith(RobolectricTestRunner::class)
class CrossServiceRoundTripTest {

    private val impl: ICrossService = CrossServiceImpl()
    private lateinit var proxy: CrossService_Proxy

    @Before
    fun setUp() {
        // The generated event proxy reads event buffer config from Falcon.getInstance().
        Falcon.init(RuntimeEnvironment.getApplication())

        val dispatcher = ICrossService_Dispatcher(impl)
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        val jobs = mutableMapOf<IIpcEventCallback, Job>()

        val transport = object : IpcTransport {
            override val maxPayloadSize: Int get() = 1 shl 20 // 1 MB

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

        proxy = CrossService_Proxy(transport, ICrossService::class.qualifiedName!!)
    }

    @After
    fun tearDown() {
        try { Falcon.getInstance().shutdown() } catch (_: Exception) {}
    }

    // ── @IpcMethod — primitives ──────────────────────────────────────────────

    @Test fun `ping round-trips`() = runBlocking {
        assertEquals("pong:hi", proxy.maybePing("hi", shouldFail = false))
    }

    @Test fun `add round-trips`() = runBlocking {
        assertEquals(5, proxy.add(2, 3))
    }

    @Test fun `echoBytes round-trips binary payload`() = runBlocking {
        val data = ByteArray(1024) { (it % 256).toByte() }
        assertArrayEquals(data, proxy.echoBytes(data))
    }

    // ── @IpcMethod — Parcelable ──────────────────────────────────────────────

    @Test fun `getVehicleData round-trips a Parcelable with nested collections`() = runBlocking {
        val v = proxy.getVehicleData("VIN12345")
        assertEquals("VIN12345", v.vin)
        assertEquals(88.5f, v.speedKmh)
        assertEquals(listOf("low_washer_fluid"), v.warningFlags)
        assertEquals(false, v.doorStates["front_left"])
        assertEquals(2.3f, v.tirePressure?.get("front_left"))
    }

    @Test fun `getBatchData round-trips CrossData`() = runBlocking {
        val d: CrossData = proxy.getBatchData(7)
        assertEquals(7, d.id)
        assertEquals("item#7", d.name)
        assertEquals(listOf("a", "b", "c"), d.tags)
        assertEquals(mapOf("priority" to 7, "severity" to 14), d.meta)
    }

    // ── @IpcMethod — collections & nullables ─────────────────────────────────

    @Test fun `filterWarnings round-trips a List`() = runBlocking {
        assertEquals(listOf("active_a", "active_b"),
            proxy.filterWarnings(listOf("active_a", "info_x", "active_b")))
    }

    @Test fun `getDoorStates round-trips a Map`() = runBlocking {
        assertEquals(true, proxy.getDoorStates()["rear_left"])
    }

    @Test fun `getTirePressure round-trips a nullable Map`() = runBlocking {
        assertEquals(2.4f, proxy.getTirePressure(installed = true)!!["front_right"])
        assertNull(proxy.getTirePressure(installed = false))
    }

    @Test fun `batchProcess round-trips List to Map`() = runBlocking {
        val m = proxy.batchProcess(1, listOf("ab", "x", "1234"))
        assertEquals(mapOf("ab" to true, "x" to false, "1234" to true), m)
    }

    @Test fun `findById round-trips nullable Parcelable`() = runBlocking {
        assertEquals("VIN99999", proxy.findById("VIN99999")?.vin)
        assertNull(proxy.findById("AB"))
    }

    @Test fun `maybePing round-trips a nullable primitive`() = runBlocking {
        assertNull(proxy.maybePing("hi", shouldFail = true))
    }

    // ── @IpcEvent / @IpcStream ───────────────────────────────────────────────

    @Test fun `speedAlerts event flow round-trips`() = runBlocking {
        // 60 + 15 per emission → 75, 90, 105
        assertEquals(listOf(75, 90, 105), proxy.speedAlerts().take(3).toList())
    }

    @Test fun `firmwareChunks stream round-trips`() = runBlocking {
        val chunks = proxy.firmwareChunks().take(3).toList()
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(8 * 1024, it.size) }
    }

    // ── @IpcCallback (success + error codes) ─────────────────────────────────

    @Test fun `slowLookup callback success`() {
        val results = mutableListOf<CrossData>()
        proxy.slowLookup("vehicle", object : IpcReply<CrossData> {
            override fun onResult(data: CrossData) { results.add(data) }
        })
        assertEquals("vehicle", results.single().name)
    }

    @Test fun `slowLookup callback error path`() {
        var observed = -1
        proxy.slowLookup("", object : IpcReply<CrossData> {
            override fun onResult(data: CrossData) {}
            override fun onError(code: Int, message: String) { observed = code }
        })
        assertEquals(1, observed) // empty query
    }

    @Test fun `validateVehicle callback distinct error codes`() {
        fun codeFor(vin: String): Int {
            var c = 0
            proxy.validateVehicle(vin, object : IpcReply<VehicleData> {
                override fun onResult(data: VehicleData) { c = 0 }
                override fun onError(code: Int, message: String) { c = code }
            })
            return c
        }
        assertEquals(0, codeFor("VIN12345")) // valid
        assertEquals(2, codeFor("  "))       // blank
        assertEquals(3, codeFor("AB"))       // too short
    }
}
