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
                    val destination = args.text("destinationRef")
                    val station = stations.firstOrNull { it.text("stationRef") == destination }
                    if (destination != "demo_driver:home") {
                        if (station == null) throw ToolException("INVALID_REFERENCE", "目的地引用无效")
                        requireCheckedStation(destination, context)
                    }
                    val distance = station?.number("distanceKm")?.times(1000)?.toLong() ?: 15000L
                    val routeId = "route_" + UUID.randomUUID()
                    val expiry = clock() + 300_000
                    val routes = state.getValue("routes").jsonObject.filterValues { it.jsonPrimitive.long > clock() }
                    if (routes.size >= 64) throw ToolException("CAPACITY_EXCEEDED", "路线数量已达上限")
                    state = JsonObject(state + ("routes" to JsonObject(routes + (routeId to num(expiry)))))
                    val details = (state["routeDetails"] as? JsonObject).orEmpty().filterKeys { it in routes }
                    state = JsonObject(state + ("routeDetails" to JsonObject(details + (routeId to obj(
                        "destinationRef" to str(destination), "distanceMeters" to num(distance), "workflowId" to str(context.workflowId))))))
                    obj("routeId" to str(routeId), "durationSeconds" to num(1200),
                        "distanceMeters" to num(distance), "expiresAt" to num(expiry))
                }
                "navigation_start" -> {
                    val routeId = args.text("routeId")
                    val expires = state.getValue("routes").jsonObject[routeId]?.jsonPrimitive?.longOrNull ?: 0
                    if (expires <= clock()) throw ToolException("ROUTE_EXPIRED", "路线无效或已过期")
                    val detail = (state["routeDetails"] as? JsonObject)?.get(routeId) as? JsonObject
                    if (detail != null && detail["workflowId"] != str(context.workflowId)) throw ToolException("INVALID_REFERENCE", "路线不属于当前任务")
                    val destination = detail?.text("destinationRef") ?: "demo_driver:home"
                    if (destination != "demo_driver:home") requireCheckedStation(destination, context)
                    val session = "nav_" + UUID.randomUUID()
                    state = JsonObject(state + ("navigation" to obj("status" to str("active"),
                        "routeId" to str(routeId), "navigationSessionId" to str(session), "destinationRef" to str(destination))))
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
                "energy_get_state" -> energy()
                "charging_search" -> {
                    if (args.text("destinationRef") != "demo_driver:home" || state["homeSet"] != bool(true))
                        throw ToolException("INVALID_REFERENCE", "仅支持已保存的家庭路线")
                    obj("items" to JsonArray(stations.filter {
                        it.number("powerKw") >= args.number("minPowerKw") &&
                        it.integer("detourMinutes") <= args.integer("maxDetourMinutes") &&
                        it.integer("queueMinutes") <= args.integer("maxQueueMinutes") && it["connector"] == energy()["connector"]
                    }.sortedBy { it.number("pricePerKwh") }))
                }
                "charging_check_station" -> {
                    val ref = args.text("stationRef")
                    val station = stations.firstOrNull { it.text("stationRef") == ref }
                        ?: throw ToolException("INVALID_REFERENCE", "站点引用无效")
                    if (ref == "demo:charge:river" && state["firstStationOffline"] != bool(false))
                        throw ToolException("STATION_OFFLINE", "首选站临时离线，请复核其他候选站")
                    val checked = obj("stationRef" to str(ref), "destinationRef" to str(ref), "available" to bool(true),
                        "queueMinutes" to station.getValue("queueMinutes"), "checkedAt" to num(clock()), "validUntil" to num(clock() + 60_000))
                    val existing = (state["chargingChecks"] as? JsonObject).orEmpty().filterValues {
                        it.jsonObject.integer("validUntil") > clock()
                    }.entries.toList().takeLast(63).associate { it.toPair() }
                    state = JsonObject(state + ("chargingChecks" to JsonObject(existing + ("${context.workflowId}:$ref" to checked))))
                    checked
                }
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
    private fun energy(): JsonObject = state["energy"] as? JsonObject ?: obj(
        "socPercent" to num(18), "estimatedRangeKm" to num(24), "reserveKm" to num(10), "connector" to str("gbt_dc"))
    private fun requireCheckedStation(ref: String, context: ToolContext) {
        val quote = (state["chargingChecks"] as? JsonObject)?.get("${context.workflowId}:$ref") as? JsonObject
        if (quote == null || quote.integer("validUntil") <= clock() || quote["available"] != bool(true))
            throw ToolException("STATION_CHECK_REQUIRED", "必须在当前任务实时复核站点")
        if (ref == "demo:charge:river" && state["firstStationOffline"] != bool(false))
            throw ToolException("STATION_OFFLINE", "站点临时离线")
        val distance = stations.single { it.text("stationRef") == ref }.number("distanceKm")
        if (distance + energy().number("reserveKm") > energy().number("estimatedRangeKm"))
            throw ToolException("INSUFFICIENT_RANGE", "估算续航不满足到站及预留要求")
    }
    private val stations = listOf(
        station("river", "江畔快充（模拟）", 180, 4, 0, 0.8, 8),
        station("park", "公园快充（模拟）", 150, 7, 5, 1.2, 12),
        station("square", "广场充电（模拟）", 90, 3, 0, 0.6, 5)
    )
    private fun station(ref: String, name: String, power: Int, detour: Int, queue: Int, price: Double, distance: Int) = obj(
        "stationRef" to str("demo:charge:$ref"), "name" to str(name), "powerKw" to num(power), "detourMinutes" to num(detour),
        "queueMinutes" to num(queue), "pricePerKwh" to num(price), "distanceKm" to num(distance), "connector" to str("gbt_dc"))
    private fun receipt(extra: JsonObject, context: ToolContext): JsonObject {
        val ref = "op_" + UUID.randomUUID()
        val operation = obj("operationRef" to str(ref), "status" to str("succeeded"))
        state = JsonObject(state + ("operations" to JsonObject(state.getValue("operations").jsonObject + (ref to obj("owner" to str(context.userId), "workflowId" to str(context.workflowId), "result" to operation)))))
        return JsonObject(operation + extra)
    }
}
