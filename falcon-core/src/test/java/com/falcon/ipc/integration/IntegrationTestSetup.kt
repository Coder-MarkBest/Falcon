package com.falcon.ipc.integration

import com.falcon.ipc.core.*
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
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

    class NavServiceImpl : INavService {
        override fun getLocation(): String = "Beijing, 39.9, 116.4"
        override fun calculateRoute(dest: String): Int = 42
    }

    class MediaServiceImpl : IMediaService {
        override fun playSong(name: String): Boolean = true
        override fun getVolume(): Int = 50
    }

    @Test
    fun `end-to-end service registration and call`() {
        val registry = ServiceRegistry()
        registry.register(INavService::class, NavServiceImpl())
        registry.register(IMediaService::class, MediaServiceImpl())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )

        // Simulate IPC call
        val navResult = router.handleLocal(
            IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation"),
            "test", 0
        )
        assertEquals("Beijing, 39.9, 116.4", navResult)

        val mediaResult = router.handleLocal(
            IpcEnvelope(serviceKey = IMediaService::class.qualifiedName!!, method = "getVolume"),
            "test", 0
        )
        assertEquals(50, mediaResult)
    }

    @Test
    fun `stress test - 1000 calls`() {
        val registry = ServiceRegistry()
        registry.register(INavService::class, NavServiceImpl())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter(maxCallsPerSecond = 10000)
        )

        val start = System.currentTimeMillis()
        repeat(1000) {
            router.handleLocal(
                IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation"),
                "test", 0
            )
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000 calls should complete in < 5s, took ${elapsed}ms", elapsed < 5000)
    }

    @Test
    fun `batch execution integration`() {
        val registry = ServiceRegistry()
        registry.register(INavService::class, NavServiceImpl())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )
        val batchExecutor = BatchExecutor(router)

        val batch = BatchRequest()
        batch.add(IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation"))
        batch.add(IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "calculateRoute", args = IpcSerializer.serializeArgs(arrayOf("airport"))))

        val response = batchExecutor.execute(batch, "test")
        assertEquals(2, response.responses.size)
        assertFalse(response.responses[0].isError)
    }

    @Test
    fun `monitor integration`() {
        val monitor = MonitorFacade().apply { setLevel(MonitorLevel.DETAILED) }
        val registry = ServiceRegistry()
        registry.register(INavService::class, NavServiceImpl())

        val router = MessageRouter(registry, monitor, PermissionChecker(emptyMap()), RateLimiter())

        repeat(10) {
            router.handleLocal(
                IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "getLocation"),
                "test", 0
            )
        }

        val stats = monitor.getStats()
        assertTrue(stats.isNotEmpty())
        assertEquals(10L, stats.first().callCount)
    }
}
