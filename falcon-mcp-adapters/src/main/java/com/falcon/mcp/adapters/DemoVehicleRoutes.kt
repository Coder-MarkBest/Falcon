package com.falcon.mcp.adapters

import com.falcon.mcp.provider.FalconJsonCodec
import android.content.Context
import com.falcon.capability.*
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.dynamic.DynamicResponse

object DemoVehicleRoutes {
    const val GATEWAY_PACKAGE = "com.falcon.mcp.gateway"
    fun register(context: Context, falcon: FalconManager) {
        val prefs = context.getSharedPreferences("agent-demo-vehicle", Context.MODE_PRIVATE)
        val vehicle = DemoVehicle(prefs.getString("snapshot", null), persist = { snapshot ->
            check(prefs.edit().putString("snapshot", snapshot).commit()) { "Unable to persist demo operation" }
        })
        VehicleTools.all.forEach { tool ->
            falcon.route(tool.route, 1) { request ->
                if (request.callerPackage != GATEWAY_PACKAGE || request.callerProcess != GATEWAY_PACKAGE) {
                    DynamicResponse.error(403, "Gateway identity required")
                } else try {
                    val result = vehicle.execute(tool.name, FalconJsonCodec.decode(request.body), ToolContext(
                        request.headers.getString("actor").orEmpty(), request.headers.getString("zone").orEmpty(),
                        request.headers.getString("workflowId").orEmpty(), request.headers.getString("actionId").orEmpty()
                    ).also { require(it.workflowId.isNotBlank() && it.actionId.isNotBlank()) })
                    DynamicResponse.success(FalconJsonCodec.encode(result))
                } catch (e: ToolException) {
                    DynamicResponse(statusCode = 409, body = FalconJsonCodec.encode(obj("code" to str(e.code))))
                }
            }
        }
    }
}
