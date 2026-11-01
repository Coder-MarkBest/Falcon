package com.falcon.mcp.gateway

import com.falcon.agent.*
import com.falcon.capability.*
import com.falcon.mcp.adapters.DemoVehicle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpGatewayTest {
    private val principal = GatewayPrincipal("demo_driver", "driver", VehicleTools.all.map { it.scope }.toSet())
    private fun request(method: String, params: JsonObject = obj(), id: Long = 1) =
        obj("jsonrpc" to str("2.0"), "id" to num(id), "method" to str(method), "params" to params)
    private val init = request("initialize", obj("protocolVersion" to str(McpDispatcher.VERSION),
        "clientInfo" to obj("name" to str("test"), "version" to str("1")), "capabilities" to obj()))
    private fun backend(vehicle: DemoVehicle = DemoVehicle()) = object : ToolClient {
        override suspend fun call(name: String, arguments: JsonObject, context: ToolContext) = vehicle.execute(name, arguments, context)
    }
    private suspend fun connect(dispatcher: McpDispatcher): String {
        val session = requireNotNull(dispatcher.dispatch(init, null, null).sessionId)
        dispatcher.dispatch(obj("jsonrpc" to str("2.0"), "method" to str("notifications/initialized")), session, McpDispatcher.VERSION)
        return session
    }
    @Test fun `initialization gates tools and missing session is handled`() = runTest {
        val dispatcher = McpDispatcher(backend(), principal)
        assertEquals(404, dispatcher.dispatch(request("tools/list"), null, McpDispatcher.VERSION).status)
        val session = requireNotNull(dispatcher.dispatch(init, null, null).sessionId)
        assertNotNull(dispatcher.dispatch(request("tools/list"), session, McpDispatcher.VERSION).body?.get("error"))
        assertEquals(400, dispatcher.dispatch(request("ping"), session, "wrong").status)
    }
    @Test fun `invalid arguments and missing stable write metadata never invoke provider`() = runTest {
        var calls = 0
        val dispatcher = McpDispatcher(object : ToolClient {
            override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject { calls++; return obj() }
        }, principal)
        val session = connect(dispatcher)
        listOf(
            obj("name" to str("media_play"), "arguments" to obj("contentRef" to str("demo:relaxing"))),
            obj("name" to str("climate_get_state"), "arguments" to obj("zone" to str("invalid"))),
            obj("name" to str("media_search"), "arguments" to obj("mood" to str("relaxing"), "limit" to str("3")))
        ).forEach { assertNotNull(dispatcher.dispatch(request("tools/call", it), session, McpDispatcher.VERSION).body?.get("error")) }
        assertEquals(0, calls)
    }
    @Test fun `zone restrictions and scopes are enforced by gateway`() = runTest {
        val dispatcher = McpDispatcher(backend(), principal.copy(scopes = setOf("climate.read")))
        val session = connect(dispatcher)
        val result = dispatcher.dispatch(request("tools/call", obj("name" to str("climate_get_state"),
            "arguments" to obj("zone" to str("front_passenger")))), session, McpDispatcher.VERSION)
        assertEquals(bool(true), result.body?.get("result")?.jsonObject?.get("isError"))
        val list = dispatcher.dispatch(request("tools/list"), session, McpDispatcher.VERSION)
        assertEquals(1, list.body!!.getValue("result").jsonObject.getValue("tools").jsonArray.size)
    }
    @Test fun `tool notifications do not execute actions`() = runTest {
        var calls = 0
        val dispatcher = McpDispatcher(object : ToolClient {
            override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject { calls++; return obj() }
        }, principal)
        val session = connect(dispatcher)
        val notification = JsonObject(request("tools/call", obj("name" to str("media_play"))) - "id")
        assertEquals(202, dispatcher.dispatch(notification, session, McpDispatcher.VERSION).status)
        assertEquals(0, calls)
    }
    @Test fun `real HTTP MCP client completes three-branch journey`() = runBlocking {
        val server = McpHttpServer(McpDispatcher(backend(), principal), "test-token", 0)
        server.start(3000, true)
        try {
            val client = HttpMcpToolClient("http://127.0.0.1:${server.listeningPort}/mcp", "test-token")
            assertEquals(10, client.listTools().size)
            val result = HomeJourneyAgent(MockIntentModel(), client).run("我要回家，有点冷，放点轻松的音乐", AgentSession("demo_driver", "driver"))
            assertEquals(3, result.branches.size)
            assertTrue(result.summary, result.branches.all { it.status == BranchStatus.SUCCEEDED })
        } finally { server.stop() }
    }
    @Test fun `HTTP rejects invalid bearer origin oversized body and GET streaming`() {
        val server = McpHttpServer(McpDispatcher(backend(), principal), "test-token", 0)
        server.start(3000, true)
        fun http(method: String, token: String = "test-token", origin: String? = null, size: Int = 2): Int {
            return java.net.Socket("127.0.0.1", server.listeningPort).use { socket ->
                socket.soTimeout = 3000
                val headers = buildString {
                    append("$method /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n")
                    origin?.let { append("Origin: $it\r\n") }
                    if (method == "POST") append("Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: $size\r\n")
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().write((headers + if (method == "POST") " ".repeat(size) else "").toByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
            }
        }
        try {
            assertEquals(401, http("POST", "wrong"))
            assertEquals(403, http("POST", origin = "https://example.com"))
            assertEquals(405, http("GET"))
            assertEquals(400, http("POST", size = 32769))
        } finally { server.stop() }
    }
}
