package com.falcon.agent

import com.falcon.capability.*
import kotlinx.serialization.json.*

/** Raw MCP catalog and results; no dependency on a compile-time tool registry. */
interface McpToolSession {
    suspend fun descriptors(): List<JsonObject>
    suspend fun execute(name: String, arguments: JsonObject, context: ToolContext): JsonObject
}

object RemoteSchema {
    fun validate(schema: JsonObject, value: JsonElement, depth: Int = 0) {
        if (depth > 12) throw ToolException("INVALID_ARGUMENT", "Schema nesting exceeded")
        val allowed = setOf("type", "description", "properties", "required", "additionalProperties", "enum", "minimum", "maximum", "maxLength", "maxItems", "items", "title", "\$schema")
        if (schema.keys.any { it !in allowed }) throw ToolException("UNSUPPORTED_SCHEMA", "Unsupported schema constraint")
        val p = value as? JsonPrimitive
        fun reject(): Nothing = throw ToolException("INVALID_ARGUMENT", "参数不符合远端工具Schema")
        when (schema.text("type")) {
            "object" -> {
                val valueObj = value as? JsonObject ?: reject()
                val props = schema["properties"]?.jsonObject ?: obj()
                val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                if (!valueObj.keys.containsAll(required)) reject()
                valueObj.forEach { (k, v) ->
                    val child = props[k] as? JsonObject
                    if (child != null) validate(child, v, depth + 1)
                    else if (schema["additionalProperties"] == bool(false)) reject()
                }
            }
            "string" -> if (p?.isString != true || p.content.length > (schema["maxLength"]?.jsonPrimitive?.int ?: 65536)) reject()
            "boolean" -> if (p == null || p.isString || p.booleanOrNull == null) reject()
            "integer", "number" -> {
                val number = p?.takeUnless { it.isString }?.doubleOrNull ?: reject()
                if (!number.isFinite() || (schema.text("type") == "integer" && p.longOrNull == null)) reject()
                if (schema["minimum"]?.jsonPrimitive?.double?.let { number < it } == true || schema["maximum"]?.jsonPrimitive?.double?.let { number > it } == true) reject()
            }
            "array" -> {
                val array = value as? JsonArray ?: reject()
                if (array.size > (schema["maxItems"]?.jsonPrimitive?.int ?: 64)) reject()
                array.forEach { validate(schema.getValue("items").jsonObject, it, depth + 1) }
            }
            else -> throw ToolException("UNSUPPORTED_SCHEMA", "Unsupported schema type")
        }
        if (schema["enum"]?.jsonArray?.let { value !in it } == true) reject()
    }
}
