package com.falcon.mcp.adapters

import com.falcon.capability.*
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.dynamic.*
import com.falcon.ipc.protocol.IpcException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** The JSON boundary is explicit. Arrays use an indexed Bundle, never business Parcelable. */
object FalconJsonCodec {
    private const val ARRAY_MARKER = "_falcon_json_array"
    fun encode(value: JsonObject, depth: Int = 0): DynamicPayload {
        require(depth <= 8 && value.size <= 64) { "Payload exceeds structural limits" }
        require(ARRAY_MARKER !in value) { "Reserved JSON key" }
        return DynamicPayload.build {
            value.forEach { (key, child) ->
                when (child) {
                    is JsonObject -> putPayload(key, encode(child, depth + 1))
                    is JsonArray -> {
                        require(child.size <= 64 && depth < 8)
                        putPayload(key, DynamicPayload.build {
                            putBoolean(ARRAY_MARKER, true)
                            putInt("size", child.size)
                            child.forEachIndexed { index, item ->
                                putPayload(index.toString(), encode(obj("value" to item), depth + 1))
                            }
                        })
                    }
                    is JsonPrimitive -> when {
                        child === JsonNull -> putString(key, null)
                        child.isString -> putString(key, child.content)
                        child.booleanOrNull != null -> putBoolean(key, child.boolean)
                        child.longOrNull != null -> putLong(key, child.long)
                        child.doubleOrNull?.isFinite() == true -> putDouble(key, child.double)
                        else -> error("Unsupported JSON primitive")
                    }
                }
            }
        }
    }
    fun decode(payload: DynamicPayload): JsonObject = decodeBundle(payload.toBundle(), 0)
    private fun decodeBundle(bundle: android.os.Bundle, depth: Int): JsonObject {
        require(depth <= 8 && bundle.size() <= 66)
        return buildJsonObject {
            bundle.keySet().forEach { key ->
                @Suppress("DEPRECATION") val raw = bundle.get(key)
                put(key, when (raw) {
                    null -> JsonNull
                    is String -> str(raw)
                    is Boolean -> bool(raw)
                    is Number -> num(raw)
                    is android.os.Bundle -> {
                        if (raw.getBoolean(ARRAY_MARKER, false)) {
                            val size = raw.getInt("size", -1)
                            require(size in 0..64 && raw.size() == size + 2)
                            JsonArray((0 until size).map { index ->
                                decodeBundle(requireNotNull(raw.getBundle(index.toString())), depth + 1).getValue("value")
                            })
                        } else decodeBundle(raw, depth + 1)
                    }
                    else -> error("Unsupported wire value")
                })
            }
        }
    }
}

class FalconToolClient(private val falcon: FalconManager, private val target: DynamicTarget) : ToolClient {
    override suspend fun call(name: String, arguments: JsonObject, context: ToolContext): JsonObject {
        val tool = VehicleTools.byName[name] ?: throw ToolException("UNKNOWN_TOOL", "未知工具。")
        val response = try {
            falcon.callTo(target, tool.route, setOf(1), DynamicPayload.build {
                putString("actor", context.userId)
                putString("zone", context.zone)
                putString("workflowId", context.workflowId)
                putString("actionId", context.actionId)
                putString("idempotencyKey", context.workflowId + ":" + context.actionId)
            }, FalconJsonCodec.encode(arguments))
        } catch (e: CancellationException) { throw e
        } catch (e: IpcException) {
            throw ToolException("TRANSPORT_ERROR", "Falcon调用未获得可确认结果。")
        }
        val data = FalconJsonCodec.decode(response.body)
        if (!response.isSuccessful) throw ToolException(
            (data["code"] as? JsonPrimitive)?.content ?: "PROVIDER_ERROR",
            "提供方拒绝或未完成操作。"
        )
        try { tool.output.validate(data, "result") }
        catch (e: ToolException) { throw ToolException("INVALID_PROVIDER_OUTPUT", "提供方结果不符合契约。") }
        return data
    }
}
