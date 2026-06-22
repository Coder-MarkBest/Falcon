package com.falcon.cross.server

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView

/**
 * Minimal launcher Activity so the headless server APK can be brought into the
 * foreground once. Android 8+ blocks [startService] / [startForegroundService]
 * from processes whose UID has never been visible. Launching this Activity
 * puts the UID in TOP state, which allows all processes under the same UID
 * to start services.
 *
 * When launched with `auto_finish=true`, this Activity is used as a warm-up
 * by the client app — it stays visible briefly to ensure the UID transitions
 * to TOP state, then finishes.
 */
class ServerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("CrossServer", "ServerActivity created — server process is now in foreground state")

        if (intent.getBooleanExtra("auto_finish", false)) {
            // Launched programmatically by the client to warm up the server.
            // Stay visible for 1s so the UID reliably enters TOP state before
            // the :server process is created and tries to start IpcHostService.
            Log.i("CrossServer", "Warm-up launch — will auto-finish in 5s")
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i("CrossServer", "Auto-finishing (warm-up)")
                finish()
            }, 5000L)
            return
        }

        // Interactive launch: show status
        val tv = TextView(this).apply {
            text = "Falcon Cross Server\n\nServer process is running.\nYou can now open the Client app."
            textSize = 18f
            setPadding(64, 64, 64, 64)
        }
        setContentView(tv)
    }
}
