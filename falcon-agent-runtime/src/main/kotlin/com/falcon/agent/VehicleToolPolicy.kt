package com.falcon.agent

import com.falcon.capability.*
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.round

/** Preconditions and observed completion only. Never schedules a tool or fills model arguments. */
internal class VehicleToolPolicy(private val zone: String) {
    val pending = mutableMapOf<String, JsonObject>()
    private val submitted = mutableSetOf<String>()
    private val receipts = mutableMapOf<String, JsonObject>()
    private val destinations = mutableSetOf<String>()
    private val routes = mutableMapOf<String, Long>()
    private val playable = mutableSetOf<String>()
    private var energy: JsonObject? = null
    private val stations = mutableMapOf<String, JsonObject>()
    private val checkedStations = mutableMapOf<String, JsonObject>()
    private val routeDistances = mutableMapOf<String, Double>()
    private val routeDestinations = mutableMapOf<String, String>()
    private var climate: JsonObject? = null
    private fun demand(ok: Boolean, message: String) {
        if (!ok) throw ToolException("POLICY_REJECTED", message)
    }
    fun before(name: String, args: JsonObject, read: Boolean) {
        if ("zone" in args) demand(args["zone"] == str(zone), "只能操作当前授权座位。")
        if (name == "navigation_plan_route") demand(args.text("destinationRef") in destinations, "请先解析当前用户的目的地。")
        if (name == "charging_search") demand(args.text("destinationRef") in destinations, "请先解析家庭地点。")
        if (name == "charging_check_station") demand(args.text("stationRef") in stations, "请先搜索候选站。")
        if (name == "navigation_plan_route" && args.text("destinationRef").startsWith("demo:charge:")) checkStation(args.text("destinationRef"))
        val knownWrite = name in setOf("navigation_start", "climate_set_temperature", "media_play")
        demand(!knownWrite || !read, "写操作目录注解不正确。")
        if (read) return
        demand(knownWrite, "此写操作尚未配置本地执行策略。")
        demand(name !in submitted, "本次任务已提交过此写操作，禁止再次提交；请查询实际状态。")
        when (name) {
            "navigation_start" -> {
                val route = args.text("routeId")
                demand((routes[route] ?: 0) > System.currentTimeMillis(), "请先规划有效路线。")
                routeDestinations[route]?.takeIf { it.startsWith("demo:charge:") }?.let { checkStation(it) }
                energy?.let { current -> demand((routeDistances[route] ?: Double.POSITIVE_INFINITY) + current.number("reserveKm") <= current.number("estimatedRangeKm"), "估算续航不足以满足行程和预留要求。") }
            }
            "media_play" -> demand(args.text("contentRef") in playable, "请先搜索无需购买且可播放的内容。")
            "climate_set_temperature" -> {
                val state = climate ?: throw ToolException("POLICY_REJECTED", "请先读取空调状态。")
                val target = args.number("targetTemperatureC")
                val step = state.number("stepC")
                val min = state.number("minC")
                val delta = target - state.number("targetTemperatureC")
                demand(args["expectedStateVersion"] == state["stateVersion"], "必须使用读取到的状态版本。")
                demand(step > 0 && target in min..state.number("maxC") && delta > 0 && delta <= 1.000001 &&
                    abs((target - min) / step - round((target - min) / step)) < 0.000001,
                    "本演示只允许按设备步长升温最多1℃，且不能越界。")
            }
        }
        submitted += name
        pending[name] = args
    }
    fun failure(name: String, result: JsonObject) {
        val code = (result["_meta"] as? JsonObject)?.get("falcon/errorCode")
        // Only definite server rejections clear pending. Transport/unknown errors need reconciliation.
        if (code in setOf(str("STATION_CHECK_REQUIRED"), str("STATION_OFFLINE"), str("INSUFFICIENT_RANGE"), str("PROVIDER_NOT_READY"), str("STATE_CONFLICT"), str("INVALID_ARGUMENT"), str("FORBIDDEN"),
                str("ROUTE_EXPIRED"), str("INVALID_REFERENCE"), str("CAPACITY_EXCEEDED"), str("HOME_NOT_SET"))) pending.remove(name)
    }
    private fun checkStation(ref: String) {
        val checked = checkedStations[ref] ?: throw ToolException("POLICY_REJECTED", "请先实时复核站点。")
        val candidate = stations[ref] ?: throw ToolException("POLICY_REJECTED", "站点不在候选列表。")
        demand(checked.integer("validUntil") > System.currentTimeMillis() && checked["available"] == bool(true) &&
            checked.integer("queueMinutes") <= candidate.integer("maxQueueMinutes"), "站点复核已过期、不可用或排队超限。")
        val current = energy ?: throw ToolException("POLICY_REJECTED", "请先读取续航。")
        demand(candidate.number("distanceKm") + current.number("reserveKm") <= current.number("estimatedRangeKm"), "估算续航不满足到站及预留要求。")
    }
    fun after(name: String, args: JsonObject, data: JsonObject) {
        when (name) {
            "profile_resolve_place" -> destinations += data.text("destinationRef")
            "navigation_plan_route" -> {
                val route = data.text("routeId")
                routes[route] = data.integer("expiresAt")
                routeDistances[route] = data.number("distanceMeters") / 1000
                routeDestinations[route] = args.text("destinationRef")
            }
            "energy_get_state" -> energy = data
            "charging_search" -> data.getValue("items").jsonArray.forEach {
                val candidate = it.jsonObject
                demand(candidate.number("powerKw") >= args.number("minPowerKw") && candidate.integer("detourMinutes") <= args.integer("maxDetourMinutes") && candidate.integer("queueMinutes") <= args.integer("maxQueueMinutes"), "搜索结果违反约束。")
                stations[candidate.text("stationRef")] = JsonObject(candidate + ("maxQueueMinutes" to args.getValue("maxQueueMinutes")))
            }
            "charging_check_station" -> {
                demand(data["stationRef"] == args["stationRef"] && data["destinationRef"] == args["stationRef"], "站点引用不匹配。")
                checkedStations[data.text("stationRef")] = data
                if (data["available"] == bool(true)) destinations += data.text("destinationRef")
            }
            "media_search" -> data.getValue("items").jsonArray.map { it.jsonObject }.filter {
                it["playable"] == bool(true) && it["requiresPurchase"] == bool(false)
            }.forEach { playable += it.text("contentRef") }
            "navigation_start", "climate_set_temperature", "media_play" -> receipts[name] = data
            "climate_get_state" -> {
                demand(data["zone"] == str(zone) && data["zone"] == args["zone"], "空调返回区域不匹配。")
                climate = data
                val expected = pending["climate_set_temperature"]
                val receipt = receipts["climate_set_temperature"]
                if (expected != null && data.number("targetTemperatureC") == expected.number("targetTemperatureC") &&
                    data.integer("stateVersion") == (receipt?.integer("stateVersion") ?: (expected.integer("expectedStateVersion") + 1)))
                    pending.remove("climate_set_temperature")
            }
            "navigation_get_state" -> {
                val expected = pending["navigation_start"]
                val receipt = receipts["navigation_start"]
                if (expected != null && data["status"] == str("active") && data["routeId"] == expected["routeId"] &&
                    (receipt == null || data["navigationSessionId"] == receipt["navigationSessionId"])) pending.remove("navigation_start")
            }
            "media_get_state" -> {
                val expected = pending["media_play"]
                val receipt = receipts["media_play"]
                if (expected != null && data["status"] == str("playing") && data["contentRef"] == expected["contentRef"] &&
                    (receipt == null || data["playbackSessionId"] == receipt["playbackSessionId"])) pending.remove("media_play")
            }
        }
    }
}
