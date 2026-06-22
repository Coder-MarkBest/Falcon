package com.falcon.demo

import android.app.Application
import com.falcon.ipc.Falcon
import com.falcon.ipc.generated.DemoFalconGeneratedRegistry
import com.falcon.ipc.register
import com.falcon.ipc.util.ProcessUtils

/**
 * Falcon must be initialised in **every** process that participates in IPC.
 *
 * - In the `:server` process we init AND register the service implementation.
 * - In the main (client) process we only init — discovery + proxy creation
 *   happen on demand in [DemoActivity].
 *
 * The generated registry object [DemoFalconGeneratedRegistry] is produced by
 * KSP from [IDemoService]; both sides must pass it to `generated(...)`.
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val processName = ProcessUtils.getCurrentProcessName(this)

        if (processName.endsWith(":server")) {
            // ───────── Server process ─────────
            val falcon = Falcon.init(this) {
                generated(DemoFalconGeneratedRegistry)
                security {
                    // Relaxed for the demo. In production keep signature
                    // verification on (default) and set trustedSignatures.
                    rateLimitPerSecond = 0      // 0 = unlimited
                    maxConcurrentCalls = 0      // 0 = unlimited
                }
            }
            falcon.register(IDemoService::class, DemoServiceImpl())
        } else {
            // ───────── Client (main) process ─────────
            Falcon.init(this) {
                generated(DemoFalconGeneratedRegistry)
            }
        }
    }
}
