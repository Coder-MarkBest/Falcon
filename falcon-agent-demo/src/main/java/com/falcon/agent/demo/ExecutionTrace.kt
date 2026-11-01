package com.falcon.agent.demo

import com.falcon.agent.*
import com.falcon.capability.*
import kotlinx.serialization.json.*
import java.io.File

/** Explicit demo diagnostic trace. Never records authentication headers or credential files. */
class ExecutionTrace(private val file: File) {
    @Synchronized fun reset() { file.writeText("") }
    @Synchronized fun record(stage: String, data: JsonObject) {
        file.appendText(obj("at" to num(System.currentTimeMillis()), "stage" to str(stage), "data" to data).toString() + "\n")
    }
    fun model(delegate: DeepSeekTransport) = DeepSeekTransport { request ->
        record("deepseek.request", obj("model" to str(DeepSeekHttpTransport.MODEL), "input" to str(request.user)))
        delegate.complete(request).also { record("deepseek.response", it) }
    }
    fun tools(delegate: ToolClient) = object : ToolClient {
        override suspend fun listTools(): List<ToolDefinition> = delegate.listTools().also {
            record("mcp.tools.list", obj("names" to JsonArray(it.map { tool -> str(tool.name) })))
        }
        override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
            val metadata = obj("workflowId" to str(context.workflowId), "actionId" to str(context.actionId), "tool" to str(name))
            record("mcp.tools.call.request", JsonObject(metadata + ("arguments" to arguments)))
            return delegate.call(name, arguments, context).also {
                record("mcp.tools.call.result", JsonObject(metadata + ("structuredContent" to it)))
            }
        }
    }
}
