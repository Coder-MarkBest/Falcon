package com.falcon.ipc.integration

import android.os.Bundle
import com.falcon.ipc.core.*
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.runtime.IpcDispatcher
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntegrationTestSetup {

    interface INavService : IpcService {
        fun getLocation(): String
        fun calculateRoute(dest: String): Int
    }

    interface IMediaService : IpcService {
        fun playSong(name: String): Boolean
        fun getVolume(): Int
    }

    // Method IDs
    private val NAV_GET_LOCATION = 1
    private val NAV_CALCULATE_ROUTE = 2
    private val MEDIA_PLAY_SONG = 1
    private val MEDIA_GET_VOLUME = 2

    private fun navDispatcher() = object : IpcDispatcher {
        override fun dispatch(methodId: Int, args: Bundle): Bundle = when (methodId) {
            NAV_GET_LOCATION -> Bundle().apply { putString("result", "Beijing, 39.9, 116.4") }
            NAV_CALCULATE_ROUTE -> Bundle().apply { putInt("result", 42) }
            else -> throw IllegalArgumentException("Unknown methodId=$methodId")
        }
    }

    private fun mediaDispatcher() = object : IpcDispatcher {
        override fun dispatch(methodId: Int, args: Bundle): Bundle = when (methodId) {
            MEDIA_PLAY_SONG -> Bundle().apply { putBoolean("result", true) }
            MEDIA_GET_VOLUME -> Bundle().apply { putInt("result", 50) }
            else -> throw IllegalArgumentException("Unknown methodId=$methodId")
        }
    }

    @Test
    fun `end-to-end service registration and call`() {
        val registry = ServiceRegistry()
        registry.registerDispatcher(INavService::class.qualifiedName!!, navDispatcher())
        registry.registerDispatcher(IMediaService::class.qualifiedName!!, mediaDispatcher())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )

        val navResult = router.handleLocal(
            IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation",
                methodId = NAV_GET_LOCATION, argsBundle = Bundle()),
            "test", 0
        ) as Bundle
        assertEquals("Beijing, 39.9, 116.4", navResult.getString("result"))

        val mediaResult = router.handleLocal(
            IpcEnvelope(serviceKey = IMediaService::class.qualifiedName!!, method = "getVolume",
                methodId = MEDIA_GET_VOLUME, argsBundle = Bundle()),
            "test", 0
        ) as Bundle
        assertEquals(50, mediaResult.getInt("result"))
    }

    @Test
    fun `stress test - 1000 calls`() {
        val registry = ServiceRegistry()
        registry.registerDispatcher(INavService::class.qualifiedName!!, navDispatcher())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter(maxCallsPerSecond = 10000)
        )

        val start = System.currentTimeMillis()
        repeat(1000) {
            router.handleLocal(
                IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation",
                    methodId = NAV_GET_LOCATION, argsBundle = Bundle()),
                "test", 0
            )
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000 calls should complete in < 5s, took ${elapsed}ms", elapsed < 5000)
    }

    @Test
    fun `batch execution integration`() {
        val registry = ServiceRegistry()
        registry.registerDispatcher(INavService::class.qualifiedName!!, navDispatcher())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )
        val batchExecutor = BatchExecutor(router)

        val batch = BatchRequest()
        batch.add(IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation",
            methodId = NAV_GET_LOCATION, argsBundle = Bundle()))
        batch.add(IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "calculateRoute",
            methodId = NAV_CALCULATE_ROUTE, argsBundle = Bundle().apply { putString("dest", "airport") }))

        val response = batchExecutor.execute(batch, "test")
        assertEquals(2, response.responses.size)
        assertFalse(response.responses[0].isError)
    }

    @Test
    fun `monitor integration`() {
        val monitor = MonitorFacade().apply { setLevel(MonitorLevel.DETAILED) }
        val registry = ServiceRegistry()
        registry.registerDispatcher(INavService::class.qualifiedName!!, navDispatcher())

        val router = MessageRouter(registry, monitor, PermissionChecker(emptyMap()), RateLimiter())

        repeat(10) {
            router.handleLocal(
                IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation",
                    methodId = NAV_GET_LOCATION, argsBundle = Bundle()),
                "test", 0
            )
        }

        val stats = monitor.getStats()
        assertTrue(stats.isNotEmpty())
        assertEquals(10L, stats.first().callCount)
    }
}
