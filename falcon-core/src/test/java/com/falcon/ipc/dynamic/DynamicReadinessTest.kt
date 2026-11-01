package com.falcon.ipc.dynamic

import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import com.falcon.ipc.Falcon
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.core.PeerManager
import com.falcon.ipc.core.PeerConnection
import com.falcon.ipc.protocol.*
import com.falcon.ipc.transport.BinderTransport
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DynamicReadinessTest {
    private val target = DynamicTarget("com.provider", "com.provider:server")
    @After fun cleanup() { Falcon.instance?.shutdown() }

    private class Host : IIpcHost.Stub() {
        @Volatile var ready = false
        var failBusiness = false
        val probed = CompletableDeferred<Unit>()
        val businessCalls = AtomicInteger()
        override fun invoke(request: IpcEnvelope): IpcEnvelope {
            if (request.method == DynamicProtocol.PROBE) {
                probed.complete(Unit)
                return IpcEnvelope.response(request.requestId, Bundle().apply { putInt("version", if (ready) 1 else 0) })
            }
            businessCalls.incrementAndGet()
            return if (failBusiness) IpcEnvelope.error(ErrorCode.TRANSPORT_ERROR, "Lost response", request.requestId)
            else IpcEnvelope.response(request.requestId, DynamicProtocol.encodeResponse(DynamicResponse.success()))
        }
        override fun subscribe(k: String?, c: IIpcEventCallback?) = Unit
        override fun unsubscribe(k: String?, c: IIpcEventCallback?) = Unit
        override fun getServiceInfo() = ""
        override fun invokeCallback(r: IpcEnvelope?, c: IIpcEventCallback?) = Unit
    }
    // Supply a Binder peer without Android discovery; exercise the real manager, probe and dispatch.
    private fun connect(manager: FalconManager, host: Host) {
        val peers = FalconManager::class.java.getDeclaredField("peerManager").apply { isAccessible = true }.get(manager) as PeerManager
        @Suppress("UNCHECKED_CAST")
        val connections = PeerManager::class.java.getDeclaredField("connections").apply { isAccessible = true }
            .get(peers) as MutableMap<String, PeerConnection>
        connections[target.processName] = PeerConnection(target.processName, Uri.parse("content://${target.packageName}.falcon.registry"),
            BinderTransport(host), host.asBinder(), IBinder.DeathRecipient { })
    }
    @Test fun `waits for delayed connection and route registration before one business call`() = runBlocking {
        val manager = Falcon.init(RuntimeEnvironment.getApplication()) { timeout { connectMs = 3000 } }
        val host = Host()
        val result = async { manager.callTo(target, "demo/write") }
        delay(150)
        assertFalse(result.isCompleted)
        connect(manager, host)
        withTimeout(1500) { host.probed.await() }
        assertEquals(0, host.businessCalls.get())
        host.ready = true
        assertTrue(result.await().isSuccessful)
        assertEquals(1, host.businessCalls.get())
    }
    @Test fun `unready route times out with explicit not-dispatched error`() = runBlocking {
        val manager = Falcon.init(RuntimeEnvironment.getApplication()) { timeout { connectMs = 200 } }
        val host = Host()
        connect(manager, host)
        try { manager.callTo(target, "demo/write"); fail() }
        catch (e: IpcException) { assertEquals(ErrorCode.PROVIDER_NOT_READY, e.errorCode) }
        assertEquals(0, host.businessCalls.get())
    }
    @Test fun `cancelling readiness wait never dispatches business call`() = runBlocking {
        val manager = Falcon.init(RuntimeEnvironment.getApplication()) { timeout { connectMs = 3000 } }
        val host = Host()
        connect(manager, host)
        val result = launch { manager.callTo(target, "demo/write") }
        withTimeout(1500) { host.probed.await() }
        result.cancelAndJoin()
        host.ready = true
        delay(150)
        assertEquals(0, host.businessCalls.get())
    }
    @Test fun `lost response after dispatch is never retried by readiness loop`() = runBlocking {
        val manager = Falcon.init(RuntimeEnvironment.getApplication()) { timeout { connectMs = 1000 } }
        val host = Host().apply { ready = true; failBusiness = true }
        connect(manager, host)
        try { manager.callTo(target, "demo/write"); fail() }
        catch (e: IpcException) { assertEquals(ErrorCode.TRANSPORT_ERROR, e.errorCode) }
        assertEquals(1, host.businessCalls.get())
    }
}
