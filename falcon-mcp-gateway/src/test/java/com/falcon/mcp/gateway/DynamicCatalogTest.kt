package com.falcon.mcp.gateway

import com.falcon.capability.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DynamicCatalogTest {
    private fun request(method: String, params: JsonObject = obj()) = obj("jsonrpc" to str("2.0"), "id" to num(1), "method" to str(method), "params" to params)
    private val init get() = request("initialize", obj("protocolVersion" to str(McpDispatcher.VERSION), "clientInfo" to obj(), "capabilities" to obj()))
    private fun tool(scope: String = "comfort.read") = PublishedTool(obj("name" to str("new_sensor"), "description" to str("未知传感器"),
        "inputSchema" to Field("object", "参数").schema(), "outputSchema" to Field("object", "结果", properties = mapOf("value" to Field("integer", "值"))).schema(),
        "annotations" to obj("readOnlyHint" to bool(true))), scope, ToolProtocol.READ)
    private suspend fun connect(d: McpDispatcher): String {
        val id = d.dispatch(init, null, null).sessionId!!
        d.dispatch(obj("jsonrpc" to str("2.0"), "method" to str("notifications/initialized")), id, McpDispatcher.VERSION)
        return id
    }
    @Test fun `next task sees registration removal offline and reappearance without rebuilding dispatcher`() = runTest {
        var live = false; var registered = false; var calls = 0
        val catalog = ToolCatalog { if (live && registered) mapOf("new_sensor" to ToolBinding(tool()) { _, _ -> calls++; obj("value" to num(42)) }) else emptyMap() }
        val d = McpDispatcher(catalog, GatewayPrincipal("driver", "driver", setOf("comfort.read")))
        suspend fun list(session: String) = d.dispatch(request("tools/list"), session, McpDispatcher.VERSION).body!!.getValue("result").jsonObject.getValue("tools").jsonArray
        assertTrue(list(connect(d)).isEmpty())
        live = true; registered = true
        val session = connect(d)
        assertEquals(1, list(session).size)
        val returned = d.dispatch(request("tools/call", obj("name" to str("new_sensor"))), session, McpDispatcher.VERSION)
        assertEquals(num(42), returned.body!!.getValue("result").jsonObject.getValue("structuredContent").jsonObject["value"])
        registered = false; assertTrue(list(connect(d)).isEmpty())
        registered = true; live = false; assertTrue(list(connect(d)).isEmpty())
        live = true; assertEquals(1, list(connect(d)).size)
        assertEquals(1, calls)
    }
    @Test fun `scopes hide unapproved tools and returned schema is enforced`() = runTest {
        val catalog = ToolCatalog { mapOf("new_sensor" to ToolBinding(tool()) { _, _ -> obj("value" to str("bad")) }) }
        val denied = McpDispatcher(catalog, GatewayPrincipal("driver", "driver", emptySet()))
        val s = connect(denied)
        assertTrue(denied.dispatch(request("tools/list"), s, McpDispatcher.VERSION).body!!.getValue("result").jsonObject.getValue("tools").jsonArray.isEmpty())
        val d = McpDispatcher(catalog, GatewayPrincipal("driver", "driver", setOf("comfort.read")))
        val result = d.dispatch(request("tools/call", obj("name" to str("new_sensor"))), connect(d), McpDispatcher.VERSION).body!!.getValue("result").jsonObject
        assertEquals(str("INVALID_PROVIDER_OUTPUT"), result.getValue("_meta").jsonObject["falcon/errorCode"])
    }
}
