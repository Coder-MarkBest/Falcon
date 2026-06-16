package com.falcon.ipc.runtime

import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.KProperty

class IpcServiceDelegateTest {

    interface ITestService : IpcService {
        fun getValue(): String
    }

    class TestServiceImpl : ITestService {
        override fun getValue(): String = "real"
    }

    class FallbackImpl : ITestService {
        override fun getValue(): String = "fallback"
    }

    // Dummy property reference for testing (KProperty parameter is unused by the delegate)
    private val dummyProp: String = ""

    @Test
    fun `returns fallback when Falcon not initialized`() {
        val fallback = FallbackImpl()
        val delegate = IpcServiceDelegate(ITestService::class, fallback)

        val result = delegate.getValue(this, ::dummyProp)
        assertEquals("fallback", result.getValue())
    }

    @Test
    fun `throws when no fallback and Falcon not initialized`() {
        val delegate = IpcServiceDelegate(ITestService::class, null)

        try {
            delegate.getValue(this, ::dummyProp)
            fail("Should throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Falcon") == true)
        }
    }
}
