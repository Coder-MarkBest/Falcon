package com.falcon.ipc.integration

import com.falcon.ipc.core.*
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration test simulating two services in the same process.
 * Real cross-process tests require instrumented tests with Android emulator.
 */
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
            "test"
        )
        assertEquals("Beijing, 39.9, 116.4", navResult)

        val mediaResult = router.handleLocal(
            IpcEnvelope(serviceKey = IMediaService::class.qualifiedName!!, method = "getVolume"),
            "test"
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
                "test"
            )
        }
        val elapsed = System.currentTimeMillis() - start
        assertTrue("1000 calls should complete in < 5s, took ${elapsed}ms", elapsed < 5000)
    }

    @Test
    fun `circuit breaker integration`() {
        val cb = CircuitBreaker(CircuitBreakerConfig(failureThreshold = 3, openDurationMs = 100))

        // Simulate failures
        repeat(3) { cb.recordFailure("svc") }
        assertFalse(cb.allowCall("svc"))

        // After open duration, should allow
        Thread.sleep(150)
        assertTrue(cb.allowCall("svc"))
    }

    @Test
    fun `vehicle state integration`() {
        val manager = VehicleStateManager()

        manager.registerServicePriority("brakes", ServicePriority.CRITICAL)
        manager.registerServicePriority("music", ServicePriority.OPTIONAL)

        // Engine off — only critical
        manager.setVehicleState(VehicleState.OFF)
        assertTrue(manager.isServiceActive("brakes"))
        assertFalse(manager.isServiceActive("music"))

        // Engine on — all active
        manager.setVehicleState(VehicleState.RUNNING)
        assertTrue(manager.isServiceActive("brakes"))
        assertTrue(manager.isServiceActive("music"))
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
        batch.add(IpcEnvelope(serviceKey = INavService::class.qualifiedName!!, method = "calculateRoute", args = "airport".toByteArray()))

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
                "test"
            )
        }

        val stats = monitor.getStats()
        assertTrue(stats.isNotEmpty())
        assertEquals(10L, stats.first().callCount)
    }
}
