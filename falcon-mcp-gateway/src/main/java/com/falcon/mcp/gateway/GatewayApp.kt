package com.falcon.mcp.gateway

import android.app.Application
import android.content.Context
import android.util.Base64
import com.falcon.capability.VehicleTools
import com.falcon.ipc.Falcon
import com.falcon.ipc.dynamic.DynamicTarget
import com.falcon.ipc.util.FalconLogger
import com.falcon.mcp.adapters.FalconToolClient
import java.security.SecureRandom

class GatewayApp : Application() {
    var startupError: String? = null
        private set
    private var server: McpHttpServer? = null
    override fun onCreate() {
        super.onCreate()
        val falcon = Falcon.init(this) {
            peerPackages("com.falcon.cross.server")
            security {
                trustedSignatures = setOf("223b61dec24a63fdc836a01523ce6fb3bed67b32350fdd03c9271a7c4b5d58b1")
                rateLimitPerSecond = 50; maxConcurrentCalls = 8
            }
            transport { invokeTimeoutMs = 8000 }
        }
        val principal = GatewayPrincipal("demo_driver", "driver", VehicleTools.all.map { it.scope }.toSet())
        val dispatcher = McpDispatcher(FalconToolClient(falcon,
            DynamicTarget("com.falcon.cross.server", "com.falcon.cross.server:server")), principal)
        try {
            server = McpHttpServer(dispatcher, token(this)).also { it.start(5000, true) }
        } catch (e: Exception) {
            startupError = "MCP服务启动失败，请检查端口18765是否被占用。"
            FalconLogger.e("MCP", "Gateway listener failed to start")
        }
    }
    companion object {
        @Synchronized fun token(context: Context): String {
            val prefs = context.getSharedPreferences("mcp-credentials", Context.MODE_PRIVATE)
            return prefs.getString("token", null) ?: Base64.encodeToString(ByteArray(32).also {
                SecureRandom().nextBytes(it)
            }, Base64.NO_WRAP or Base64.URL_SAFE).also {
                check(prefs.edit().putString("token", it).commit())
            }
        }
    }
}
