package com.falcon.ipc.integration

import com.falcon.ipc.core.*
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.TransportResult
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EndToEndPipelineTest {

    interface IGreetingService : IpcService {
        fun greet(name: String): String
        fun add(a: Int, b: Int): Int
    }

    class GreetingServiceImpl : IGreetingService {
        override fun greet(name: String): String = "Hello, $name!"
        override fun add(a: Int, b: Int): Int = a + b
    }

    // Simulates a local transport that calls the stub directly
    class LocalTransport(private val stub: (IpcEnvelope) -> IpcEnvelope) : IpcTransport {
        override val maxPayloadSize: Int = 64 * 1024
        override fun invoke(envelope: IpcEnvelope): TransportResult {
            val response = stub(envelope)
            return if (response.isError) {
                TransportResult.Error(response.errorCode, response.errorMessage)
            } else {
                TransportResult.Success(response.args)
            }
        }
    }

    @Test
    fun `end-to-end - proxy calls stub through transport`() {
        // Server side: set up registry and router
        val registry = ServiceRegistry()
        val impl = GreetingServiceImpl()
        registry.register(IGreetingService::class, impl)

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )

        // Create local transport (simulates Binder)
        val transport = LocalTransport { envelope ->
            try {
                val result = router.handleLocal(envelope, "test")
                IpcEnvelope.response(envelope.requestId, IpcSerializer.serializeResult(result))
            } catch (e: Exception) {
                IpcEnvelope.error(-1, e.message ?: "Error", envelope.requestId)
            }
        }

        // Client side: create proxy
        val proxy = ProxyFactory.create(
            IGreetingService::class.java,
            IGreetingService::class.qualifiedName!!,
            transport
        )

        // Call through proxy — this tests the full pipeline
        val greeting = proxy.greet("Falcon")
        assertEquals("Hello, Falcon!", greeting)

        val sum = proxy.add(3, 5)
        assertEquals(8, sum)
    }

    @Test
    fun `end-to-end - proxy handles complex return types`() {
        val registry = ServiceRegistry()
        registry.register(IGreetingService::class, GreetingServiceImpl())

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )

        val transport = LocalTransport { envelope ->
            try {
                val result = router.handleLocal(envelope, "test")
                IpcEnvelope.response(envelope.requestId, IpcSerializer.serializeResult(result))
            } catch (e: Exception) {
                IpcEnvelope.error(-1, e.message ?: "Error", envelope.requestId)
            }
        }

        val proxy = ProxyFactory.create(
            IGreetingService::class.java,
            IGreetingService::class.qualifiedName!!,
            transport
        )

        // Test string with special characters
        assertEquals("Hello, 你好世界!", proxy.greet("你好世界"))

        // Test negative numbers
        assertEquals(-3, proxy.add(-1, -2))

        // Test zero
        assertEquals(0, proxy.add(0, 0))
    }

    @Test
    fun `end-to-end - error propagation`() {
        val registry = ServiceRegistry()
        // Don't register any service

        val router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )

        val transport = LocalTransport { envelope ->
            try {
                val result = router.handleLocal(envelope, "test")
                IpcEnvelope.response(envelope.requestId, IpcSerializer.serializeResult(result))
            } catch (e: Exception) {
                IpcEnvelope.error(-1, e.message ?: "Error", envelope.requestId)
            }
        }

        val proxy = ProxyFactory.create(
            IGreetingService::class.java,
            IGreetingService::class.qualifiedName!!,
            transport
        )

        try {
            proxy.greet("test")
            fail("Should throw")
        } catch (e: RuntimeException) {
            assertTrue(e.message?.contains("IPC error") == true)
        }
    }
}
