package com.falcon.agent

import com.falcon.capability.*
import kotlinx.serialization.json.*

/** Raw MCP catalog and results; no dependency on a compile-time tool registry. */
interface McpToolSession {
    suspend fun descriptors(): List<JsonObject>
    suspend fun execute(name: String, arguments: JsonObject, context: ToolContext): JsonObject
}

object RemoteSchema {
    fun validate(schema: JsonObject, value: JsonElement, depth: Int = 0) = ToolSchema.validate(schema, value, depth)
}
