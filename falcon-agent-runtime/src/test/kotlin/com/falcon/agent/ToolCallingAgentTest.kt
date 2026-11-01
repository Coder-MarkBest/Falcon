package com.falcon.agent

import com.falcon.capability.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolCallingAgentTest {
    private val session = AgentSession("demo_driver", "driver")
    private fun call(id: String, name: String, args: JsonObject = obj()) = obj("id" to str(id), "type" to str("function"),
        "function" to obj("name" to str(name), "arguments" to str(args.toString())))
    private fun choice(vararg calls: JsonObject) = obj("finish_reason" to str("tool_calls"),
        "message" to obj("role" to str("assistant"), "content" to JsonNull, "tool_calls" to JsonArray(calls.toList())))
    private fun done() = obj("finish_reason" to str("stop"), "message" to obj("role" to str("assistant"), "content" to str("已核实。")))
    private fun result(data: JsonObject) = obj("isError" to bool(false), "structuredContent" to data,
        "content" to JsonArray(listOf(obj("type" to str("text"), "text" to str(data.toString())))))
    private class Session(val catalog: List<JsonObject>, val executeCall: (String, JsonObject) -> JsonObject) : McpToolSession {
        val calls = mutableListOf<String>()
        override suspend fun descriptors() = catalog
        override suspend fun execute(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
            calls += name
            assertTrue(context.actionId.matches(Regex("call_[0-9]+")))
            return executeCall(name, arguments)
        }
    }
    private fun catalog(vararg names: String) = VehicleTools.all.filter { it.name in names }.map { it.descriptor() }
    private fun lastResult(request: JsonObject): JsonObject = Json.parseToJsonElement(
        request.getValue("messages").jsonArray.last().jsonObject.text("content")).jsonObject
    private fun climate(temp: Double = 22.0, version: Int = 1) = obj("zone" to str("driver"),
        "targetTemperatureC" to num(temp), "minC" to num(16), "maxC" to num(30), "stepC" to num(0.5),
        "stateVersion" to num(version), "observedAt" to num(1))
    private fun setting(temp: Number = 23) = obj("zone" to str("driver"), "targetTemperatureC" to num(temp), "expectedStateVersion" to num(1))

    @Test fun `empty runtime catalog permits honest answer without model tools`() = runTest {
        val tools = Session(emptyList()) { _, _ -> error("No tool may execute") }
        val model = ToolCallingModel { request ->
            assertFalse("tools" in request)
            assertFalse("tool_choice" in request)
            done()
        }
        assertEquals(0, ToolCallingAgent(model, tools).run("查询新能力", session).calls)
    }
    @Test fun `remote catalog drives a previously unknown readonly tool and results are fed back exactly`() = runTest {
        val descriptor = obj("name" to str("cabin_read_light"), "description" to str("读取当前座舱灯光"),
            "inputSchema" to obj("type" to str("object"), "properties" to obj(), "additionalProperties" to bool(false)),
            "annotations" to obj("readOnlyHint" to bool(true)))
        val returned = result(obj("brightness" to num(42)))
        val tools = Session(listOf(descriptor)) { _, _ -> returned }
        var rounds = 0
        val model = ToolCallingModel { request ->
            val function = request.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
            assertEquals(descriptor["inputSchema"], function["parameters"])
            assertTrue(function.text("description").startsWith(descriptor.text("description")))
            if (rounds++ == 0) choice(call("light_1", "cabin_read_light")) else {
                assertEquals(returned, lastResult(request))
                val messages = request.getValue("messages").jsonArray
                assertEquals(str("light_1"), messages.last().jsonObject["tool_call_id"])
                assertEquals(str("assistant"), messages[messages.size - 2].jsonObject["role"])
                done()
            }
        }
        assertEquals(2, ToolCallingAgent(model, tools).run("灯光状态", session).rounds)
        assertEquals(listOf("cabin_read_light"), tools.calls)
    }
    @Test fun `unknown tool and invalid arguments return errors without invoking MCP then model repairs`() = runTest {
        val tools = Session(catalog("climate_get_state")) { _, _ -> result(climate()) }
        var round = 0
        val model = ToolCallingModel { request ->
            when (round++) {
                0 -> choice(call("a", "invented_tool"))
                1 -> { assertEquals(str("UNKNOWN_TOOL"), lastResult(request)["_meta"]!!.jsonObject["falcon/errorCode"])
                    choice(call("b", "climate_get_state", obj("zone" to num(1)))) }
                2 -> { assertEquals(bool(true), lastResult(request)["isError"])
                    choice(call("c", "climate_get_state", obj("zone" to str("driver")))) }
                else -> done()
            }
        }
        ToolCallingAgent(model, tools).run("温度多少", session)
        assertEquals(listOf("climate_get_state"), tools.calls)
    }
    @Test fun `model chooses read write and verification while duplicate writes are cached`() = runTest {
        var temp = 22.0
        val tools = Session(catalog("climate_get_state", "climate_set_temperature")) { name, _ ->
            if (name == "climate_get_state") result(climate(temp, if (temp == 22.0) 1 else 2))
            else { temp = 23.0; result(obj("operationRef" to str("op"), "status" to str("succeeded"),
                "zone" to str("driver"), "targetTemperatureC" to num(23.0), "stateVersion" to num(2))) }
        }
        var round = 0
        val model = ToolCallingModel { request -> when (round++) {
            0 -> choice(call("a", "climate_get_state", obj("zone" to str("driver"))))
            1 -> choice(call("b", "climate_set_temperature", setting()))
            2 -> choice(call("c", "climate_set_temperature", setting()))
            3 -> done() // Host must refuse early success until model chooses verification.
            4 -> {
                assertTrue(request.getValue("messages").jsonArray.last().jsonObject.text("content").contains("尚未读取实际状态"))
                choice(call("d", "climate_get_state", obj("zone" to str("driver"))))
            }
            else -> done()
        } }
        val result = ToolCallingAgent(model, tools).run("有点冷", session)
        assertEquals(6, result.rounds)
        assertEquals(listOf("climate_get_state", "climate_set_temperature", "climate_get_state"), tools.calls)
    }
    @Test fun `missing prerequisites and excess temperature delta never reach provider`() = runTest {
        val tools = Session(catalog("climate_get_state", "climate_set_temperature")) { _, _ -> result(climate()) }
        var round = 0
        val model = ToolCallingModel { request -> when (round++) {
            0 -> choice(call("a", "climate_set_temperature", setting()))
            1 -> { assertEquals(bool(true), lastResult(request)["isError"])
                choice(call("b", "climate_get_state", obj("zone" to str("driver")))) }
            2 -> choice(call("c", "climate_set_temperature", setting(25)))
            else -> { assertEquals(bool(true), lastResult(request)["isError"]); done() }
        } }
        ToolCallingAgent(model, tools).run("暖一点", session)
        assertEquals(listOf("climate_get_state"), tools.calls)
    }
    @Test fun `lost write response cannot cause repeat effect and cannot finish as confirmed`() = runTest {
        val tools = Session(catalog("climate_get_state", "climate_set_temperature")) { name, _ ->
            if (name == "climate_get_state") result(climate()) else throw ToolException("TRANSPORT_ERROR", "lost")
        }
        var round = 0
        val model = ToolCallingModel { request -> when (round++) {
            0 -> choice(call("a", "climate_get_state", obj("zone" to str("driver"))))
            1 -> choice(call("b", "climate_set_temperature", setting()))
            2 -> { assertEquals(str("RESULT_UNKNOWN"), lastResult(request)["_meta"]!!.jsonObject["falcon/errorCode"])
                choice(call("c", "climate_set_temperature", setting())) }
            else -> done()
        } }
        try { ToolCallingAgent(model, tools, maxRounds = 5).run("暖一点", session); fail() }
        catch (e: ToolException) { assertEquals("AGENT_BUDGET", e.code) }
        assertEquals(1, tools.calls.count { it == "climate_set_temperature" })
    }
    @Test fun `truncated or duplicate-id batch performs no partial execution`() = runTest {
        val tools = Session(catalog("navigation_get_state")) { _, _ -> result(obj("status" to str("idle"))) }
        val call = call("duplicate", "navigation_get_state")
        for (response in listOf(choice(call, call), JsonObject(choice(call) + ("finish_reason" to str("length"))))) {
            try { ToolCallingAgent(ToolCallingModel { response }, tools).run("查询导航", session); fail() }
            catch (e: ToolException) { assertEquals("INVALID_MODEL_OUTPUT", e.code) }
        }
        assertTrue(tools.calls.isEmpty())
    }
    @Test fun `MCP business errors are preserved in model feedback`() = runTest {
        val failure = ToolCallingAgent.errorResult("HOME_NOT_SET", "请设置家庭地址")
        val tools = Session(catalog("profile_resolve_place")) { _, _ -> failure }
        var round = 0
        ToolCallingAgent(ToolCallingModel { request ->
            if (round++ == 0) choice(call("a", "profile_resolve_place", obj("label" to str("home"))))
            else { assertEquals(failure, lastResult(request)); done() }
        }, tools).run("回家", session)
        assertEquals(1, tools.calls.size)
    }
    @Test fun `call budget stops model loops`() = runTest {
        val tools = Session(catalog("navigation_get_state")) { _, _ -> result(obj("status" to str("idle"))) }
        var id = 0
        try {
            ToolCallingAgent(ToolCallingModel { choice(call("c${id++}", "navigation_get_state")) }, tools, maxCalls = 2).run("导航状态", session)
            fail()
        } catch (e: ToolException) { assertEquals("AGENT_BUDGET", e.code) }
        assertEquals(2, tools.calls.size)
    }
    @Test fun `state conflict is returned and fresh reads cannot authorize a replacement write`() = runTest {
        var reads = 0
        val tools = Session(catalog("climate_get_state", "climate_set_temperature")) { name, _ ->
            if (name == "climate_get_state") result(climate(22.0, ++reads))
            else ToolCallingAgent.errorResult("STATE_CONFLICT", "手动设置已变化")
        }
        var round = 0
        ToolCallingAgent(ToolCallingModel { request -> when (round++) {
            0 -> choice(call("a", "climate_get_state", obj("zone" to str("driver"))))
            1 -> choice(call("b", "climate_set_temperature", setting()))
            2 -> { assertEquals(str("STATE_CONFLICT"), lastResult(request)["_meta"]!!.jsonObject["falcon/errorCode"])
                choice(call("c", "climate_get_state", obj("zone" to str("driver")))) }
            3 -> choice(call("d", "climate_set_temperature", JsonObject(setting() + ("expectedStateVersion" to num(2)))))
            else -> { assertEquals(str("POLICY_REJECTED"), lastResult(request)["_meta"]!!.jsonObject["falcon/errorCode"]); done() }
        } }, tools).run("有点冷", session)
        assertEquals(1, tools.calls.count { it == "climate_set_temperature" })
    }
    @Test fun `navigation and playback require issued references and matching final sessions`() {
        val policy = VehicleToolPolicy("driver")
        fun rejected(block: () -> Unit) {
            try { block(); fail() } catch (e: ToolException) { assertEquals("POLICY_REJECTED", e.code) }
        }
        rejected { policy.before("navigation_start", obj("routeId" to str("invented")), false) }
        rejected { policy.before("media_play", obj("contentRef" to str("paid")), false) }
        policy.after("media_search", obj(), obj("items" to JsonArray(listOf(
            obj("contentRef" to str("paid"), "playable" to bool(true), "requiresPurchase" to bool(true)),
            obj("contentRef" to str("free"), "playable" to bool(true), "requiresPurchase" to bool(false))
        ))))
        rejected { policy.before("media_play", obj("contentRef" to str("paid")), false) }
        policy.before("media_play", obj("contentRef" to str("free")), false)
        policy.after("media_play", obj(), obj("playbackSessionId" to str("new")))
        policy.after("media_get_state", obj(), obj("status" to str("playing"), "contentRef" to str("free"), "playbackSessionId" to str("old")))
        assertTrue("media_play" in policy.pending)
        policy.after("media_get_state", obj(), obj("status" to str("playing"), "contentRef" to str("free"), "playbackSessionId" to str("new")))
        assertTrue(policy.pending.isEmpty())
    }

    @Test fun `not dispatched readiness error can finish without pretending a write is pending`() = runTest {
        val tools = Session(catalog("climate_get_state", "climate_set_temperature")) { name, _ ->
            if (name == "climate_get_state") result(climate())
            else ToolCallingAgent.errorResult("PROVIDER_NOT_READY", "没有下发操作")
        }
        var round = 0
        val result = ToolCallingAgent(ToolCallingModel { request -> when (round++) {
            0 -> choice(call("a", "climate_get_state", obj("zone" to str("driver"))))
            1 -> choice(call("b", "climate_set_temperature", setting()))
            else -> {
                assertEquals(str("PROVIDER_NOT_READY"), lastResult(request)["_meta"]!!.jsonObject["falcon/errorCode"])
                obj("finish_reason" to str("stop"), "message" to obj("role" to str("assistant"), "content" to str("服务未就绪，未调温。")))
            }
        } }, tools).run("有点冷", session)
        assertEquals(3, result.rounds)
        assertEquals("服务未就绪，未调温。", result.summary)
    }

}
