package com.falcon.mcp.adapters

import com.falcon.agent.*
import com.falcon.capability.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class JourneyIntegrationTest {
    private fun client(vehicle: DemoVehicle, intercept: suspend (String) -> Unit = {}) = object : ToolClient {
        override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
            intercept(name); return vehicle.execute(name, arguments, context)
        }
    }
    private val session = AgentSession("demo_driver", "driver")
    private val context = ToolContext("demo_driver", "driver", "workflow_1", "temperature_1")
    @Test fun `three independent branches complete and verify actual state`() = runTest {
        val vehicle = DemoVehicle()
        val result = HomeJourneyAgent(MockIntentModel(), client(vehicle)).run("我要回家，有点冷，放点轻松的音乐", session)
        assertEquals(3, result.branches.size)
        assertTrue(result.branches.all { it.status == BranchStatus.SUCCEEDED })
        assertEquals(23.0, vehicle.execute("climate_get_state", obj("zone" to str("driver")), context).number("targetTemperatureC"), 0.0)
    }
    @Test fun `music failure preserves navigation and climate results`() = runTest {
        val result = HomeJourneyAgent(MockIntentModel(), client(DemoVehicle()) {
            if (it == "media_search") throw ToolException("MEDIA_OFFLINE", "offline")
        }).run("我要回家，有点冷，放点轻松的音乐", session)
        assertEquals(2, result.branches.count { it.status == BranchStatus.SUCCEEDED })
        assertEquals(BranchStatus.FAILED, result.branches.single { it.branch == "音乐" }.status)
    }
    @Test fun `duplicate writes survive provider restart and mismatched reuse is rejected`() = runTest {
        var snapshot: String? = null
        val vehicle = DemoVehicle(persist = { snapshot = it })
        val args = obj("zone" to str("driver"), "targetTemperatureC" to num(23), "expectedStateVersion" to num(1))
        val first = vehicle.execute("climate_set_temperature", args, context)
        val restarted = DemoVehicle(snapshot)
        assertEquals(first, restarted.execute("climate_set_temperature", args, context))
        assertEquals(2L, restarted.execute("climate_get_state", obj("zone" to str("driver")), context).integer("stateVersion"))
        try { restarted.execute("climate_set_temperature", JsonObject(args + ("targetTemperatureC" to num(24))), context); fail() }
        catch (e: ToolException) { assertEquals("IDEMPOTENCY_CONFLICT", e.code) }
    }
    @Test fun `concurrent state conflict does not overwrite manual setting`() = runTest {
        val vehicle = DemoVehicle()
        var changed = false
        val result = HomeJourneyAgent(MockIntentModel(), client(vehicle) { name ->
            if (name == "climate_set_temperature" && !changed) {
                changed = true
                vehicle.execute(name, obj("zone" to str("driver"), "targetTemperatureC" to num(20), "expectedStateVersion" to num(1)),
                    context.copy(actionId = "manual"))
            }
        }).run("有点冷", session)
        assertEquals(BranchStatus.FAILED, result.branches.single().status)
        assertEquals(20.0, vehicle.execute("climate_get_state", obj("zone" to str("driver")), context).number("targetTemperatureC"), 0.0)
    }
    @Test fun `timeout or lost write response is not retried or announced as success`() = runTest {
        var writes = 0
        val result = HomeJourneyAgent(MockIntentModel(), client(DemoVehicle()) {
            if (it == "media_play") { writes++; throw ToolException("TRANSPORT_ERROR", "lost") }
        }).run("放点轻松的音乐", session)
        assertEquals(1, writes)
        assertEquals(BranchStatus.UNKNOWN, result.branches.single().status)
    }
    @Test fun `failed persistence rolls back simulated state`() = runTest {
        val vehicle = DemoVehicle(persist = { error("disk full") })
        try { vehicle.execute("climate_set_temperature", obj("zone" to str("driver"), "targetTemperatureC" to num(23), "expectedStateVersion" to num(1)), context); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(22.0, vehicle.execute("climate_get_state", obj("zone" to str("driver")), context).number("targetTemperatureC"), 0.0)
    }
    @Test fun `other users and zones cannot read operation state`() = runTest {
        try { DemoVehicle().execute("operation_get", obj("operationRef" to str("op_fake")), context.copy(userId = "other")); fail() }
        catch (e: ToolException) { assertEquals("FORBIDDEN", e.code) }
    }
    @Test fun `operation references are isolated by workflow`() = runTest {
        val vehicle = DemoVehicle()
        val receipt = vehicle.execute("climate_set_temperature", obj("zone" to str("driver"),
            "targetTemperatureC" to num(23), "expectedStateVersion" to num(1)), context)
        val args = obj("operationRef" to receipt.getValue("operationRef"))
        assertEquals("succeeded", vehicle.execute("operation_get", args, context).text("status"))
        try { vehicle.execute("operation_get", args, context.copy(workflowId = "other")); fail() }
        catch (e: ToolException) { assertEquals("OPERATION_NOT_FOUND", e.code) }
    }
    @Test fun `accepted with unknown completion never becomes success`() = runTest {
        val vehicle = DemoVehicle()
        var writes = 0
        val client = object : ToolClient {
            override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
                if (name == "media_play") {
                    writes++
                    return obj("operationRef" to str("op_pending"), "status" to str("accepted"), "playbackSessionId" to str("session_pending"))
                }
                if (name == "operation_get") return obj("operationRef" to str("op_pending"), "status" to str("unknown"))
                return vehicle.execute(name, arguments, context)
            }
        }
        val result = HomeJourneyAgent(MockIntentModel(), client).run("放点轻松的音乐", session)
        assertEquals(BranchStatus.UNKNOWN, result.branches.single().status)
        assertEquals(1, writes)
    }

}
