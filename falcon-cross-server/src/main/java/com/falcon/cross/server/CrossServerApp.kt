package com.falcon.cross.server

import android.app.Application
import android.util.Log
import com.falcon.cross.shared.ICrossService
import com.falcon.mcp.adapters.DemoVehicleRoutes
import com.falcon.ipc.Falcon
import com.falcon.ipc.dynamic.DynamicPayload
import com.falcon.ipc.dynamic.DynamicResponse
import com.falcon.ipc.generated.SharedFalconGeneratedRegistry
import com.falcon.ipc.register
import com.falcon.ipc.util.ProcessUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Headless server APK — simulates a vehicle data gateway signed by the
 * vehicle manufacturer. Only whitelisted client signatures are admitted.
 *
 * ## Signing
 * Signed with `cross-server.keystore` (simulates "Vehicle OEM" certificate).
 *
 * ## Security (production-realistic demo)
 * - `signatureVerification = true` (the default, explicit for clarity)
 * - `trustedSignatures` whitelists the cross-client's certificate SHA-256,
 *   modelling a real multi-vendor trust relationship.
 * - Rate limits are relaxed (0 = unlimited) for demo responsiveness.
 */
class CrossServerApp : Application() {

    private val dynamicScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /** SHA-256 fingerprint of cross-client.keystore (alias=cross-client),
         *  normalized to lowercase hex without colons (matching SignatureGuard format). */
        private const val CLIENT_CERT_SHA256 =
            "69d02e8504beddac7638ee5a7e2ab059a1903f1ba6c4dfc92f8513a9849031e4"
    }

    override fun onCreate() {
        super.onCreate()
        val processName = ProcessUtils.getCurrentProcessName(this)
        Log.i("CrossServer", "onCreate in $processName")
        val falcon = Falcon.init(this) {
            generated(SharedFalconGeneratedRegistry)
            security {
                signatureVerification = true
                trustedSignatures = setOf(CLIENT_CERT_SHA256)
                rateLimitPerSecond = 0      // demo: unlimited
                maxConcurrentCalls = 0       // demo: unlimited
            }
            transport { binderPoolSize = 4; maxBinderPayloadSize = 256 * 1024 }
        }
        falcon.register(ICrossService::class, CrossServiceImpl())
        if (processName.endsWith(":server")) {
            val prefs = getSharedPreferences("agent-demo-vehicle", MODE_PRIVATE)
            val vehicle = com.falcon.mcp.adapters.DemoVehicle(prefs.getString("snapshot", null), persist = {
                check(prefs.edit().putString("snapshot", it).commit())
            })
            val provider = com.falcon.mcp.provider.FalconMcpProvider(falcon, "com.falcon.mcp.gateway")
            com.falcon.capability.VehicleTools.all.forEach { tool ->
                provider.registry.registerTool(com.falcon.capability.PublishedTool(tool.descriptor(),
                    if (tool.readOnly) "vehicle.read" else "vehicle.write",
                    if (tool.readOnly) "read.v1" else "vehicle.demo.v1")) { args, context -> vehicle.execute(tool.name, args, context) }
            }
        }

        // Dynamic RPC uses only platform wire types. Client and server do not share
        // an interface or model class for these routes and topics.
        for (version in 1..2) {
            falcon.route("vehicle/status/get", version) { request ->
                val vin = request.body.getString("vin").orEmpty()
                if (vin.isBlank()) {
                    DynamicResponse.error(400, "vin is required")
                } else {
                    DynamicResponse.success(DynamicPayload.build {
                        putString("vin", vin)
                        putInt("speedKmh", 88)
                        putBoolean("ignitionOn", true)
                        putInt("protocolVersion", request.version)
                        putString("serverProcess", processName)
                    })
                }
            }
        }
        falcon.topic("vehicle/telemetry", version = 1)

        if (processName.endsWith(":server")) {
            dynamicScope.launch {
                var sequence = 0L
                while (isActive) {
                    delay(500)
                    falcon.publish("vehicle/telemetry", DynamicPayload.build {
                        putLong("sequence", ++sequence)
                        putInt("speedKmh", 60 + (sequence % 40).toInt())
                        putBoolean("ignitionOn", true)
                    })
                }
            }
        }
        Log.i("CrossServer", "Typed service and Dynamic RPC registered — trusted clients: 1")
    }

    override fun onTerminate() {
        dynamicScope.cancel()
        super.onTerminate()
    }
}
