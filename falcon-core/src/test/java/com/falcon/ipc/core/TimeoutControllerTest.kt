package com.falcon.ipc.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimeoutControllerTest {

    private lateinit var controller: TimeoutController

    @Before
    fun setup() {
        controller = TimeoutController()
    }

    @Test
    fun `returns result when within timeout`() = runTest {
        val result = controller.withSimpleTimeout("req-1", 5000) {
            delay(100)
            "done"
        }
        assertEquals("done", result)
    }

    @Test
    fun `returns null on timeout`() = runTest {
        val result = controller.withSimpleTimeout("req-2", 100) {
            delay(10_000)
            "should not reach"
        }
        assertNull(result)
        assertEquals(1, controller.getTimeoutCount())
    }

    @Test
    fun `cancel stops pending call`() = runTest {
        val job = async {
            controller.withSimpleTimeout("req-3", 30_000) {
                delay(60_000)
                "should not reach"
            }
        }

        delay(100)
        val cancelled = controller.cancel("req-3")
        assertTrue(cancelled)
        job.cancel()
    }

    @Test
    fun `cancel returns false for unknown requestId`() {
        assertFalse(controller.cancel("nonexistent"))
    }

    @Test
    fun `pending count tracks active calls`() = runTest {
        assertEquals(0, controller.getPendingCount())
    }
}
