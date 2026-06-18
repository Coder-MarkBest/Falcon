package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.runtime.IpcDispatcher
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchRequestTest {

    interface IMathService : IpcService {
        fun add(a: Int, b: Int): Int
        fun multiply(a: Int, b: Int): Int
    }

    // Method IDs
    private val MATH_ADD = 1
    private val MATH_MULTIPLY = 2

    private lateinit var router: MessageRouter
    private lateinit var batchExecutor: BatchExecutor

    @Before
    fun setup() {
        val registry = ServiceRegistry()
        val mathKey = IMathService::class.qualifiedName!!
        registry.registerDispatcher(mathKey, object : IpcDispatcher {
            override fun dispatch(methodId: Int, args: Bundle): Bundle = when (methodId) {
                MATH_ADD -> {
                    val a = args.getInt("a", 0)
                    val b = args.getInt("b", 0)
                    Bundle().apply { putInt("result", a + b) }
                }
                MATH_MULTIPLY -> {
                    val a = args.getInt("a", 0)
                    val b = args.getInt("b", 0)
                    Bundle().apply { putInt("result", a * b) }
                }
                else -> throw IllegalArgumentException("Unknown methodId=$methodId")
            }
        })
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
        val mathKey = IMathService::class.qualifiedName!!
        val batch = BatchRequest()
        batch.add(IpcEnvelope(
            serviceKey = mathKey,
            method = "add",
            methodId = MATH_ADD,
            argsBundle = Bundle().apply { putInt("a", 3); putInt("b", 5) }
        ))
        batch.add(IpcEnvelope(
            serviceKey = mathKey,
            method = "multiply",
            methodId = MATH_MULTIPLY,
            argsBundle = Bundle().apply { putInt("a", 4); putInt("b", 6) }
        ))

        val response = batchExecutor.execute(batch, "test")
        assertEquals(2, response.responses.size)
        assertFalse(response.responses[0].isError)
        assertFalse(response.responses[1].isError)
    }

    @Test
    fun `batch handles individual errors`() {
        val mathKey = IMathService::class.qualifiedName!!
        val batch = BatchRequest()
        batch.add(IpcEnvelope(
            serviceKey = mathKey,
            method = "add",
            methodId = MATH_ADD,
            argsBundle = Bundle().apply { putInt("a", 1); putInt("b", 2) }
        ))
        batch.add(IpcEnvelope(
            serviceKey = "com.nonexistent.Service",
            method = "missing",
            methodId = 1,
            argsBundle = Bundle()
        ))

        val response = batchExecutor.execute(batch, "test")
        assertEquals(2, response.responses.size)
        assertFalse(response.responses[0].isError)
        assertTrue(response.responses[1].isError)
    }

    @Test
    fun `batch tracks execution time`() {
        val mathKey = IMathService::class.qualifiedName!!
        val batch = BatchRequest()
        batch.add(IpcEnvelope(
            serviceKey = mathKey,
            method = "add",
            methodId = MATH_ADD,
            argsBundle = Bundle().apply { putInt("a", 1); putInt("b", 1) }
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
