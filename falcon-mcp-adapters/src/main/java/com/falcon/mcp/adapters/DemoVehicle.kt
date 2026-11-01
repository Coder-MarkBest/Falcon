package com.falcon.mcp.adapters

import com.falcon.capability.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

/** Simulated actuators. One mutex covers state, deduplication, and durable snapshot commit. */
class DemoVehicle(
    snapshot: String? = null,
    private val persist: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var state = snapshot?.let { Json.parseToJsonElement(it).jsonObject } ?: obj(
        "homeSet" to bool(true), "temperature" to num(22.0), "version" to num(1),
        "navigation" to obj("status" to str("idle")), "media" to obj("status" to str("idle")),
        "routes" to obj(), "operations" to obj(), "dedup" to obj()
    )
    suspend fun execute(name: String, args: JsonObject, context: ToolContext): JsonObject = mutex.withLock {
        val tool = VehicleTools.byName[name] ?: throw ToolException("UNKNOWN_TOOL", "未知工具")
        tool.input.validate(args, "arguments")
        if (context.userId != "demo_driver" || context.zone != "driver" ||
            ("zone" in args && args.text("zone") != context.zone)) {
            throw ToolException("FORBIDDEN", "演示仅授权当前主驾用户")
        }
        val dedupKey = "${context.userId}:${context.workflowId}:${context.actionId}"
        // Canonical object order prevents harmless argument ordering from changing identity.
        val fingerprint = name + ":" + JsonObject(args.toSortedMap()).toString()
        val dedup = state.getValue("dedup").jsonObject
        if (!tool.readOnly) {
            dedup[dedupKey]?.jsonObject?.let {
                if (it.text("fingerprint") != fingerprint) throw ToolException("IDEMPOTENCY_CONFLICT", "动作键已用于其他参数")
                return@withLock it.getValue("result").jsonObject
            }
            if (dedup.size >= 1024) throw ToolException("CAPACITY_EXCEEDED", "演示动作记录已满，请清除演示数据")
        }
        val previous = state
        try {
            val result = when (name) {
                "profile_resolve_place" -> {
                    if (state["homeSet"] != bool(true)) throw ToolException("HOME_NOT_SET", "请设置家庭地址")
                    obj("destinationRef" to str("demo_driver:home"), "displayName" to str("家"))
                }
                "navigation_plan_route" -> {
                    if (args.text("destinationRef") != "demo_driver:home") throw ToolException("INVALID_REFERENCE", "目的地引用无效")
                    val routeId = "route_" + UUID.randomUUID()
                    val expiry = clock() + 300_000
                    val routes = state.getValue("routes").jsonObject.filterValues { it.jsonPrimitive.long > clock() }
                    if (routes.size >= 64) throw ToolException("CAPACITY_EXCEEDED", "路线数量已达上限")
                    state = JsonObject(state + ("routes" to JsonObject(routes + (routeId to num(expiry)))))
                    obj("routeId" to str(routeId), "durationSeconds" to num(1200),
                        "distanceMeters" to num(15000), "expiresAt" to num(expiry))
                }
                "navigation_start" -> {
                    val routeId = args.text("routeId")
                    val expires = state.getValue("routes").jsonObject[routeId]?.jsonPrimitive?.longOrNull ?: 0
                    if (expires <= clock()) throw ToolException("ROUTE_EXPIRED", "路线无效或已过期")
                    val session = "nav_" + UUID.randomUUID()
                    state = JsonObject(state + ("navigation" to obj("status" to str("active"),
                        "routeId" to str(routeId), "navigationSessionId" to str(session))))
                    receipt(obj("navigationSessionId" to str(session)), context)
                }
                "navigation_get_state" -> state.getValue("navigation").jsonObject
                "climate_get_state" -> obj("zone" to str(context.zone), "targetTemperatureC" to state.getValue("temperature"),
                    "minC" to num(16), "maxC" to num(30), "stepC" to num(0.5),
                    "stateVersion" to state.getValue("version"), "observedAt" to num(clock()))
                "climate_set_temperature" -> {
                    if (args.integer("expectedStateVersion") != state.integer("version")) throw ToolException("STATE_CONFLICT", "状态已变化")
                    val temp = args.number("targetTemperatureC")
                    if (temp * 2 != kotlin.math.round(temp * 2)) throw ToolException("INVALID_ARGUMENT", "温度必须符合0.5℃步长")
                    val version = state.integer("version") + 1
                    state = JsonObject(state + mapOf("temperature" to num(temp), "version" to num(version)))
                    receipt(obj("zone" to str(context.zone), "targetTemperatureC" to num(temp), "stateVersion" to num(version)), context)
                }
                "media_search" -> obj("items" to JsonArray(listOf(obj("contentRef" to str("demo:relaxing"),
                    "title" to str("轻松驾驶 · 模拟歌单"), "playable" to bool(true), "requiresPurchase" to bool(false)))))
                "media_play" -> {
                    if (args.text("contentRef") != "demo:relaxing") throw ToolException("INVALID_REFERENCE", "内容引用不可播放")
                    val session = "play_" + UUID.randomUUID()
                    state = JsonObject(state + ("media" to obj("status" to str("playing"),
                        "contentRef" to args.getValue("contentRef"), "playbackSessionId" to str(session))))
                    receipt(obj("playbackSessionId" to str(session)), context)
                }
                "media_get_state" -> state.getValue("media").jsonObject
                "operation_get" -> {
                    val record = state.getValue("operations").jsonObject[args.text("operationRef")]?.jsonObject
                        ?: throw ToolException("OPERATION_NOT_FOUND", "操作引用不存在")
                    if (record["owner"] != str(context.userId) || record["workflowId"] != str(context.workflowId)) {
                        throw ToolException("OPERATION_NOT_FOUND", "操作引用不存在")
                    }
                    record.getValue("result").jsonObject
                }
                else -> throw ToolException("UNKNOWN_TOOL", "未知工具")
            }
            tool.output.validate(result, "result")
            if (!tool.readOnly) {
                state = JsonObject(state + ("dedup" to JsonObject(dedup + (dedupKey to obj(
                    "fingerprint" to str(fingerprint), "result" to result)))))
            }
            if (state != previous) persist(state.toString())
            result
        } catch (e: Exception) { state = previous; throw e }
    }
    private fun receipt(extra: JsonObject, context: ToolContext): JsonObject {
        val ref = "op_" + UUID.randomUUID()
        val operation = obj("operationRef" to str(ref), "status" to str("succeeded"))
        state = JsonObject(state + ("operations" to JsonObject(state.getValue("operations").jsonObject + (ref to obj("owner" to str(context.userId), "workflowId" to str(context.workflowId), "result" to operation)))))
        return JsonObject(operation + extra)
    }
}
