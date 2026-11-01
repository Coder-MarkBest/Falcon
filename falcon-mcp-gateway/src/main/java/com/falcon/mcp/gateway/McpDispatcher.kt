package com.falcon.mcp.gateway

import com.falcon.capability.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GatewayPrincipal(val userId: String, val zone: String, val scopes: Set<String>)
data class RpcReply(val status: Int, val body: JsonObject? = null, val sessionId: String? = null)

/** Small MCP tools server. No resources/tasks/stream notifications are advertised. */
class McpDispatcher(
    private val catalog: ToolCatalog,
    private val principal: GatewayPrincipal,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Session(val createdAt: Long, @Volatile var initialized: Boolean = false,
        @Volatile var tools: Map<String, ToolBinding>? = null)
    private val sessions = ConcurrentHashMap<String, Session>()
    companion object { const val VERSION = "2025-11-25" }
    fun close(sessionId: String?): Boolean = sessionId != null && sessions.remove(sessionId) != null
    suspend fun dispatch(request: JsonObject, sessionId: String?, version: String?): RpcReply {
        val id = request["id"]
        fun error(code: Int, message: String, status: Int = 200) = RpcReply(status, obj(
            "jsonrpc" to str("2.0"), "id" to (id ?: JsonNull),
            "error" to obj("code" to num(code), "message" to str(message))))
        if (request["jsonrpc"] != str("2.0") || request["method"] !is JsonPrimitive ||
            request.getValue("method").jsonPrimitive.isString.not() ||
            (id != null && (id !is JsonPrimitive || id === JsonNull || (!id.isString && id.longOrNull == null)))) {
            return error(-32600, "Invalid Request", 400)
        }
        val method = request.text("method")
        if (request["params"] != null && request["params"] !is JsonObject) return error(-32602, "Invalid params")
        val params = request["params"] as? JsonObject ?: obj()
        sessions.entries.removeAll { clock() - it.value.createdAt > 30 * 60_000 }
        if (method == "initialize") {
            if (id == null) return RpcReply(202)
            if (params["protocolVersion"] !is JsonPrimitive || params["capabilities"] !is JsonObject || params["clientInfo"] !is JsonObject) {
                return error(-32602, "Invalid initialize params")
            }
            val session = synchronized(sessions) {
                if (sessions.size >= 16) null else UUID.randomUUID().toString().also { sessions[it] = Session(clock()) }
            } ?: return error(-32000, "Session capacity exceeded", 429)
            return RpcReply(200, obj("jsonrpc" to str("2.0"), "id" to id, "result" to obj(
                "protocolVersion" to str(VERSION), "capabilities" to obj("tools" to obj("listChanged" to bool(false))),
                "serverInfo" to obj("name" to str("falcon-vehicle-gateway"), "version" to str("0.1"))
            )), session)
        }
        val session = sessionId?.let { sessions[it] } ?: return error(-32000, "Session missing or expired", 404)
        if (version != VERSION) return error(-32600, "Unsupported MCP-Protocol-Version", 400)
        if (id == null) {
            if (method == "notifications/initialized") session.initialized = true
            // Notifications never execute tools or other actions.
            return RpcReply(202)
        }
        if (!session.initialized && method != "ping") return error(-32000, "Session not initialized")
        val result = try {
            when (method) {
                "ping" -> obj()
                "tools/list" -> {
                    if (params.keys.any { it != "cursor" } || "cursor" in params) return error(-32602, "No cursor is issued by this bounded catalog")
                    val snapshot = catalog.snapshot().filterValues { it.tool.scope in principal.scopes }
                    session.tools = snapshot
                    obj("tools" to JsonArray(snapshot.values.map { it.tool.descriptor }))
                }
                "tools/call" -> {
                    if (params.keys.any { it !in setOf("name", "arguments", "_meta") }) return error(-32602, "Unknown tool parameter")
                    val bindings = session.tools ?: catalog.snapshot().also { session.tools = it }
                    val binding = bindings[params.text("name")] ?: return error(-32602, "Unknown tool")
                    val tool = binding.tool
                    val arguments = params["arguments"] as? JsonObject ?: obj()
                    if (params["arguments"] != null && params["arguments"] !is JsonObject) return error(-32602, "Invalid arguments")
                    try { ToolSchema.validate(tool.descriptor.getValue("inputSchema").jsonObject, arguments) }
                    catch (e: ToolException) { return error(-32602, e.message) }
                    if (tool.scope !in principal.scopes || ("zone" in arguments && arguments.text("zone") != principal.zone)) {
                        return RpcReply(200, success(id, toolError("FORBIDDEN")))
                    }
                    if (params["_meta"] != null && params["_meta"] !is JsonObject) return error(-32602, "Invalid metadata")
                    val meta = params["_meta"] as? JsonObject ?: obj()
                    val workflow = meta["falcon/workflowId"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    val action = meta["falcon/actionId"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    val keyPattern = Regex("[A-Za-z0-9_-]{1,100}")
                    if (("falcon/workflowId" in meta && workflow == null) || ("falcon/actionId" in meta && action == null) ||
                        (!tool.readOnly && (workflow == null || action == null)) ||
                        (workflow != null && !keyPattern.matches(workflow)) || (action != null && !keyPattern.matches(action))) {
                        return error(-32602, "Writes require stable falcon/workflowId and falcon/actionId metadata")
                    }
                    try {
                        val data = binding.invoke(arguments, ToolContext(principal.userId, principal.zone,
                            workflow ?: UUID.randomUUID().toString(), action ?: UUID.randomUUID().toString()))
                        try { ToolSchema.validate(tool.descriptor.getValue("outputSchema").jsonObject, data) }
                        catch (e: ToolException) { throw ToolException("INVALID_PROVIDER_OUTPUT", "Invalid provider output") }
                        obj("content" to JsonArray(listOf(obj("type" to str("text"), "text" to str(data.toString())))),
                            "structuredContent" to data, "isError" to bool(false))
                    } catch (e: CancellationException) { throw e
                    } catch (e: ToolException) { toolError(e.code)
                    } catch (e: Exception) { toolError("RESULT_UNKNOWN") }
                }
                else -> return error(-32601, "Method not found")
            }
        } catch (e: ToolException) { return error(-32602, e.message) }
        return RpcReply(200, success(id, result))
    }
    private fun success(id: JsonElement, result: JsonObject) = obj("jsonrpc" to str("2.0"), "id" to id, "result" to result)
    private fun toolError(code: String) = obj("content" to JsonArray(listOf(obj("type" to str("text"),
        "text" to str(when (code) {
            "HOME_NOT_SET" -> "HOME_NOT_SET：请先在车机设置中保存家庭地址，然后再试。当前工具不支持聊天输入具体地址。"
            "PROVIDER_NOT_READY" -> "PROVIDER_NOT_READY：服务尚未就绪，本次没有下发操作，请稍后重试。"
            else -> "$code：操作未确认完成。"
        })))), "isError" to bool(true),
        "_meta" to obj("falcon/errorCode" to str(code)))
}
