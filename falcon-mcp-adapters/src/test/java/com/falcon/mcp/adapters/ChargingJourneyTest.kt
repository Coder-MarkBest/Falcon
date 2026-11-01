package com.falcon.mcp.adapters

import com.falcon.capability.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChargingJourneyTest {
    private val context = ToolContext("demo_driver", "driver", "charging_trip", "navigate")
    private fun search(detour: Int = 10, power: Int = 120) = obj("destinationRef" to str("demo_driver:home"),
        "maxDetourMinutes" to num(detour), "minPowerKw" to num(power), "maxQueueMinutes" to num(10))
    private suspend fun check(vehicle: DemoVehicle, ref: String) = vehicle.execute("charging_check_station", obj("stationRef" to str(ref)), context)
    private suspend fun plan(vehicle: DemoVehicle, ref: String) = vehicle.execute("navigation_plan_route", obj("destinationRef" to str(ref)), context)
    @Test fun `offline cheapest candidate can be replaced by checked reachable station without booking`() = runTest {
        val vehicle = DemoVehicle()
        val stations = vehicle.execute("charging_search", search(), context).getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf("demo:charge:river", "demo:charge:park"), stations.map { it.text("stationRef") })
        try { check(vehicle, "demo:charge:river"); fail() } catch (e: ToolException) { assertEquals("STATION_OFFLINE", e.code) }
        val checked = check(vehicle, "demo:charge:park")
        val route = plan(vehicle, checked.text("destinationRef"))
        assertEquals(12000L, route.integer("distanceMeters"))
        val args = obj("routeId" to route.getValue("routeId"))
        val receipt = vehicle.execute("navigation_start", args, context)
        assertEquals(receipt, vehicle.execute("navigation_start", args, context))
        val actual = vehicle.execute("navigation_get_state", obj(), context)
        assertEquals("demo:charge:park", actual.text("destinationRef"))
        assertEquals(receipt["navigationSessionId"], actual["navigationSessionId"])
    }
    @Test fun `strict constraints return no candidates and unverified reference cannot be planned`() = runTest {
        val vehicle = DemoVehicle()
        assertTrue(vehicle.execute("charging_search", search(2, 200), context).getValue("items").jsonArray.isEmpty())
        try { plan(vehicle, "demo:charge:park"); fail() } catch (e: ToolException) { assertEquals("STATION_CHECK_REQUIRED", e.code) }
        assertEquals("idle", vehicle.execute("navigation_get_state", obj(), context).text("status"))
    }
    @Test fun `station check expires before dispatch and cannot be shared across workflows`() = runTest {
        var now = 1000L
        val vehicle = DemoVehicle(clock = { now })
        check(vehicle, "demo:charge:park")
        try { vehicle.execute("navigation_plan_route", obj("destinationRef" to str("demo:charge:park")), context.copy(workflowId = "other")); fail() }
        catch (e: ToolException) { assertEquals("STATION_CHECK_REQUIRED", e.code) }
        val route = plan(vehicle, "demo:charge:park")
        now += 60_001
        try { vehicle.execute("navigation_start", obj("routeId" to route.getValue("routeId")), context); fail() }
        catch (e: ToolException) { assertEquals("STATION_CHECK_REQUIRED", e.code) }
        assertEquals("idle", vehicle.execute("navigation_get_state", obj(), context).text("status"))
    }
    @Test fun `provider rejects charging navigation without range reserve even with valid check`() = runTest {
        var snapshot = ""
        val initial = DemoVehicle(persist = { snapshot = it })
        check(initial, "demo:charge:park")
        val original = Json.parseToJsonElement(snapshot).jsonObject
        val low = JsonObject(original + ("energy" to obj("socPercent" to num(5), "estimatedRangeKm" to num(15), "reserveKm" to num(10), "connector" to str("gbt_dc"))))
        try { plan(DemoVehicle(low.toString()), "demo:charge:park"); fail() }
        catch (e: ToolException) { assertEquals("INSUFFICIENT_RANGE", e.code) }
    }
}
