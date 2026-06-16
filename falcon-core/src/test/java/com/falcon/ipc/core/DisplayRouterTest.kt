package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DisplayRouterTest {

    private lateinit var router: DisplayRouter

    @Before
    fun setup() {
        router = DisplayRouter()
    }

    @Test
    fun `unregistered service defaults to ANY`() {
        val displays = router.getDisplaysForService("unknown")
        assertTrue(displays.contains(DisplayTarget.ANY))
    }

    @Test
    fun `register service to specific display`() {
        router.registerServiceDisplay("nav", DisplayTarget.CLUSTER)
        router.registerServiceDisplay("nav", DisplayTarget.HUD)

        val displays = router.getDisplaysForService("nav")
        assertTrue(displays.contains(DisplayTarget.CLUSTER))
        assertTrue(displays.contains(DisplayTarget.HUD))
        assertFalse(displays.contains(DisplayTarget.MAIN))
    }

    @Test
    fun `get services for display`() {
        router.registerServiceDisplay("nav", DisplayTarget.CLUSTER)
        router.registerServiceDisplay("speed", DisplayTarget.CLUSTER)
        router.registerServiceDisplay("media", DisplayTarget.MAIN)

        val clusterServices = router.getServicesForDisplay(DisplayTarget.CLUSTER)
        assertTrue(clusterServices.contains("nav"))
        assertTrue(clusterServices.contains("speed"))
        assertFalse(clusterServices.contains("media"))
    }

    @Test
    fun `ANY display matches all targets`() {
        router.registerServiceDisplay("status", DisplayTarget.ANY)

        assertTrue(router.isServiceAvailableOnDisplay("status", DisplayTarget.MAIN))
        assertTrue(router.isServiceAvailableOnDisplay("status", DisplayTarget.CLUSTER))
        assertTrue(router.isServiceAvailableOnDisplay("status", DisplayTarget.HUD))
    }

    @Test
    fun `specific display only matches its target`() {
        router.registerServiceDisplay("nav", DisplayTarget.CLUSTER)

        assertTrue(router.isServiceAvailableOnDisplay("nav", DisplayTarget.CLUSTER))
        assertFalse(router.isServiceAvailableOnDisplay("nav", DisplayTarget.MAIN))
    }
}
