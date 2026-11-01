package com.falcon.mcp.adapters

import com.falcon.capability.*
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.dynamic.*
import com.falcon.ipc.protocol.IpcException
import com.falcon.ipc.protocol.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

import com.falcon.mcp.provider.FalconJsonCodec

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
            if (e.errorCode == ErrorCode.PROVIDER_NOT_READY) throw ToolException("PROVIDER_NOT_READY", "服务尚未就绪，本次没有下发操作。")
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
