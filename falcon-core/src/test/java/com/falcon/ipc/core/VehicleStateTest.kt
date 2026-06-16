package com.falcon.ipc.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VehicleStateTest {

    private lateinit var manager: VehicleStateManager

    @Before
    fun setup() {
        manager = VehicleStateManager()
    }

    @Test
    fun `initial state is OFF`() {
        assertEquals(VehicleState.OFF, manager.state.value)
    }

    @Test
    fun `setVehicleState updates state`() {
        manager.setVehicleState(VehicleState.RUNNING)
        assertEquals(VehicleState.RUNNING, manager.state.value)
    }

    @Test
    fun `state is observable as Flow`() = runTest {
        manager.setVehicleState(VehicleState.ACC)
        assertEquals(VehicleState.ACC, manager.state.first())
    }

    @Test
    fun `OFF state only allows CRITICAL services`() {
        manager.registerServicePriority("brakes", ServicePriority.CRITICAL)
        manager.registerServicePriority("media", ServicePriority.NORMAL)
        manager.registerServicePriority("nav", ServicePriority.IMPORTANT)

        manager.setVehicleState(VehicleState.OFF)

        assertTrue(manager.isServiceActive("brakes"))
        assertFalse(manager.isServiceActive("media"))
        assertFalse(manager.isServiceActive("nav"))
    }

    @Test
    fun `ACC state allows CRITICAL and IMPORTANT`() {
        manager.registerServicePriority("brakes", ServicePriority.CRITICAL)
        manager.registerServicePriority("nav", ServicePriority.IMPORTANT)
        manager.registerServicePriority("media", ServicePriority.NORMAL)

        manager.setVehicleState(VehicleState.ACC)

        assertTrue(manager.isServiceActive("brakes"))
        assertTrue(manager.isServiceActive("nav"))
        assertFalse(manager.isServiceActive("media"))
    }

    @Test
    fun `RUNNING state allows all services`() {
        manager.registerServicePriority("brakes", ServicePriority.CRITICAL)
        manager.registerServicePriority("media", ServicePriority.NORMAL)
        manager.registerServicePriority("diag", ServicePriority.OPTIONAL)

        manager.setVehicleState(VehicleState.RUNNING)

        assertTrue(manager.isServiceActive("brakes"))
        assertTrue(manager.isServiceActive("media"))
        assertTrue(manager.isServiceActive("diag"))
    }

    @Test
    fun `LOW_POWER blocks OPTIONAL services`() {
        manager.registerServicePriority("brakes", ServicePriority.CRITICAL)
        manager.registerServicePriority("media", ServicePriority.NORMAL)
        manager.registerServicePriority("diag", ServicePriority.OPTIONAL)

        manager.setVehicleState(VehicleState.LOW_POWER)

        assertTrue(manager.isServiceActive("brakes"))
        assertFalse(manager.isServiceActive("media"))
        assertFalse(manager.isServiceActive("diag"))
    }

    @Test
    fun `unregistered services default to NORMAL priority`() {
        manager.setVehicleState(VehicleState.RUNNING)
        assertTrue(manager.isServiceActive("unknown"))

        manager.setVehicleState(VehicleState.OFF)
        assertFalse(manager.isServiceActive("unknown"))
    }

    @Test
    fun `getActiveServiceCount reflects current state`() {
        manager.registerServicePriority("a", ServicePriority.CRITICAL)
        manager.registerServicePriority("b", ServicePriority.NORMAL)
        manager.registerServicePriority("c", ServicePriority.OPTIONAL)

        manager.setVehicleState(VehicleState.OFF)
        assertEquals(1, manager.getActiveServiceCount())

        manager.setVehicleState(VehicleState.RUNNING)
        assertEquals(3, manager.getActiveServiceCount())
    }
}
