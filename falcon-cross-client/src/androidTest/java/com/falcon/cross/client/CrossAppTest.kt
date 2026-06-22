package com.falcon.cross.client

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.falcon.ipc.Falcon
import com.falcon.ipc.getServiceSuspending
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * True cross-APK Binder test: the client APK (this app) discovers the server APK
 * (com.falcon.cross.server) via peerPackages, crossing a real Binder boundary
 * between two independently-built applications. This is the production multi-app
 * path — different APKs, different signing keys, different build artifacts.
 *
 * Setup: both APKs must be installed. The server APK is a separate build
 * (./gradlew :falcon-cross-server:assembleDebug && adb install -r ...).
 * Run: ./gradlew :falcon-cross-client:connectedDebugAndroidTest
 */
@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
@RunWith(AndroidJUnit4::class)
class CrossAppTest {

    private fun service(): ICrossService = runBlocking {
        withTimeout(15_000) {
            var svc: ICrossService? = null
            while (svc == null) {
                svc = Falcon.getInstance().getServiceSuspending<ICrossService>()
                if (svc == null) Thread.sleep(300)
            }
            svc!!
        }
    }

    // ── @IpcMethod ──────────────────────────────────────────────────────────

    @Test fun cross_request_response() = runBlocking {
        val r = service().ping("ciao")
        assertTrue(r.startsWith("pong:ciao"))
        assertTrue(r.contains("PID="))   // proves we're talking to a different process
    }

    @Test fun cross_parcelable_roundtrip() = runBlocking {
        val d = service().getBatchData(42)
        assertEquals(42, d.id)
        assertEquals("item#42", d.name)
        assertEquals(listOf("a", "b", "c"), d.tags)
        assertEquals(mapOf("priority" to 42, "severity" to 84), d.meta)
    }

    @Test fun cross_collections_roundtrip() = runBlocking {
        val m = service().batchProcess(1, listOf("ab", "x", "1234"))
        assertEquals(true, m["ab"])    // length 2 = even → true
        assertEquals(false, m["x"])    // length 1 = odd → false
        assertEquals(true, m["1234"])  // length 4 = even → true
    }

    @Test fun cross_nullable_map_roundtrip() = runBlocking {
        assertNotNull(service().getTirePressure(true))   // installed → map
        assertNull(service().getTirePressure(false))     // not installed → null
    }

    // ── @IpcEvent ───────────────────────────────────────────────────────────

    @Test fun cross_event_flow() = runBlocking {
        // speedAlerts() starts at 60 and adds 15 per emission → 75, 90, 105.
        val alerts = service().speedAlerts().take(3).toList()
        assertEquals(listOf(75, 90, 105), alerts)
    }

    // ── @IpcCallback ────────────────────────────────────────────────────────

    @Test fun cross_callback_success() = runBlocking {
        val results = mutableListOf<CrossData>()
        val latch = java.util.concurrent.CountDownLatch(1)
        service().slowLookup("test", object : IpcReply<CrossData> {
            override fun onResult(data: CrossData) { results.add(data); latch.countDown() }
        })
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, results.size)
        assertEquals("test", results[0].name)
    }

    @Test fun cross_callback_error() = runBlocking {
        var observed = 0
        val latch = java.util.concurrent.CountDownLatch(1)
        service().slowLookup("", object : IpcReply<CrossData> {
            override fun onResult(data: CrossData) {}
            override fun onError(code: Int, message: String) { observed = code; latch.countDown() }
        })
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, observed)   // "empty query"
    }

    // ── Schema compatibility ────────────────────────────────────────────────

    @Test fun cross_schema_compatible() {
        // Both APKs are built from identical sources → the schema hash matches,
        // so getServiceSuspending successfully returns a proxy (not null).
        val proxy = runBlocking {
            Falcon.getInstance().getServiceSuspending<ICrossService>()
        }
        assertNotNull("cross-app discovery returned proxy (schema must match)", proxy)
    }

    @Test fun cross_proxy_cached() = runBlocking {
        val first = Falcon.getInstance().getServiceSuspending<ICrossService>()
        val second = Falcon.getInstance().getServiceSuspending<ICrossService>()
        assertTrue("cross-app proxy is cached", first === second)
    }
}
