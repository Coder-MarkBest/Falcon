package com.falcon.demo

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.falcon.ipc.Falcon
import com.falcon.ipc.getServiceSuspending
import com.falcon.ipc.protocol.IpcResult
import com.falcon.ipc.runtime.callSafe
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client UI. Runs in the main process and talks to [DemoServiceImpl] living in
 * the `:server` process. Each button demonstrates one Falcon usage pattern.
 */
class DemoActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private val buttons = mutableListOf<Button>()
    private var clockJob: Job? = null

    // Resolved ONCE on a background thread and cached. getService() internally does
    // runBlocking { withTimeout(...) } — calling it on the main thread would block
    // the UI (ANR/freeze). All handlers use this cached proxy instead.
    @Volatile private var demo: IDemoService? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        appendLog("Discovering :server process…")
        // Discovery is asynchronous (start :server → register → ContentObserver
        // → connect). Poll getService() until the proxy is available.
        waitForServiceThenEnable()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun addButton(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                isEnabled = false
                setOnClickListener { action() }
            }
            buttons.add(b)
            root.addView(b)
        }

        addButton("1 ▸ @IpcMethod  ping()") { demoPing() }
        addButton("2 ▸ @IpcMethod  add(2,3)") { demoAdd() }
        addButton("3 ▸ @IpcMethod  getUser() [Parcelable]") { demoGetUser() }
        addButton("4 ▸ callSafe { ping() } [typed result]") { demoCallSafe() }
        addButton("5 ▸ @IpcEvent  clock()  ▶ start/stop") { demoClockToggle() }
        addButton("6 ▸ @IpcStream  download()") { demoDownload() }
        addButton("7 ▸ @IpcCallback  loadAsync()") { demoCallback() }
        addButton("✕ clear log") { log.text = "" }

        log = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(log)

        return ScrollView(this).apply { addView(root) }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            log.append("[${timeFmt.format(Date())}] $line\n")
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        runOnUiThread { buttons.forEach { it.isEnabled = enabled } }
    }

    // ───────────────────────── Discovery ─────────────────────────

    private fun waitForServiceThenEnable() {
        lifecycleScope.launch(Dispatchers.IO) {
            repeat(50) {                     // up to ~10s
                // Non-blocking discovery from a coroutine.
                val svc = Falcon.getInstance().getServiceSuspending<IDemoService>()
                if (svc != null) {
                    demo = svc
                    appendLog("✓ Connected to :server. Tap a button.")
                    setButtonsEnabled(true)
                    return@launch
                }
                delay(200)
            }
            appendLog("✗ Could not reach :server within 10s. Check logcat 'Falcon'.")
        }
    }

    /** Returns the cached proxy — never blocks, safe to call on the main thread. */
    private fun service(): IDemoService? = demo

    // ───────────────────────── Demos ─────────────────────────

    /** 1. Plain request/response. */
    private fun demoPing() = lifecycleScope.launch(Dispatchers.IO) {
        val svc = service() ?: return@launch appendLog("service unavailable")
        appendLog("ping(\"hello\") → \"${svc.ping("hello")}\"")
    }

    /** 2. Multiple primitive args. */
    private fun demoAdd() = lifecycleScope.launch(Dispatchers.IO) {
        val svc = service() ?: return@launch appendLog("service unavailable")
        appendLog("add(2, 3) → ${svc.add(2, 3)}")
    }

    /** 3. Parcelable return. */
    private fun demoGetUser() = lifecycleScope.launch(Dispatchers.IO) {
        val svc = service() ?: return@launch appendLog("service unavailable")
        val u = svc.getUser(42)
        appendLog("getUser(42) → id=${u.id} name=${u.name} vip=${u.vip}")
    }

    /** 4. Typed error handling via callSafe — never throws. */
    private fun demoCallSafe() = lifecycleScope.launch(Dispatchers.IO) {
        when (val r = Falcon.getInstance().callSafe<IDemoService, String> { it.ping("safe") }) {
            is IpcResult.Success            -> appendLog("callSafe → Success: ${r.data}")
            is IpcResult.Timeout            -> appendLog("callSafe → Timeout")
            is IpcResult.Failure            -> appendLog("callSafe → Failure[${r.code}]: ${r.message}")
            is IpcResult.ServiceUnavailable -> appendLog("callSafe → ServiceUnavailable")
        }
    }

    /** 5. Pub/sub event stream — collect a Flow across processes. */
    private fun demoClockToggle() {
        val running = clockJob?.isActive == true
        if (running) {
            clockJob?.cancel()
            clockJob = null
            appendLog("clock() subscription cancelled")
            return
        }
        val svc = service() ?: return appendLog("service unavailable")
        appendLog("clock() subscribed — tick every 1s")
        clockJob = lifecycleScope.launch(Dispatchers.IO) {
            svc.clock()
                .catch { appendLog("clock() error: ${it.message}") }
                .collect { tick -> appendLog("  clock tick = $tick") }
        }
    }

    /** 6. Chunked byte stream. Streams are hot subscriptions — bound with take()
     *  (or cancel) since server-side completion does not cross the process boundary. */
    private fun demoDownload() = lifecycleScope.launch(Dispatchers.IO) {
        val svc = service() ?: return@launch appendLog("service unavailable")
        appendLog("download() streaming…")
        var total = 0
        svc.download()
            .take(5)
            .catch { appendLog("download() error: ${it.message}") }
            .collect { chunk ->
                total += chunk.size
                appendLog("  chunk ${chunk.size}B (total ${total}B)")
            }
        appendLog("download() complete: $total bytes")
    }

    /** 7. Async callback — fire and forget, result via IpcReply.
     *  The dispatch itself is a synchronous Binder call, so run it off the main thread. */
    private fun demoCallback() = lifecycleScope.launch(Dispatchers.IO) {
        val svc = service() ?: return@launch appendLog("service unavailable")
        appendLog("loadAsync(7) dispatched…")
        svc.loadAsync(7, object : IpcReply<String> {
            override fun onResult(data: String) = appendLog("  callback onResult: $data")
            override fun onError(code: Int, message: String) =
                appendLog("  callback onError[$code]: $message")
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        clockJob?.cancel()
    }
}
