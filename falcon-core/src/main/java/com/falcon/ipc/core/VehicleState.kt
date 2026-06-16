package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VehicleState {
    OFF,          // Engine off, minimal power
    ACC,          // Accessory mode
    RUNNING,      // Engine running, full power
    LOW_POWER     // Battery low, reduce non-essential IPC
}

enum class ServicePriority {
    CRITICAL,     // Always active (brakes, airbag, steering)
    IMPORTANT,    // Active in ACC and above
    NORMAL,       // Active when RUNNING
    OPTIONAL      // Only when RUNNING and not LOW_POWER
}

class VehicleStateManager {
    private val _state = MutableStateFlow(VehicleState.OFF)
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val servicePriorities = mutableMapOf<String, ServicePriority>()

    fun setVehicleState(newState: VehicleState) {
        val old = _state.value
        _state.value = newState
        FalconLogger.i("Vehicle", "State: $old → $newState")
    }

    fun registerServicePriority(serviceKey: String, priority: ServicePriority) {
        servicePriorities[serviceKey] = priority
    }

    fun isServiceActive(serviceKey: String): Boolean {
        val priority = servicePriorities[serviceKey] ?: ServicePriority.NORMAL
        return when (_state.value) {
            VehicleState.OFF -> priority == ServicePriority.CRITICAL
            VehicleState.ACC -> priority == ServicePriority.CRITICAL ||
                                  priority == ServicePriority.IMPORTANT
            VehicleState.RUNNING -> true
            VehicleState.LOW_POWER -> priority == ServicePriority.CRITICAL ||
                                      priority == ServicePriority.IMPORTANT
        }
    }

    fun getActiveServiceCount(): Int {
        return servicePriorities.count { isServiceActive(it.key) }
    }
}
