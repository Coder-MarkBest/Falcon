package com.falcon.agent

import com.falcon.capability.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.math.round

data class AgentSession(val userId: String, val speakerZone: String)
enum class BranchStatus { SUCCEEDED, FAILED, NEEDS_INPUT, UNKNOWN }
data class BranchResult(val branch: String, val status: BranchStatus, val message: String)
data class JourneyResult(val workflowId: String, val branches: List<BranchResult>) {
    val summary: String get() = branches.joinToString("；") { it.message }
}

/** Implementations must serialize concurrent branch writes. Store no credentials here. */
fun interface JourneyJournal {
    fun record(workflowId: String, event: String)
}

/** First-version architecture: the runtime composes atomic MCP tools. */
class HomeJourneyAgent(
    private val model: IntentModel,
    private val tools: ToolClient,
    private val journal: JourneyJournal = JourneyJournal { _, _ -> }
) {
    suspend fun run(utterance: String, session: AgentSession): JourneyResult {
        if (session.userId.isBlank() || session.speakerZone !in setOf("driver", "front_passenger")) {
            throw ToolException("NEEDS_CLARIFICATION", "请先确认当前用户和发话座位。")
        }
        val intent = model.understand(utterance)
        if (!intent.goHome && !intent.slightlyWarmer && !intent.relaxingMusic) {
            throw ToolException("UNSUPPORTED_INTENT", "请求未包含当前支持的场景。")
        }
        val workflow = UUID.randomUUID().toString()
        journal.record(workflow, "STARTED")
        suspend fun call(name: String, args: JsonObject = obj(), action: String = name): JsonObject {
            journal.record(workflow, "CALL:$action")
            val result = tools.call(name, args, ToolContext(session.userId, session.speakerZone, workflow, action))
            journal.record(workflow, "RETURNED:$action")
            return result
        }
        suspend fun completed(receipt: JsonObject) {
            when (receipt.text("status")) {
                "succeeded" -> return
                "accepted" -> repeat(4) { index ->
                    val state = call("operation_get", obj("operationRef" to receipt.getValue("operationRef")), "verify_operation_$index")
                    when (state.text("status")) {
                        "succeeded" -> return
                        "failed", "cancelled" -> throw ToolException("EXECUTION_FAILED", "操作未完成。")
                        "unknown" -> throw ToolException("RESULT_UNKNOWN", "执行结果待确认。")
                    }
                    delay(250)
                }
                else -> throw ToolException("RESULT_UNKNOWN", "执行结果待确认。")
            }
            throw ToolException("RESULT_UNKNOWN", "执行结果待确认。")
        }
        suspend fun branch(name: String, block: suspend () -> String): BranchResult {
            journal.record(workflow, "$name:STARTED")
            val result = try {
                BranchResult(name, BranchStatus.SUCCEEDED, withTimeout(20_000) { block() })
            } catch (e: TimeoutCancellationException) {
                BranchResult(name, BranchStatus.UNKNOWN, "$name：处理超时，结果待确认")
            } catch (e: CancellationException) {
                journal.record(workflow, "$name:CANCEL_REQUESTED"); throw e
            } catch (e: ToolException) {
                val status = when (e.code) {
                    "HOME_NOT_SET", "NEEDS_CLARIFICATION" -> BranchStatus.NEEDS_INPUT
                    "RESULT_UNKNOWN", "TRANSPORT_ERROR" -> BranchStatus.UNKNOWN
                    else -> BranchStatus.FAILED
                }
                BranchResult(name, status, when (e.code) {
                    "HOME_NOT_SET" -> "尚未保存家庭目的地，请先设置家的位置"
                    "RESULT_UNKNOWN", "TRANSPORT_ERROR" -> "$name：结果待确认，未自动重试"
                    else -> "$name：未完成（${e.code}）"
                })
            } catch (e: Exception) {
                BranchResult(name, BranchStatus.UNKNOWN, "$name：结果待确认")
            }
            journal.record(workflow, "$name:${result.status}")
            return result
        }
        return try {
            val results = supervisorScope {
                buildList<Deferred<BranchResult>> {
                    if (intent.goHome) add(async { branch("导航") {
                        val place = call("profile_resolve_place", obj("label" to str("home")))
                        val route = call("navigation_plan_route", obj("destinationRef" to place.getValue("destinationRef")))
                        val started = call("navigation_start", obj("routeId" to route.getValue("routeId")))
                        completed(started)
                        val state = call("navigation_get_state")
                        if (state.text("status") != "active" || state["routeId"] != route["routeId"] ||
                            state["navigationSessionId"] != started["navigationSessionId"]) {
                            throw ToolException("RESULT_UNKNOWN", "导航状态尚未确认。")
                        }
                        "已开始导航回家"
                    } })
                    if (intent.slightlyWarmer) add(async { branch("空调") {
                        val state = call("climate_get_state", obj("zone" to str(session.speakerZone)), "climate_read")
                        val current = state.number("targetTemperatureC")
                        val step = state.number("stepC")
                        val min = state.number("minC"); val max = state.number("maxC")
                        if (step <= 0 || min > max || current !in min..max) throw ToolException("INVALID_STATE", "空调状态无效。")
                        val target = (min + round((current + 1.0 - min) / step) * step).coerceIn(min, max)
                        if (target <= current) {
                            "空调目标温度已达可调上限，未继续调高"
                        } else {
                            val receipt = call("climate_set_temperature", obj("zone" to str(session.speakerZone),
                                "targetTemperatureC" to num(target), "expectedStateVersion" to state.getValue("stateVersion")), "climate_set")
                            completed(receipt)
                            val actual = call("climate_get_state", obj("zone" to str(session.speakerZone)), "climate_verify")
                            if (actual.number("targetTemperatureC") != target || actual["stateVersion"] != receipt["stateVersion"]) {
                                throw ToolException("RESULT_UNKNOWN", "空调状态已变化。")
                            }
                            val zone = if (session.speakerZone == "driver") "主驾" else "副驾"
                            "${zone}空调目标温度已调到${target}℃"
                        }
                    } })
                    if (intent.relaxingMusic) add(async { branch("音乐") {
                        val results = call("media_search", obj("mood" to str("relaxing"), "limit" to num(3)))
                        val item = results.getValue("items").jsonArray.map { it.jsonObject }.firstOrNull {
                            it["playable"] == bool(true) && it["requiresPurchase"] == bool(false)
                        } ?: throw ToolException("NO_PLAYABLE_CONTENT", "没有可直接播放的音乐。")
                        val playing = call("media_play", obj("contentRef" to item.getValue("contentRef")))
                        completed(playing)
                        val state = call("media_get_state")
                        if (state.text("status") != "playing" || state["contentRef"] != item["contentRef"] ||
                            state["playbackSessionId"] != playing["playbackSessionId"]) {
                            throw ToolException("RESULT_UNKNOWN", "播放状态尚未确认。")
                        }
                        "正在播放轻松音乐"
                    } })
                }.awaitAll()
            }
            journal.record(workflow, "FINISHED")
            JourneyResult(workflow, results)
        } catch (e: CancellationException) {
            journal.record(workflow, "INTERRUPTED_RECONCILIATION_REQUIRED"); throw e
        }
    }
}
