package com.falcon.ipc.core

import com.falcon.ipc.service.IpcService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServiceRegistryTest {

    interface ITestService : IpcService {
        fun doWork(): String
    }

    class TestServiceImpl : ITestService {
        override fun doWork(): String = "done"
    }

    private lateinit var registry: ServiceRegistry

    @Before
    fun setup() {
        registry = ServiceRegistry()
    }

    @Test
    fun `register and retrieve service`() {
        val impl = TestServiceImpl()
        registry.register(ITestService::class, impl)

        val retrieved = registry.getService(ITestService::class.qualifiedName!!)
        assertNotNull(retrieved)
        assertEquals(impl, retrieved)
    }

    @Test
    fun `getService returns null for unregistered`() {
        assertNull(registry.getService("com.nonexistent.Service"))
    }

    @Test
    fun `getAllServices returns all registered`() {
        registry.register(ITestService::class, TestServiceImpl())
        assertEquals(1, registry.getAllServices().size)
    }

    @Test
    fun `unregisterAll clears registry`() {
        registry.register(ITestService::class, TestServiceImpl())
        assertEquals(1, registry.getAllServices().size)

        registry.unregisterAll()
        assertEquals(0, registry.getAllServices().size)
    }
}
