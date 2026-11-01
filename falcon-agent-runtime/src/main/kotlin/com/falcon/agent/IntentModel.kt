package com.falcon.agent

import com.falcon.capability.*
import kotlinx.serialization.json.*

data class CabinIntent(val goHome: Boolean, val slightlyWarmer: Boolean, val relaxingMusic: Boolean)

interface IntentModel {
    suspend fun understand(utterance: String): CabinIntent
}

/** Offline fixture for the documented demo, not a general natural-language model. */
class MockIntentModel : IntentModel {
    override suspend fun understand(utterance: String): CabinIntent {
        val text = utterance.trim().lowercase()
        if (text.isBlank() || text.length > 1000 || listOf("不", "别", "取消", "停止").any { it in text }) {
            throw ToolException("NEEDS_CLARIFICATION", "Mock仅支持明确的正向演示指令，请重新输入。")
        }
        val intent = CabinIntent("回家" in text, listOf("有点冷", "太冷", "很冷", "暖一点").any { it in text },
            "音乐" in text && ("轻松" in text || "放松" in text))
        if (!intent.goHome && !intent.slightlyWarmer && !intent.relaxingMusic) {
            throw ToolException("UNSUPPORTED_INTENT", "Mock未识别该请求；可输入：我要回家，有点冷，放点轻松的音乐。")
        }
        return intent
    }
}

data class DeepSeekRequest(val system: String, val user: String)

/** Injectable provider transport; credentials stay outside the intent model. */
fun interface DeepSeekTransport {
    suspend fun complete(request: DeepSeekRequest): JsonObject
}

class DeepSeekIntentModel(private val transport: DeepSeekTransport) : IntentModel {
    override suspend fun understand(utterance: String): CabinIntent {
        require(utterance.isNotBlank() && utterance.length <= 1000)
        val result = transport.complete(DeepSeekRequest(
            "提取座舱意图，返回JSON对象，仅含三个布尔字段goHome、slightlyWarmer、relaxingMusic。" +
                "示例：我要回家，有点冷，放点轻松的音乐 => {\"goHome\":true,\"slightlyWarmer\":true,\"relaxingMusic\":true}。" +
                "否定、歧义或不支持的请求返回clarification字符串。不要生成工具调用、身份或权限。", utterance))
        if ("clarification" in result) throw ToolException("NEEDS_CLARIFICATION", "请求需要进一步确认。")
        val keys = setOf("goHome", "slightlyWarmer", "relaxingMusic")
        if (result.keys != keys || result.values.any { it !is JsonPrimitive || it.isString || it.booleanOrNull == null }) {
            throw ToolException("INVALID_MODEL_OUTPUT", "模型输出不符合意图契约。")
        }
        return CabinIntent(result.getValue("goHome").jsonPrimitive.boolean,
            result.getValue("slightlyWarmer").jsonPrimitive.boolean,
            result.getValue("relaxingMusic").jsonPrimitive.boolean)
    }
}
