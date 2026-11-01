package com.falcon.agent

import com.falcon.capability.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

/** Credentials are supplied at runtime. Never logs headers, response bodies or exceptions containing them. */
class DeepSeekHttpTransport(private val apiKey: () -> String) : DeepSeekTransport {
    override suspend fun complete(request: DeepSeekRequest): JsonObject = decode(post(requestBody(request)))
    suspend fun chat(body: JsonObject): JsonObject {
        return try { Json.parseToJsonElement(post(body)).jsonObject.getValue("choices").jsonArray.single().jsonObject } catch (e: ToolException) { throw e }
        catch (e: Exception) { throw ToolException("INVALID_MODEL_OUTPUT", "模型响应格式错误。") }
    }
    private suspend fun post(requestBody: JsonObject): String = withContext(Dispatchers.IO) {
        val key = apiKey().trim()
        if (key.isBlank()) throw ToolException("MODEL_NOT_CONFIGURED", "请先配置DeepSeek Key。")
        val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = requestBody.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status != 200) throw httpError(status)
            val body = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 65536) {
                    ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, 65537 - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                if (out.size() > 65536) throw ToolException("INVALID_MODEL_OUTPUT", "模型响应过大。")
                out.toString("UTF-8")
            }
            body
        } catch (e: CancellationException) { throw e
        } catch (e: ToolException) { throw e
        } catch (e: Exception) { throw ToolException("MODEL_NETWORK_ERROR", "DeepSeek连接失败或超时，请检查网络。")
        } finally { connection.disconnect() }
    }
    companion object {
        const val MODEL = "deepseek-flash"
        internal fun requestBody(request: DeepSeekRequest) = obj(
            "model" to str(MODEL), "stream" to bool(false), "max_tokens" to num(512),
            "thinking" to obj("type" to str("disabled")),
            "response_format" to obj("type" to str("json_object")),
            "messages" to JsonArray(listOf(
                obj("role" to str("system"), "content" to str(request.system)),
                obj("role" to str("user"), "content" to str(request.user))
            ))
        )
        internal fun httpError(status: Int): ToolException = when (status) {
            401, 403 -> ToolException("MODEL_AUTH_ERROR", "DeepSeek认证失败，请检查Key权限。")
            402 -> ToolException("MODEL_BALANCE_ERROR", "DeepSeek账户余额不足。")
            429 -> ToolException("MODEL_RATE_LIMITED", "DeepSeek请求受限，请稍后再试。")
            else -> ToolException("MODEL_API_ERROR", "DeepSeek服务未完成请求（HTTP $status）。")
        }
        internal fun decode(body: String): JsonObject = try {
            val choice = Json.parseToJsonElement(body).jsonObject.getValue("choices").jsonArray.single().jsonObject
            if (choice["finish_reason"] != str("stop")) throw IllegalArgumentException()
            val message = choice.getValue("message").jsonObject
            if ("tool_calls" in message && message["tool_calls"] != JsonNull) throw IllegalArgumentException()
            val content = message.getValue("content").jsonPrimitive
            if (!content.isString || content.content.isBlank()) throw IllegalArgumentException()
            Json.parseToJsonElement(content.content).jsonObject
        } catch (e: Exception) {
            throw ToolException("INVALID_MODEL_OUTPUT", "DeepSeek未返回完整、有效的JSON意图。")
        }
    }
}
