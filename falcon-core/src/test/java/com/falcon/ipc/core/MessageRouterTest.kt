package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.runtime.IpcDispatcher
import com.falcon.ipc.security.AccessRule
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageRouterTest {

    interface ICalcService : IpcService {
        fun add(a: Int, b: Int): Int
    }

    interface EchoService : IpcService {
        fun echo(s: String): String
    }

    class EchoServiceImpl : EchoService {
        override fun echo(s: String) = s
    }

    private lateinit var registry: ServiceRegistry
    private lateinit var router: MessageRouter

    private fun fakeDispatcher(prefix: String = "ok") = object : IpcDispatcher {
        override fun dispatch(methodId: Int, args: Bundle) =
            Bundle().apply { putString("r", "$prefix:$methodId") }
    }

    @Before
    fun setup() {
        registry = ServiceRegistry()

        // Register dispatcher for ICalcService (methodId=1 → add)
        val calcKey = ICalcService::class.qualifiedName!!
        registry.registerDispatcher(calcKey, object : IpcDispatcher {
            override fun dispatch(methodId: Int, args: Bundle): Bundle {
                val a = args.getInt("a", 0)
                val b = args.getInt("b", 0)
                return Bundle().apply { putInt("result", a + b) }
            }
        })

        val monitor = MonitorFacade().apply { setLevel(MonitorLevel.NONE) }
        val permissionChecker = PermissionChecker(emptyMap())
        val rateLimiter = RateLimiter()

        router = MessageRouter(registry, monitor, permissionChecker, rateLimiter)
    }

    @Test
    fun `routes to local service via dispatcher`() {
        val argsBundle = Bundle().apply { putInt("a", 3); putInt("b", 5) }
        val envelope = IpcEnvelope(
            serviceKey = ICalcService::class.qualifiedName!!,
            method = "add",
            methodId = 1,
            argsBundle = argsBundle
        )

        val result = router.handleLocal(envelope, "com.test", 1000) as Bundle
        assertEquals(8, result.getInt("result"))
    }

    @Test
    fun `returns error for unknown service`() {
        val envelope = IpcEnvelope(
            serviceKey = "com.nonexistent.IService",
            method = "doWork",
            methodId = 1,
            argsBundle = Bundle()
        )

        try {
            router.handleLocal(envelope, "com.test", 1000)
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not found") == true)
        }
    }

    @Test
    fun `checks permission before routing`() {
        val calcKey = ICalcService::class.qualifiedName!!
        val denyChecker = PermissionChecker(
            mapOf(calcKey to AccessRule(allowList = setOf(":allowed"), denyList = emptySet()))
        )
        val restrictedRouter = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            denyChecker,
            RateLimiter()
        )

        val envelope = IpcEnvelope(
            serviceKey = calcKey,
            method = "add",
            methodId = 1,
            argsBundle = Bundle().apply { putInt("a", 1); putInt("b", 2) }
        )

        try {
            restrictedRouter.handleLocal(envelope, ":blocked", 1000)
            fail("Should throw permission denied")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Permission") == true)
        }
    }

    @Test
    fun `check_service returns true for registered and false otherwise`() {
        val registry = ServiceRegistry()
        registry.register(EchoService::class, EchoServiceImpl())
        val router = MessageRouter(registry, MonitorFacade(), PermissionChecker(emptyMap()),
            RateLimiter(clock = { 0L }))
        val key = EchoService::class.qualifiedName!!
        val present = router.handleLocal(
            IpcEnvelope(serviceKey = "", method = "__check_service__", argsBundle = Bundle().apply { putString("key", key) }),
            "proc", 1234) as Bundle
        // 0 = accessible, 1 = not found, 2 = permission denied
        assertEquals(0, present.getInt("r"))
        val absent = router.handleLocal(
            IpcEnvelope(serviceKey = "", method = "__check_service__", argsBundle = Bundle().apply { putString("key", "no.such.Svc") }),
            "proc", 1234) as Bundle
        assertEquals(1, absent.getInt("r"))
    }

    @Test
    fun `check_service returns false when caller lacks permission`() {
        val registry = ServiceRegistry()
        registry.register(EchoService::class, EchoServiceImpl())
        val key = EchoService::class.qualifiedName!!
        // allowList excludes "intruder" -> denied
        val checker = PermissionChecker(mapOf(key to AccessRule(allowList = setOf("trusted"))))
        val router = MessageRouter(registry, MonitorFacade(), checker, RateLimiter(clock = { 0L }))
        val result = router.handleLocal(
            IpcEnvelope(serviceKey = "", method = "__check_service__", argsBundle = Bundle().apply { putString("key", key) }),
            "intruder", 1234) as Bundle
        assertEquals(2, result.getInt("r")) // 2 = permission denied, 0 = accessible, 1 = not found
    }

    @Test
    fun `check_service schema match returns accessible, mismatch returns 3`() {
        val registry = ServiceRegistry()
        registry.register(EchoService::class, EchoServiceImpl())
        registry.registerSchema(EchoService::class.qualifiedName!!, 12345)
        val router = MessageRouter(registry, MonitorFacade(), PermissionChecker(emptyMap()),
            RateLimiter(clock = { 0L }))
        val key = EchoService::class.qualifiedName!!

        fun check(clientSchema: Int) = (router.handleLocal(
            IpcEnvelope(serviceKey = "", method = "__check_service__",
                argsBundle = Bundle().apply { putString("key", key); putInt("schema", clientSchema) }),
            "proc", 1234) as Bundle).getInt("r")

        assertEquals(0, check(12345))   // match → accessible
        assertEquals(3, check(99999))   // mismatch → schema mismatch
        assertEquals(0, check(0))       // client unknown → skip check (back-compat)
    }

    @Test
    fun `check_service skips schema check when server schema unknown`() {
        val registry = ServiceRegistry()
        registry.register(EchoService::class, EchoServiceImpl())   // no registerSchema → server schema 0
        val router = MessageRouter(registry, MonitorFacade(), PermissionChecker(emptyMap()),
            RateLimiter(clock = { 0L }))
        val key = EchoService::class.qualifiedName!!
        val r = (router.handleLocal(
            IpcEnvelope(serviceKey = "", method = "__check_service__",
                argsBundle = Bundle().apply { putString("key", key); putInt("schema", 777) }),
            "proc", 1234) as Bundle).getInt("r")
        assertEquals(0, r)   // server schema 0 → no rejection
    }

    @Test
    fun `rate limit denial throws`() {
        val echoKey = EchoService::class.qualifiedName!!
        val registry = ServiceRegistry()
        registry.registerDispatcher(echoKey, fakeDispatcher())
        val router = MessageRouter(registry, MonitorFacade(), PermissionChecker(emptyMap()),
            RateLimiter(maxCallsPerSecond = 1, clock = { 0L }))
        fun call() = router.handleLocal(
            IpcEnvelope(serviceKey = echoKey, method = "echo", methodId = 1, argsBundle = Bundle()),
            "proc", 1234)
        call() // first allowed
        assertThrows(IllegalStateException::class.java) { call() } // second over limit
    }
}
