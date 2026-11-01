package com.falcon.mcp.provider

import com.falcon.capability.*
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.dynamic.*
import com.falcon.mcp.provider.FalconJsonCodec
import kotlinx.serialization.json.*
import java.util.UUID

/** Registration and catalog publication are atomic. No lock is held across a handler call. */
class ProviderToolRegistry {
    private data class Entry(val tool: PublishedTool, val handler: suspend (JsonObject, ToolContext) -> JsonObject)
    private val entries = linkedMapOf<String, Entry>()
    private val epoch = UUID.randomUUID().toString()
    private var revision = 0L
    @Synchronized fun registerTool(tool: PublishedTool, handler: suspend (JsonObject, ToolContext) -> JsonObject) {
        require(tool.name.length <= 48)
        require(entries.size < 32 || tool.name in entries)
        val proposed = entries.mapValues { it.value.tool }.toMutableMap().apply { put(tool.name, tool) }
        require(JsonArray(proposed.values.map { it.wire }).toString().toByteArray().size <= 48000)
        entries[tool.name] = Entry(tool, handler)
        revision++
    }
    @Synchronized fun unregisterTool(name: String): Boolean = (entries.remove(name) != null).also { if (it) revision++ }
    @Synchronized fun catalog(): JsonObject = obj("revision" to str("$epoch:$revision"),
        "tools" to JsonArray(entries.values.map { it.tool.wire }))
    suspend fun invoke(body: JsonObject, context: ToolContext): JsonObject {
        val entry = synchronized(this) {
            if (body.text("revision") != "$epoch:$revision") throw ToolException("TOOL_CHANGED", "工具目录已变化，请在下次任务刷新。")
            val entry = entries[body.text("name")] ?: throw ToolException("TOOL_UNAVAILABLE", "工具已注销。")
            if (entry.tool.contractHash != body.text("contractHash")) throw ToolException("TOOL_CHANGED", "工具契约已变化。")
            entry
        }
        val args = body.getValue("arguments").jsonObject
        ToolSchema.validate(entry.tool.descriptor.getValue("inputSchema").jsonObject, args)
        val result = entry.handler(args, context)
        ToolSchema.validate(entry.tool.descriptor.getValue("outputSchema").jsonObject, result)
        return result
    }
}

class FalconMcpProvider(falcon: FalconManager, private val authorizedCaller: String) {
    val registry = ProviderToolRegistry()
    init {
        falcon.route(ToolProtocol.CATALOG, 1) { request ->
            if (!authorized(request)) DynamicResponse.error(403, "Gateway identity required")
            else DynamicResponse.success(DynamicPayload.build { putString("catalogJson", registry.catalog().toString()) })
        }
        falcon.route(ToolProtocol.INVOKE, 1) { request ->
            if (!authorized(request)) DynamicResponse.error(403, "Gateway identity required")
            else try {
                val context = ToolContext(request.headers.getString("actor").orEmpty(), request.headers.getString("zone").orEmpty(),
                    request.headers.getString("workflowId").orEmpty(), request.headers.getString("actionId").orEmpty())
                require(context.userId.isNotBlank() && context.zone.isNotBlank() && context.workflowId.isNotBlank() && context.actionId.isNotBlank())
                DynamicResponse.success(FalconJsonCodec.encode(registry.invoke(FalconJsonCodec.decode(request.body), context)))
            } catch (e: ToolException) {
                DynamicResponse(statusCode = 409, body = FalconJsonCodec.encode(obj("code" to str(e.code))))
            }
        }
    }
    private fun authorized(request: DynamicRequest) = request.callerPackage == authorizedCaller && request.callerProcess == authorizedCaller
}
