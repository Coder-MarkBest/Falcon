package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HotUpdateManagerTest {

    private lateinit var versionRegistry: ServiceVersionRegistry
    private lateinit var hotUpdate: HotUpdateManager

    @Before
    fun setup() {
        versionRegistry = ServiceVersionRegistry()
        hotUpdate = HotUpdateManager(versionRegistry)
    }

    @Test
    fun `updates service version`() {
        versionRegistry.register("svc", ServiceVersion(1, 0))
        hotUpdate.registerUpdatedService("svc", ServiceVersion(1, 1))
        assertEquals(ServiceVersion(1, 1), versionRegistry.getVersion("svc"))
    }

    @Test
    fun `ignores older version`() {
        versionRegistry.register("svc", ServiceVersion(1, 2))
        hotUpdate.registerUpdatedService("svc", ServiceVersion(1, 0))
        assertEquals(ServiceVersion(1, 2), versionRegistry.getVersion("svc"))
    }

    @Test
    fun `update callback fires on upgrade`() {
        var updatedService: String? = null
        hotUpdate.onUpdate { key, _ -> updatedService = key }

        versionRegistry.register("svc", ServiceVersion(1, 0))
        hotUpdate.registerUpdatedService("svc", ServiceVersion(1, 1))

        assertEquals("svc", updatedService)
    }

    @Test
    fun `update callback does not fire on same version`() {
        var callCount = 0
        hotUpdate.onUpdate { _, _ -> callCount++ }

        versionRegistry.register("svc", ServiceVersion(1, 0))
        hotUpdate.registerUpdatedService("svc", ServiceVersion(1, 0))

        assertEquals(0, callCount)
    }
}
