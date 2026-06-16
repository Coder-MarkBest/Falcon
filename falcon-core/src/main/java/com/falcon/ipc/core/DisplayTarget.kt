package com.falcon.ipc.core

enum class DisplayTarget {
    MAIN,     // Center console / 中控
    CLUSTER,  // Instrument cluster / 仪表
    HUD,      // Head-up display
    REAR,     // Rear entertainment
    ANY       // No specific display
}

class DisplayRouter {
    private val displayServices = mutableMapOf<String, MutableSet<DisplayTarget>>()

    fun registerServiceDisplay(serviceKey: String, target: DisplayTarget) {
        displayServices.getOrPut(serviceKey) { mutableSetOf() }.add(target)
    }

    fun getDisplaysForService(serviceKey: String): Set<DisplayTarget> {
        return displayServices[serviceKey] ?: setOf(DisplayTarget.ANY)
    }

    fun getServicesForDisplay(target: DisplayTarget): List<String> {
        return displayServices.filter { (_, displays) ->
            displays.contains(target) || displays.contains(DisplayTarget.ANY)
        }.keys.toList()
    }

    fun isServiceAvailableOnDisplay(serviceKey: String, target: DisplayTarget): Boolean {
        val displays = getDisplaysForService(serviceKey)
        return displays.contains(target) || displays.contains(DisplayTarget.ANY)
    }
}
