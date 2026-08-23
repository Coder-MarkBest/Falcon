package com.falcon.ipc.integration

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.core.MessageRouter
import com.falcon.ipc.core.ServiceRegistry
import com.falcon.ipc.dynamic.DynamicPayload
import com.falcon.ipc.dynamic.DynamicProtocol
import com.falcon.ipc.dynamic.DynamicResponse
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.transport.BinderTransport
import com.falcon.ipc.transport.TransportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DynamicRpcRoundTripTest {
    @Test
    fun `dynamic call crosses real AIDL stub and Binder transport`() {
        val registry = ServiceRegistry()
        registry.dynamic.registerRoute("navigation/location/get", 2) { request ->
            DynamicResponse.success(DynamicPayload.build {
                putDouble("latitude", 39.9042)
                putString("coordinateSystem", request.body.getString("coordinateSystem"))
            })
        }
        val monitor = MonitorFacade().apply { setLevel(MonitorLevel.BASIC) }
        val router = MessageRouter(registry, monitor, PermissionChecker(emptyMap()), RateLimiter())
        val host = object : IIpcHost.Stub() {
            override fun invoke(request: IpcEnvelope): IpcEnvelope = try {
                IpcEnvelope.response(request.requestId, router.handleLocal(
                    request, "com.cluster", 42, ":cluster"
                ) as Bundle)
            } catch (e: Exception) {
                IpcEnvelope.error(-1, e.message ?: "error", request.requestId)
            }
            override fun subscribe(eventKey: String?, callback: IIpcEventCallback?) = Unit
            override fun unsubscribe(eventKey: String?, callback: IIpcEventCallback?) = Unit
            override fun getServiceInfo(): String = ""
            override fun invokeCallback(request: IpcEnvelope?, reply: IIpcEventCallback?) = Unit
        }
        val transport = BinderTransport(IIpcHost.Stub.asInterface(host.asBinder()))
        val envelope = IpcEnvelope(
            serviceKey = DynamicProtocol.SERVICE_KEY,
            method = DynamicProtocol.CALL,
            requestId = "req-42",
            argsBundle = Bundle().apply {
                putString("route", "navigation/location/get")
                putInt("version", 2)
                putBundle("body", DynamicPayload.build {
                    putString("coordinateSystem", "WGS84")
                }.toBundle())
            }
        )

        val result = transport.invoke(envelope)
        assertTrue(result is TransportResult.Success)
        val response = DynamicProtocol.decodeResponse((result as TransportResult.Success).data as Bundle)
        assertEquals(200, response.statusCode)
        assertEquals(39.9042, response.body.getDouble("latitude"), 0.0)
        assertEquals("WGS84", response.body.getString("coordinateSystem"))
        val stats = monitor.getStats().single()
        assertEquals("navigation/location/get", stats.methodName)
        assertEquals(1L, stats.successCount)
    }

    @Test
    fun `probe rejects incompatible protocol versions`() {
        val registry = ServiceRegistry()
        registry.dynamic.registerRoute("vehicle/status/get", 3) { DynamicResponse.success() }
        val router = MessageRouter(
            registry, MonitorFacade(), PermissionChecker(emptyMap()), RateLimiter()
        )
        val result = router.handleLocal(IpcEnvelope(
            serviceKey = DynamicProtocol.SERVICE_KEY,
            method = DynamicProtocol.PROBE,
            argsBundle = Bundle().apply {
                putString("kind", "route")
                putString("name", "vehicle/status/get")
                putIntArray("versions", intArrayOf(1, 2))
            }
        ), "com.cluster", 43, ":cluster") as Bundle

        assertEquals(404, result.getInt("status"))
        assertEquals(0, result.getInt("version"))
    }
}
