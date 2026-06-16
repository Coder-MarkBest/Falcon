package com.falcon.ipc.core

import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.security.AccessRule
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MessageRouterTest {

    interface ICalcService : IpcService {
        fun add(a: Int, b: Int): Int
    }

    class CalcServiceImpl : ICalcService {
        override fun add(a: Int, b: Int): Int = a + b
    }

    private lateinit var registry: ServiceRegistry
    private lateinit var router: MessageRouter

    @Before
    fun setup() {
        registry = ServiceRegistry()
        registry.register(ICalcService::class, CalcServiceImpl())

        val monitor = MonitorFacade().apply { setLevel(MonitorLevel.NONE) }
        val permissionChecker = PermissionChecker(emptyMap())
        val rateLimiter = RateLimiter()

        router = MessageRouter(registry, monitor, permissionChecker, rateLimiter)
    }

    @Test
    fun `routes to local service`() {
        val envelope = IpcEnvelope(
            serviceKey = ICalcService::class.qualifiedName!!,
            method = "add",
            args = "3,5".toByteArray()
        )

        val result = router.handleLocal(envelope, "com.test")
        assertEquals(8, result)
    }

    @Test
    fun `returns error for unknown service`() {
        val envelope = IpcEnvelope(
            serviceKey = "com.nonexistent.IService",
            method = "doWork"
        )

        try {
            router.handleLocal(envelope, "com.test")
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not found") == true)
        }
    }

    @Test
    fun `checks permission before routing`() {
        val denyChecker = PermissionChecker(
            mapOf(ICalcService::class.qualifiedName!! to
                AccessRule(allowList = setOf(":allowed"), denyList = emptySet()))
        )
        val restrictedRouter = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            denyChecker,
            RateLimiter()
        )

        val envelope = IpcEnvelope(
            serviceKey = ICalcService::class.qualifiedName!!,
            method = "add",
            args = "1,2".toByteArray()
        )

        try {
            restrictedRouter.handleLocal(envelope, ":blocked")
            fail("Should throw permission denied")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Permission") == true)
        }
    }
}
