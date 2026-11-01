package com.falcon.agent

import com.falcon.capability.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChargingPolicyTest {
    private val ref = "demo:charge:park"
    private fun rejected(block: () -> Unit) {
        try { block(); fail() } catch (e: ToolException) { assertEquals("POLICY_REJECTED", e.code) }
    }
    private fun prepared(): VehicleToolPolicy = VehicleToolPolicy("driver").apply {
        after("energy_get_state", obj(), obj("estimatedRangeKm" to num(24), "reserveKm" to num(10)))
        after("profile_resolve_place", obj(), obj("destinationRef" to str("demo_driver:home")))
        after("charging_search", obj("minPowerKw" to num(120), "maxDetourMinutes" to num(10), "maxQueueMinutes" to num(10)),
            obj("items" to JsonArray(listOf(obj("stationRef" to str(ref), "powerKw" to num(150),
                "detourMinutes" to num(7), "queueMinutes" to num(5), "distanceKm" to num(12))))))
    }
    private fun checked(policy: VehicleToolPolicy, validUntil: Long, queue: Int = 5) {
        policy.after("charging_check_station", obj("stationRef" to str(ref)), obj("stationRef" to str(ref),
            "destinationRef" to str(ref), "available" to bool(true), "queueMinutes" to num(queue), "validUntil" to num(validUntil)))
    }
    @Test fun `planning requires fresh station check and cannot invent references`() {
        val policy = prepared()
        rejected { policy.before("charging_check_station", obj("stationRef" to str("fake")), true) }
        rejected { policy.before("navigation_plan_route", obj("destinationRef" to str(ref)), true) }
        checked(policy, 0)
        rejected { policy.before("navigation_plan_route", obj("destinationRef" to str(ref)), true) }
        checked(policy, System.currentTimeMillis() + 60000)
        policy.before("navigation_plan_route", obj("destinationRef" to str(ref)), true)
    }
    @Test fun `queue change or route range excess blocks navigation`() {
        val policy = prepared()
        checked(policy, System.currentTimeMillis() + 60000, 20)
        rejected { policy.before("navigation_plan_route", obj("destinationRef" to str(ref)), true) }
        checked(policy, System.currentTimeMillis() + 60000)
        policy.after("navigation_plan_route", obj("destinationRef" to str(ref)), obj("routeId" to str("route"),
            "expiresAt" to num(System.currentTimeMillis() + 60000), "distanceMeters" to num(15000)))
        rejected { policy.before("navigation_start", obj("routeId" to str("route")), false) }
        assertTrue(policy.pending.isEmpty())
    }
}
