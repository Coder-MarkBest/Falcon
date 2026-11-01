package com.falcon.capability

import kotlinx.serialization.json.*

fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))
fun str(value: String) = JsonPrimitive(value)
fun num(value: Number) = JsonPrimitive(value)
fun bool(value: Boolean) = JsonPrimitive(value)
fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw ToolException("INVALID_ARGUMENT", "Missing string: $key")
fun JsonObject.number(key: String): Double = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    ?.takeIf { it.isFinite() } ?: throw ToolException("INVALID_ARGUMENT", "Missing number: $key")
fun JsonObject.integer(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    ?: throw ToolException("INVALID_ARGUMENT", "Missing integer: $key")

class ToolException(val code: String, override val message: String) : RuntimeException(message)

data class Field(
    val type: String,
    val description: String,
    val choices: Set<String> = emptySet(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val item: Field? = null,
    val properties: Map<String, Field> = emptyMap(),
    val required: Set<String> = properties.keys
) {
    fun schema(): JsonObject = buildJsonObject {
        put("type", type); put("description", description)
        if (choices.isNotEmpty()) put("enum", JsonArray(choices.map(::str)))
        minimum?.let { put("minimum", it) }; maximum?.let { put("maximum", it) }
        if (type == "string") put("maxLength", 256)
        if (type == "array") { put("maxItems", 5); put("items", requireNotNull(item).schema()) }
        if (type == "object") {
            put("properties", JsonObject(properties.mapValues { it.value.schema() }))
            put("required", JsonArray(required.map(::str))); put("additionalProperties", false)
        }
    }

    fun validate(value: JsonElement, path: String) {
        val p = value as? JsonPrimitive
        val valid = when (type) {
            "string" -> p?.isString == true && p.content.length <= 256 &&
                (choices.isEmpty() || p.content in choices)
            "boolean" -> p != null && !p.isString && p.booleanOrNull != null
            "integer", "number" -> p != null && !p.isString && p.doubleOrNull?.let {
                it.isFinite() && (type != "integer" || p.longOrNull != null) &&
                    (minimum == null || it >= minimum) && (maximum == null || it <= maximum)
            } == true
            "array" -> value is JsonArray && value.size <= 5
            "object" -> value is JsonObject && value.keys.all { it in properties } && value.keys.containsAll(required)
            else -> false
        }
        if (!valid) throw ToolException("INVALID_ARGUMENT", "Invalid field: $path")
        if (value is JsonObject) value.forEach { (key, child) -> properties.getValue(key).validate(child, "$path.$key") }
        if (value is JsonArray) value.forEachIndexed { i, child -> requireNotNull(item).validate(child, "$path[$i]") }
    }
}

private fun s(description: String, vararg choices: String) = Field("string", description, choices.toSet())
private fun n(description: String, min: Double? = null, max: Double? = null) = Field("number", description, minimum = min, maximum = max)
private fun i(description: String, min: Double = 0.0, max: Double? = null) = Field("integer", description, minimum = min, maximum = max)
private fun b(description: String) = Field("boolean", description)
private fun fields(vararg values: Pair<String, Field>) = mapOf(*values)
private fun objectField(values: Map<String, Field>, required: Set<String> = values.keys) =
    Field("object", "Structured result", properties = values, required = required)

data class ToolDefinition(
    val name: String,
    val description: String,
    val route: String,
    val input: Field,
    val output: Field,
    val scope: String,
    val readOnly: Boolean,
    val idempotent: Boolean = readOnly
) {
    fun descriptor(): JsonObject = obj(
        "name" to str(name), "description" to str(description),
        "inputSchema" to input.schema(), "outputSchema" to output.schema(),
        "annotations" to obj("readOnlyHint" to bool(readOnly), "destructiveHint" to bool(false),
            "idempotentHint" to bool(idempotent), "openWorldHint" to bool(false))
    )
}

object VehicleTools {
    private val zone = s("座位区域，必须在会话授权范围内", "driver", "front_passenger")
    private val receipt = fields(
        "operationRef" to s("操作引用"),
        "status" to s("accepted仅表示接受，succeeded表示已确认生效", "accepted", "succeeded")
    )
    private fun tool(name: String, description: String, route: String, input: Map<String, Field>,
        output: Map<String, Field>, scope: String, read: Boolean, idempotent: Boolean = read,
        requiredOutput: Set<String> = output.keys) = ToolDefinition(name, description, route,
        objectField(input), objectField(output, requiredOutput), scope, read, idempotent)

    val all: List<ToolDefinition> = listOf(
        tool("profile_resolve_place", "解析当前授权用户已保存的家；不存在时请用户先在车机设置中保存家庭地址，然后再试。不支持聊天输入具体地址、地址搜索或保存地址，不能建议用户提供具体地址，不猜测地址。",
            "profile/place/resolve", fields("label" to s("已保存地点标签", "home")),
            fields("destinationRef" to s("当前用户的目的地引用"), "displayName" to s("目的地显示名称")), "profile.read", true),
        tool("navigation_plan_route", "规划到目的地的路线，不启动导航。", "navigation/route/plan",
            fields("destinationRef" to s("由profile_resolve_place获得的引用")),
            fields("routeId" to s("路线引用"), "durationSeconds" to i("预计时长秒"),
                "distanceMeters" to i("距离米"), "expiresAt" to i("有效期Unix毫秒")), "navigation.read", true),
        tool("navigation_start", "启动有效路线；超时后先查询操作，不能盲目重发。", "navigation/session/start",
            fields("routeId" to s("规划得到的路线引用")), receipt + fields("navigationSessionId" to s("导航会话")), "navigation.write", false),
        tool("navigation_get_state", "查询实际导航状态。", "navigation/session/get", emptyMap(),
            fields("status" to s("导航状态", "idle", "active"), "routeId" to s("当前路线"),
                "navigationSessionId" to s("导航会话")), "navigation.read", true, requiredOutput = setOf("status")),
        tool("climate_get_state", "读取指定区域的目标温度、范围、步长和版本。", "climate/state/get",
            fields("zone" to zone), fields("zone" to zone, "targetTemperatureC" to n("设定温度摄氏度"),
                "minC" to n("最低目标温度"), "maxC" to n("最高目标温度"), "stepC" to n("温度步长", 0.1),
                "stateVersion" to i("并发控制版本"), "observedAt" to i("观测时间Unix毫秒")), "climate.read", true),
        tool("climate_set_temperature", "设置空调目标温度，必须先读取状态。设置成功不代表车内已达到该温度。",
            "climate/temperature/set", fields("zone" to zone, "targetTemperatureC" to n("目标温度摄氏度", 16.0, 30.0),
                "expectedStateVersion" to i("读取时的状态版本")), receipt + fields("zone" to zone,
                "targetTemperatureC" to n("实际设定温度"), "stateVersion" to i("新状态版本")), "climate.write", false, true),
        tool("media_search", "搜索轻松音乐；不购买、不订阅。只使用返回的可播放引用。", "media/content/search",
            fields("mood" to s("音乐情绪", "relaxing"), "limit" to i("候选数量", 1.0, 5.0)),
            fields("items" to Field("array", "候选内容", item = objectField(fields(
                "contentRef" to s("内容引用"), "title" to s("标题"), "playable" to b("可以直接播放"),
                "requiresPurchase" to b("需要购买"))))), "media.read", true),
        tool("media_play", "播放搜索返回的可播放内容，保留现有音量。", "media/playback/play",
            fields("contentRef" to s("搜索返回的内容引用")), receipt + fields("playbackSessionId" to s("播放会话")), "media.write", false),
        tool("media_get_state", "读取实际播放状态。", "media/playback/get", emptyMap(),
            fields("status" to s("播放状态", "idle", "playing"), "contentRef" to s("当前内容"),
                "playbackSessionId" to s("播放会话")), "media.read", true, requiredOutput = setOf("status")),
        tool("operation_get", "按操作引用查询执行结果。未知结果不能当作失败重试。", "agent/operation/get",
            fields("operationRef" to s("本会话可见的操作引用")),
            fields("operationRef" to s("操作引用"), "status" to s("执行状态", "pending", "running", "succeeded", "failed", "cancelled", "unknown")),
            "operation.read", true)
    )
    val byName = all.associateBy { it.name }
}

data class ToolContext(val userId: String, val zone: String, val workflowId: String, val actionId: String)

interface ToolClient {
    suspend fun listTools(): List<ToolDefinition> = VehicleTools.all
    suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject
}
