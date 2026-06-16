package com.falcon.ipc.core

import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatchRequestTest {

    interface IMathService : IpcService {
        fun add(a: Int, b: Int): Int
        fun multiply(a: Int, b: Int): Int
    }

    class MathServiceImpl : IMathService {
        override fun add(a: Int, b: Int): Int = a + b
        override fun multiply(a: Int, b: Int): Int = a * b
    }

    private lateinit var router: MessageRouter
    private lateinit var batchExecutor: BatchExecutor

    @Before
    fun setup() {
        val registry = ServiceRegistry()
        registry.register(IMathService::class, MathServiceImpl())
        router = MessageRouter(
            registry,
            MonitorFacade().apply { setLevel(MonitorLevel.NONE) },
            PermissionChecker(emptyMap()),
            RateLimiter()
        )
        batchExecutor = BatchExecutor(router)
    }

    @Test
    fun `batch executes multiple requests`() {
        val batch = BatchRequest()
        batch.add(IpcEnvelope(
            serviceKey = IMathService::class.qualifiedName!!,
            method = "add",
            args = "3,5".toByteArray()
        ))
        batch.add(IpcEnvelope(
            serviceKey = IMathService::class.qualifiedName!!,
            method = "multiply",
            args = "4,6".toByteArray()
        ))

        val response = batchExecutor.execute(batch, "test")
        assertEquals(2, response.responses.size)
        assertFalse(response.responses[0].isError)
        assertFalse(response.responses[1].isError)
    }

    @Test
    fun `batch handles individual errors`() {
        val batch = BatchRequest()
        batch.add(IpcEnvelope(
            serviceKey = IMathService::class.qualifiedName!!,
            method = "add",
            args = "1,2".toByteArray()
        ))
        batch.add(IpcEnvelope(
            serviceKey = "com.nonexistent.Service",
            method = "missing"
        ))

        val response = batchExecutor.execute(batch, "test")
        assertEquals(2, response.responses.size)
        assertFalse(response.responses[0].isError)
        assertTrue(response.responses[1].isError)
    }

    @Test
    fun `batch tracks execution time`() {
        val batch = BatchRequest()
        batch.add(IpcEnvelope(
            serviceKey = IMathService::class.qualifiedName!!,
            method = "add",
            args = "1,1".toByteArray()
        ))

        val response = batchExecutor.execute(batch, "test")
        assertTrue(response.totalMs >= 0)
    }

    @Test
    fun `empty batch returns empty responses`() {
        val batch = BatchRequest()
        val response = batchExecutor.execute(batch, "test")
        assertEquals(0, response.responses.size)
    }
}
