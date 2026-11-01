package com.falcon.mcp.gateway

import android.app.Application
import android.content.Context
import android.util.Base64
import com.falcon.capability.*
import com.falcon.ipc.Falcon
import com.falcon.ipc.util.FalconLogger
import java.security.SecureRandom

class GatewayApp : Application() {
    var startupError: String? = null
        private set
    private var server: McpHttpServer? = null
    override fun onCreate() {
        super.onCreate()
        val falcon = Falcon.init(this) {
            peerPackages("com.falcon.cross.server", "com.falcon.comfort.provider")
            security {
                trustedSignatures = setOf("223b61dec24a63fdc836a01523ce6fb3bed67b32350fdd03c9271a7c4b5d58b1")
                rateLimitPerSecond = 50; maxConcurrentCalls = 8
            }
            timeout { connectMs = 5000 }
            transport { invokeTimeoutMs = 8000 }
        }
        val grants = mapOf(
            "com.falcon.cross.server" to ProviderGrant("", setOf("vehicle.read", "vehicle.write"), setOf(ToolProtocol.READ, ToolProtocol.VEHICLE)),
            "com.falcon.comfort.provider" to ProviderGrant("comfort_", setOf("comfort.read"), setOf(ToolProtocol.READ))
        )
        val principal = GatewayPrincipal("demo_driver", "driver", grants.values.flatMap { it.scopes }.toSet())
        val dispatcher = McpDispatcher(DiscoveredToolCatalog(falcon, grants), principal)
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
