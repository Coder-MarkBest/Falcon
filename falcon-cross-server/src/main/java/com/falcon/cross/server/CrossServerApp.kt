package com.falcon.cross.server

import android.app.Application
import android.util.Log
import com.falcon.cross.shared.ICrossService
import com.falcon.ipc.Falcon
import com.falcon.ipc.generated.SharedFalconGeneratedRegistry
import com.falcon.ipc.register
import com.falcon.ipc.util.ProcessUtils

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

    companion object {
        /** SHA-256 fingerprint of cross-client.keystore (alias=cross-client),
         *  normalized to lowercase hex without colons (matching SignatureGuard format). */
        private const val CLIENT_CERT_SHA256 =
            "69d02e8504beddac7638ee5a7e2ab059a1903f1ba6c4dfc92f8513a9849031e4"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("CrossServer", "onCreate in ${ProcessUtils.getCurrentProcessName(this)}")
        Falcon.init(this) {
            generated(SharedFalconGeneratedRegistry)
            security {
                signatureVerification = true
                trustedSignatures = setOf(CLIENT_CERT_SHA256)
                rateLimitPerSecond = 0      // demo: unlimited
                maxConcurrentCalls = 0       // demo: unlimited
            }
            transport { binderPoolSize = 4; maxBinderPayloadSize = 256 * 1024 }
        }.register(ICrossService::class, CrossServiceImpl())
        Log.i("CrossServer", "ICrossService registered — trusted clients: 1")
    }
}
