package com.falcon.comfort.provider

import android.app.Application
import com.falcon.capability.*
import com.falcon.ipc.Falcon
import com.falcon.mcp.provider.FalconMcpProvider
import kotlinx.serialization.json.*

class ComfortApp : Application() {
    lateinit var provider: FalconMcpProvider
        private set
    override fun onCreate() {
        super.onCreate()
        val falcon = Falcon.init(this) {
            security { trustedSignatures = setOf("69d02e8504beddac7638ee5a7e2ab059a1903f1ba6c4dfc92f8513a9849031e4") }
        }
        provider = FalconMcpProvider(falcon, "com.falcon.mcp.gateway")
        if (getSharedPreferences("tools", MODE_PRIVATE).getBoolean("enabled", false)) register()
    }
    fun register() {
        val input = Field("object", "查询当前座舱区域", properties = mapOf("zone" to Field("string", "授权座位", setOf("driver", "front_passenger"))))
        val output = Field("object", "模拟空气质量", properties = mapOf(
            "zone" to Field("string", "区域"), "co2Ppm" to Field("integer", "二氧化碳浓度ppm"),
            "pm25" to Field("integer", "PM2.5微克每立方米"), "advice" to Field("string", "通风建议")))
        val descriptor = obj("name" to str("air_quality"),
            "description" to str("读取指定座位的模拟空气质量，返回CO2、PM2.5和通风建议。用户询问空气闷不闷、二氧化碳或空气质量时调用；只查询，不调整空调或车窗。"),
            "inputSchema" to input.schema(), "outputSchema" to output.schema(),
            "annotations" to obj("readOnlyHint" to bool(true), "idempotentHint" to bool(true), "destructiveHint" to bool(false), "openWorldHint" to bool(false)))
        provider.registry.registerTool(PublishedTool(descriptor, "comfort.read", ToolProtocol.READ)) { args, context ->
            if (context.userId != "demo_driver" || args.text("zone") != context.zone) throw ToolException("FORBIDDEN", "座位未授权")
            val prefs = getSharedPreferences("tools", MODE_PRIVATE)
            check(prefs.edit().putInt("calls", prefs.getInt("calls", 0) + 1).commit())
            obj("zone" to args.getValue("zone"), "co2Ppm" to num(1450), "pm25" to num(12),
                "advice" to str("模拟CO2偏高，可考虑通风；本次未执行通风操作。"))
        }
        check(getSharedPreferences("tools", MODE_PRIVATE).edit().putBoolean("enabled", true).commit())
    }
    fun unregister() {
        provider.registry.unregisterTool("air_quality")
        check(getSharedPreferences("tools", MODE_PRIVATE).edit().putBoolean("enabled", false).commit())
    }
}
