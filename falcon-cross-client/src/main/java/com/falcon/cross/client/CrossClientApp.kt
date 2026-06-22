package com.falcon.cross.client

import android.app.Application
import android.content.Intent
import android.util.Log
import com.falcon.ipc.Falcon
import com.falcon.ipc.generated.SharedFalconGeneratedRegistry
import com.falcon.ipc.util.ProcessUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Client APK — simulates an IVI/infotainment app signed by the IVI system
 * vendor. It discovers the cross-server APK and calls its services.
 *
 * ## Signing
 * Signed with `cross-client.keystore` (simulates "IVI Vendor" certificate).
 *
 * ## Security (production-realistic demo)
 * - `signatureVerification = true` so the client also verifies the server
 *   it's talking to (mutual trust).
 * - `trustedSignatures` whitelists the cross-server's certificate SHA-256.
 */
class CrossClientApp : Application() {

    companion object {
        /** SHA-256 fingerprint of cross-server.keystore (alias=cross-server),
         *  normalized to lowercase hex without colons (matching SignatureGuard format). */
        private const val SERVER_CERT_SHA256 =
            "223b61dec24a63fdc836a01523ce6fb3bed67b32350fdd03c9271a7c4b5d58b1"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("CrossClient", "onCreate in ${ProcessUtils.getCurrentProcessName(this)}")

        // Warm up the headless server BEFORE Falcon.init(). The client's PeerManager
        // starts observing the server's registry URI during init(), and the very first
        // query triggers creation of the server's :server process. That process must
        // be able to start IpcHostService (via startService), which Android 8+ blocks
        // unless the server's UID is in TOP (foreground) state.
        //
        // Launching the server's minimal Activity puts its UID in TOP state so
        // FalconManager.start() → startService(IpcHostService) succeeds.
        //
        // runBlocking with a short delay on the main thread is acceptable here:
        // Android allows ~5s before ANR in Application.onCreate(), and this
        // is a demo app — production apps would use a different approach.
        warmUpServer()

        Falcon.init(this) {
            generated(SharedFalconGeneratedRegistry)
            peerPackages("com.falcon.cross.server")
            security {
                signatureVerification = true
                trustedSignatures = setOf(SERVER_CERT_SHA256)
                rateLimitPerSecond = 0
                maxConcurrentCalls = 0
            }
        }
        Log.i("CrossClient", "Falcon initialized — discovering com.falcon.cross.server")
    }

    /** Launch the server's warm-up Activity and wait for it to enter TOP state.
     *  This ensures the server UID is foregrounded before our PeerManager
     *  triggers creation of the :server process. */
    private fun warmUpServer() {
        try {
            val intent = Intent().apply {
                setClassName("com.falcon.cross.server", "com.falcon.cross.server.ServerActivity")
                putExtra("auto_finish", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            // Block briefly so the Activity has time to start and push the UID
            // into TOP state. ServerActivity stays visible for several seconds
            // (auto-finish with postDelayed) so the UID remains TOP throughout
            // PeerManager discovery.
            runBlocking { delay(3000) }
            Log.i("CrossClient", "Server warm-up complete")
        } catch (e: Exception) {
            Log.w("CrossClient", "Server warm-up failed: ${e.message}")
        }
    }
}
