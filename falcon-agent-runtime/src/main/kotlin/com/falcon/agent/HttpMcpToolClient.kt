package com.falcon.agent

import com.falcon.capability.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Native MCP client for this demo; never forwards the gateway token to a model. */
class HttpMcpToolClient(private val endpoint: String, private val token: String) : ToolClient, McpToolSession {
    private val ids = AtomicLong()
    private val initMutex = Mutex()
    @Volatile private var sessionId: String? = null
    init {
        val url = URL(endpoint)
        require(url.protocol == "http" && url.host == "127.0.0.1" && url.path == "/mcp" && url.userInfo == null)
        require(token.isNotBlank())
    }
    suspend fun initialize() = initMutex.withLock {
        if (sessionId != null) return@withLock
        val (result, session) = send("initialize", obj("protocolVersion" to str(VERSION),
            "capabilities" to obj(), "clientInfo" to obj("name" to str("falcon-agent-demo"), "version" to str("0.1"))))
        if (result?.get("protocolVersion") != str(VERSION) || session.isNullOrBlank()) {
            throw ToolException("PROTOCOL_ERROR", "MCP初始化失败。")
        }
        sessionId = session
        try { send("notifications/initialized", obj(), notification = true) }
        catch (e: Exception) { sessionId = null; throw e }
    }
    override suspend fun descriptors(): List<JsonObject> {
        initialize()
        val tools = mutableListOf<JsonObject>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = requireNotNull(send("tools/list", cursor?.let { obj("cursor" to str(it)) } ?: obj()).first)
            tools += result.getValue("tools").jsonArray.map { it.jsonObject }
            if (tools.size > 64) throw ToolException("CATALOG_TOO_LARGE", "工具目录过大。")
            cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (cursor != null && !cursors.add(cursor)) throw ToolException("PROTOCOL_ERROR", "目录分页循环。")
        } while (cursor != null)
        return tools
    }
    // Compatibility only for the old offline demo. The tool-calling agent uses descriptors().
    override suspend fun listTools(): List<ToolDefinition> {
        val names = descriptors().map { it.text("name") }.toSet()
        return VehicleTools.all.filter { it.name in names }
    }
    override suspend fun execute(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
        initialize()
        return requireNotNull(send("tools/call", obj("name" to str(name), "arguments" to arguments,
            "_meta" to obj("falcon/workflowId" to str(context.workflowId), "falcon/actionId" to str(context.actionId)))).first)
    }
    override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
        val result = execute(name, arguments, context)
        if (result["isError"] == bool(true)) {
            val code = (result["_meta"] as? JsonObject)?.get("falcon/errorCode")?.jsonPrimitive?.content ?: "TOOL_ERROR"
            throw ToolException(code, "工具未确认完成。")
        }
        return result["structuredContent"] as? JsonObject ?: throw ToolException("PROTOCOL_ERROR", "缺少结构化结果。")
    }
    private suspend fun send(method: String, params: JsonObject, notification: Boolean = false): Pair<JsonObject?, String?> = withContext(Dispatchers.IO) {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); if (!notification) put("id", id)
            put("method", method); put("params", params)
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000; connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { connection.setRequestProperty("Mcp-Session-Id", it) }
            if (method != "initialize") connection.setRequestProperty("MCP-Protocol-Version", VERSION)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status == 202 && notification) return@withContext null to sessionId
            if (status != 200) throw ToolException("TRANSPORT_ERROR", "MCP HTTP调用未完成。")
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 65536) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65537 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            if (bytes.size > 65536) throw ToolException("PROTOCOL_ERROR", "MCP响应过大。")
            val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            if (response["jsonrpc"] != str("2.0") || response["id"] != num(id)) throw ToolException("PROTOCOL_ERROR", "MCP响应不匹配。")
            if ("error" in response) throw ToolException("PROTOCOL_ERROR", "MCP协议拒绝请求。")
            response.getValue("result").jsonObject to connection.getHeaderField("Mcp-Session-Id")
        } catch (e: CancellationException) { throw e
        } catch (e: ToolException) { throw e
        } catch (e: Exception) { throw ToolException("TRANSPORT_ERROR", "MCP连接中断，执行结果需核实。")
        } finally { connection.disconnect() }
    }
    companion object { const val VERSION = "2025-11-25" }
}
