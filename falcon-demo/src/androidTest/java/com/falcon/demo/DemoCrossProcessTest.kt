package com.falcon.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.falcon.ipc.Falcon
import com.falcon.ipc.getServiceSuspending
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real two-process Binder test: the app process (client) talks to [DemoServiceImpl]
 * in the `:server` process. Falcon is initialized by [DemoApp] in the app process;
 * `:server` is started on first discovery, exactly like production.
 */
@RunWith(AndroidJUnit4::class)
class DemoCrossProcessTest {

    private fun service(): IDemoService = runBlocking {
        withTimeout(10_000) {
            var svc: IDemoService? = null
            while (svc == null) {
                svc = Falcon.getInstance().getServiceSuspending<IDemoService>()
                if (svc == null) Thread.sleep(200)
            }
            svc
        }
    }

    @Test fun request_response_crosses_process() = runBlocking {
        val r = service().ping("ci")
        assertTrue(r.startsWith("pong: ci"))
    }

    @Test fun parcelable_crosses_process() = runBlocking {
        val u = service().getUser(42)
        assertEquals(42, u.id); assertTrue(u.vip)
    }

    @Test fun getService_caches_proxy_across_calls() = runBlocking {
        // After discovery, repeated lookups must return the SAME cached proxy instance
        // (no re-probe / rebuild per call).
        val first = Falcon.getInstance().getServiceSuspending<IDemoService>()
        val second = Falcon.getInstance().getServiceSuspending<IDemoService>()
        assertTrue(first === second)
    }

    @Test fun event_flow_crosses_process() = runBlocking {
        val ticks = service().clock().take(3).toList()
        assertEquals(listOf(0L, 1L, 2L), ticks)
    }

    // Real-Binder coverage for collections (Robolectric does not exercise true
    // cross-process Parcel classloader resolution).
    @Test fun list_of_primitives_crosses_process() = runBlocking {
        assertEquals(listOf(2L, 4L, 6L), service().sumEach(listOf(1, 2, 3)))
    }

    @Test fun empty_list_crosses_process() = runBlocking {
        assertEquals(emptyList<Long>(), service().sumEach(emptyList()))
    }

    @Test fun map_with_parcelable_values_crosses_process() = runBlocking {
        val m = service().usersByName(listOf(1, 2))
        assertEquals(setOf("User#1", "User#2"), m.keys)
        assertEquals(2, m["User#2"]!!.id)
        assertTrue(m["User#2"]!!.vip)
    }

    @Test fun nullable_collection_crosses_process() = runBlocking {
        assertEquals(listOf(1, 2, 3), service().maybeList(true))
        org.junit.Assert.assertNull(service().maybeList(false))   // null sentinel survives Binder
    }

    @Test fun callback_success_crosses_process() {
        val latch = CountDownLatch(1); var got = ""
        service().loadAsync(7, object : IpcReply<String> {
            override fun onResult(data: String) { got = data; latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals("result for task #7", got)
    }

    @Test fun callback_error_crosses_process() {
        val latch = CountDownLatch(1); var observed = 0
        service().loadAsync(-1, object : IpcReply<String> {
            override fun onResult(data: String) {}
            override fun onError(code: Int, message: String) { observed = code; latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(1, observed)
    }
}
