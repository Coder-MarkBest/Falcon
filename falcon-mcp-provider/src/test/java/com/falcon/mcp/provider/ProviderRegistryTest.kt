package com.falcon.mcp.provider

import com.falcon.capability.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRegistryTest {
    private val context = ToolContext("driver", "driver", "workflow", "action")
    private fun definition(description: String = "读取测试数据") = PublishedTool(obj("name" to str("sensor_read"),
        "description" to str(description), "inputSchema" to Field("object", "参数", properties = mapOf("zone" to Field("string", "座位", setOf("driver")))).schema(),
        "outputSchema" to Field("object", "结果", properties = mapOf("value" to Field("integer", "值"))).schema(),
        "annotations" to obj("readOnlyHint" to bool(true))), "sensor.read", ToolProtocol.READ)
    private fun request(registry: ProviderToolRegistry, tool: PublishedTool, args: JsonObject = obj("zone" to str("driver"))) =
        obj("name" to str(tool.name), "revision" to registry.catalog().getValue("revision"), "contractHash" to str(tool.contractHash), "arguments" to args)
    private suspend fun rejected(code: String, call: suspend () -> Unit) {
        try { call(); fail("Expected $code") } catch (e: ToolException) { assertEquals(code, e.code) }
    }
    @Test fun `register execute unregister and re-register invalidate stale task binding`() = runTest {
        val registry = ProviderToolRegistry(); val tool = definition(); var calls = 0
        assertTrue(registry.catalog().getValue("tools").jsonArray.isEmpty())
        registry.registerTool(tool) { _, _ -> calls++; obj("value" to num(7)) }
        val old = request(registry, tool)
        assertEquals(num(7), registry.invoke(old, context)["value"])
        assertTrue(registry.unregisterTool(tool.name))
        rejected("TOOL_CHANGED") { registry.invoke(old, context) }
        registry.registerTool(tool) { _, _ -> calls++; obj("value" to num(9)) }
        rejected("TOOL_CHANGED") { registry.invoke(old, context) }
        assertEquals(num(9), registry.invoke(request(registry, tool), context)["value"])
        assertEquals(2, calls)
    }
    @Test fun `restart wrong contract and invalid arguments never execute handler`() = runTest {
        val tool = definition(); val registry = ProviderToolRegistry(); var calls = 0
        registry.registerTool(tool) { _, _ -> calls++; obj("value" to num(7)) }
        val old = request(registry, tool)
        val restarted = ProviderToolRegistry().apply { registerTool(tool) { _, _ -> calls++; obj("value" to num(7)) } }
        rejected("TOOL_CHANGED") { restarted.invoke(old, context) }
        rejected("TOOL_CHANGED") { registry.invoke(JsonObject(old + ("contractHash" to str("fake"))), context) }
        rejected("INVALID_ARGUMENT") { registry.invoke(request(registry, tool, obj("zone" to str("rear"))), context) }
        assertEquals(0, calls)
    }
    @Test fun `invalid handler output is rejected and semantic update changes hash`() = runTest {
        val tool = definition(); val registry = ProviderToolRegistry()
        registry.registerTool(tool) { _, _ -> obj("wrong" to num(3)) }
        rejected("INVALID_ARGUMENT") { registry.invoke(request(registry, tool), context) }
        assertNotEquals(tool.contractHash, definition("另一份说明").contractHash)
    }
    @Test fun `unsupported schemas and self declared write profiles are rejected`() {
        val original = definition()
        val input = original.descriptor.getValue("inputSchema").jsonObject
        try { PublishedTool(JsonObject(original.descriptor + ("inputSchema" to JsonObject(input + ("oneOf" to JsonArray(emptyList()))))), "sensor.read", ToolProtocol.READ); fail() }
        catch (_: IllegalArgumentException) { }
        try { PublishedTool(original.descriptor, "sensor.read", ToolProtocol.VEHICLE); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
