package com.falcon.capability

import kotlinx.serialization.json.*
import java.security.MessageDigest

object ToolProtocol {
    const val CATALOG = "falcon/mcp/catalog/get"
    const val INVOKE = "falcon/mcp/tools/invoke"
    const val READ = "read.v1"
    const val VEHICLE = "vehicle.demo.v1"
    fun hash(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(prefix="{", postfix="}") { "${str(it)}:${canonical(value.getValue(it))}" }
        is JsonArray -> value.joinToString(prefix="[", postfix="]") { canonical(it) }
        else -> value.toString()
    }
}

/** The provider owns this definition. Gateway separately grants scopes/profiles. */
data class PublishedTool(val descriptor: JsonObject, val scope: String, val profile: String) {
    val name: String get() = descriptor.text("name")
    val readOnly: Boolean get() = descriptor.getValue("annotations").jsonObject["readOnlyHint"] == bool(true)
    val wire: JsonObject get() = obj("descriptor" to descriptor, "scope" to str(scope), "profile" to str(profile))
    val contractHash: String get() = ToolProtocol.hash(wire)
    init {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid local tool name" }
        require(descriptor.text("description").length in 1..2048)
        require(descriptor.keys.all { it in setOf("name", "description", "inputSchema", "outputSchema", "annotations") })
        require(scope.matches(Regex("[a-z][a-z0-9_.]{0,63}")))
        require(profile in setOf(ToolProtocol.READ, ToolProtocol.VEHICLE)) { "Unsupported execution profile" }
        require(!readOnly || profile == ToolProtocol.READ)
        require(readOnly || profile == ToolProtocol.VEHICLE)
        validateSchema(descriptor.getValue("inputSchema").jsonObject)
        validateSchema(descriptor.getValue("outputSchema").jsonObject)
        require(descriptor.getValue("inputSchema").jsonObject.text("type") == "object")
        require(descriptor.getValue("outputSchema").jsonObject.text("type") == "object")
        val annotations = descriptor.getValue("annotations").jsonObject
        require(annotations.keys.all { it in setOf("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint") })
        require(annotations.values.all { it is JsonPrimitive && !it.isString && it.booleanOrNull != null })
        require(wire.toString().toByteArray().size <= 16384)
    }
    companion object {
        fun fromWire(wire: JsonObject) = PublishedTool(wire.getValue("descriptor").jsonObject, wire.text("scope"), wire.text("profile"))
        private fun validateSchema(schema: JsonObject, depth: Int = 0) {
            require(depth <= 8 && schema.size <= 12)
            require(schema.keys.all { it in setOf("type", "description", "properties", "required", "additionalProperties", "enum", "minimum", "maximum", "maxLength", "maxItems", "items", "title") })
            val type = schema.text("type")
            require(type in setOf("object", "array", "string", "boolean", "number", "integer"))
            for (key in listOf("minimum", "maximum")) schema[key]?.let { require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true) }
            for (key in listOf("maxLength", "maxItems")) schema[key]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull in 0..65536) }
            for (key in listOf("description", "title")) schema[key]?.let { require(it is JsonPrimitive && it.isString && it.content.length <= 2048) }
            if ("minimum" in schema && "maximum" in schema) require(schema.number("minimum") <= schema.number("maximum"))
            schema["enum"]?.let { require(it is JsonArray && it.size in 1..32 && it.all { v -> v is JsonPrimitive && v != JsonNull }) }
            if (type == "object") {
                require(schema["additionalProperties"] == bool(false)) { "Objects must be closed" }
                val props = schema["properties"]?.jsonObject ?: obj()
                require(props.size <= 32 && props.keys.all { it.length in 1..64 })
                val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                require(required.distinct().size == required.size && required.all { it in props })
                props.values.forEach { validateSchema(it.jsonObject, depth + 1) }
            }
            if (type == "array") validateSchema(schema.getValue("items").jsonObject, depth + 1)
        }
    }
}
