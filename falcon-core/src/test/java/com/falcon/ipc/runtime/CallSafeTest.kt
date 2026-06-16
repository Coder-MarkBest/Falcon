package com.falcon.ipc.runtime

import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.protocol.IpcResult
import com.falcon.ipc.service.IpcService
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallSafeTest {

    interface ICalcService : IpcService {
        fun add(a: Int, b: Int): Int
        suspend fun slowOp(): String
    }

    class CalcServiceImpl : ICalcService {
        override fun add(a: Int, b: Int): Int = a + b
        override suspend fun slowOp(): String {
            delay(10_000) // Simulate slow operation
            return "done"
        }
    }

    @Test
    fun `callSafe returns Success on normal call`() = runTest {
        val manager = mock<FalconManager>()
        val impl = CalcServiceImpl()
        whenever(manager.getService(ICalcService::class)).thenReturn(impl)

        val result = manager.callSafe<ICalcService, Int> { it.add(3, 5) }

        assertTrue(result is IpcResult.Success)
        assertEquals(8, (result as IpcResult.Success).data)
    }

    @Test
    fun `callSafe returns ServiceUnavailable when service not found`() = runTest {
        val manager = mock<FalconManager>()
        whenever(manager.getService(ICalcService::class)).thenReturn(null)

        val result = manager.callSafe<ICalcService, Int> { it.add(3, 5) }

        assertTrue(result is IpcResult.ServiceUnavailable)
    }

    @Test
    fun `callSafe returns Timeout on slow call`() = runTest {
        val manager = mock<FalconManager>()
        val impl = CalcServiceImpl()
        whenever(manager.getService(ICalcService::class)).thenReturn(impl)

        val result = manager.callSafe<ICalcService, String>(timeoutMs = 100) { it.slowOp() }

        assertTrue(result is IpcResult.Timeout)
    }

    @Test
    fun `callSafe returns Failure on exception`() = runTest {
        val manager = mock<FalconManager>()
        whenever(manager.getService(ICalcService::class)).thenReturn(
            object : ICalcService {
                override fun add(a: Int, b: Int): Int = throw RuntimeException("boom")
                override suspend fun slowOp(): String = throw RuntimeException("boom")
            }
        )

        val result = manager.callSafe<ICalcService, Int> { it.add(1, 2) }

        assertTrue(result is IpcResult.Failure)
        assertEquals("boom", (result as IpcResult.Failure).message)
    }

    @Test
    fun `callSafeOrNull returns null when service not found`() {
        val manager = mock<FalconManager>()
        whenever(manager.getService(ICalcService::class)).thenReturn(null)

        val result = manager.callSafeOrNull<ICalcService, Int> { it.add(1, 2) }
        assertNull(result)
    }
}
