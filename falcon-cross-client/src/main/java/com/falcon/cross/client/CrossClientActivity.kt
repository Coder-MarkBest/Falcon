package com.falcon.cross.client

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.falcon.cross.shared.ICrossService
import com.falcon.cross.shared.VehicleData
import com.falcon.ipc.Falcon
import com.falcon.ipc.core.IpcState
import com.falcon.ipc.getServiceSuspending
import com.falcon.ipc.protocol.IpcResult
import com.falcon.ipc.runtime.callSafe
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cross-app client UI. Runs in com.falcon.cross.client APK and talks to
 * [com.falcon.cross.server.CrossServiceImpl] in the separately-installed
 * cross-server APK (com.falcon.cross.server).
 *
 * Each button demonstrates one Falcon IPC pattern across independent APKs.
 */
class CrossClientActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private val buttons = mutableListOf<Button>()
    private var telemetryJob: Job? = null
    private var speedAlertsJob: Job? = null

    @Volatile private var service: ICrossService? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        // Monitor connection state changes
        Falcon.getInstance().onConnectionStateChanged { state, processName ->
            appendLog("[CONNECTION] $processName → $state")
        }

        appendLog("Discovering cross-server APK (com.falcon.cross.server)...")
        waitForServiceThenEnable()
    }

    // ── UI ─────────────────────────────────────────────────────────────────

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun addButton(label: String, action: () -> Unit) {
            val b = Button(this).apply { text = label; isEnabled = false; setOnClickListener { action() } }
            buttons.add(b); root.addView(b)
        }

        addButton("1 ▸ @IpcMethod  ping()")              { demoPing() }
        addButton("2 ▸ @IpcMethod  add(2,3)")            { demoAdd() }
        addButton("3 ▸ @IpcMethod  getVehicleData() [Parcelable]") { demoGetVehicleData() }
        addButton("4 ▸ @IpcMethod  filterWarnings() [List]")       { demoFilterWarnings() }
        addButton("5 ▸ @IpcMethod  getDoorStates() [Map]")         { demoDoorStates() }
        addButton("6 ▸ @IpcMethod  getTirePressure() [Map?]")      { demoTirePressure() }
        addButton("7 ▸ @IpcMethod  getBatchData() [CrossData]")    { demoBatchData() }
        addButton("8 ▸ @IpcMethod  batchProcess() [List→Map]")     { demoBatchProcess() }
        addButton("9 ▸ @IpcMethod  findById() [VehicleData?]")     { demoFindById() }
        addButton("10 ▸ @IpcMethod maybePing() [String?]")         { demoMaybePing() }
        addButton("11 ▸ callSafe { ping() } [typed result]")       { demoCallSafe() }
        addButton("12 ▸ @IpcEvent  vehicleTelemetry()  ▶ start/stop") { demoTelemetryToggle() }
        addButton("13 ▸ @IpcEvent  speedAlerts()  ▶ start/stop")   { demoSpeedAlertsToggle() }
        addButton("14 ▸ @IpcStream firmwareChunks()")              { demoFirmwareChunks() }
        addButton("15 ▸ @IpcCallback slowLookup()")                { demoSlowLookup() }
        addButton("16 ▸ @IpcCallback validateVehicle() [error]")   { demoValidateVehicle() }
        addButton("17 ▸ ⏱ Benchmark cross-app latency")            { demoBenchmark() }
        addButton("18 ▸ 💥 Stress test (concurrent load)")          { demoStress() }
        addButton("✕ clear log") { log.text = "" }

        log = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f
            setTextIsSelectable(true); movementMethod = ScrollingMovementMethod()
        }
        root.addView(log)
        return ScrollView(this).apply { addView(root) }
    }

    private fun appendLog(line: String) {
        // Mirror to logcat so the demo can be verified headlessly (adb logcat -s CrossClient).
        android.util.Log.i("CrossClient", line)
        runOnUiThread { log.append("[${timeFmt.format(Date())}] $line\n") }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        runOnUiThread { buttons.forEach { it.isEnabled = enabled } }
    }

    // ── Discovery ──────────────────────────────────────────────────────────

    private fun waitForServiceThenEnable() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Server warm-up was already done in CrossClientApp.onCreate()
            // before Falcon.init(), so discovery can start immediately.
            repeat(50) {
                val svc = Falcon.getInstance().getServiceSuspending<ICrossService>()
                if (svc != null) {
                    service = svc
                    appendLog("✓ Connected to cross-server. Tap a button.")
                    setButtonsEnabled(true)
                    return@launch
                }
                delay(200)
            }
            appendLog("✗ Could not reach cross-server within 10s.")
            appendLog("  Verify: 1) cross-server APK installed  2) <queries> declared")
        }
    }

    private fun svc(): ICrossService? = service

    // ── 1. ping ────────────────────────────────────────────────────────────

    private fun demoPing() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("ping(\"hello\") → \"${s.ping("hello")}\"")
    }

    // ── 2. add ─────────────────────────────────────────────────────────────

    private fun demoAdd() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("add(2, 3) → ${s.add(2, 3)}")
    }

    // ── 3. VehicleData (Parcelable) ────────────────────────────────────────

    private fun demoGetVehicleData() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        val v = s.getVehicleData("VIN12345")
        appendLog("getVehicleData() →")
        appendLog("  vin=${v.vin} speed=${v.speedKmh}km/h odo=${v.odometerKm}km")
        appendLog("  fuel=${v.fuelLevelPct}% temp=${v.engineTempC}°C")
        appendLog("  warnings=${v.warningFlags}")
        appendLog("  doors=${v.doorStates}")
        appendLog("  tires=${v.tirePressure}")
    }

    // ── 4. List ────────────────────────────────────────────────────────────

    private fun demoFilterWarnings() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        val input = listOf("active_engine", "info_service", "active_brakes", "low_fuel")
        val result = s.filterWarnings(input)
        appendLog("filterWarnings($input) → $result")
    }

    // ── 5. Map ─────────────────────────────────────────────────────────────

    private fun demoDoorStates() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("getDoorStates() → ${s.getDoorStates()}")
    }

    // ── 6. Nullable Map ────────────────────────────────────────────────────

    private fun demoTirePressure() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("getTirePressure(true) → ${s.getTirePressure(true)}")
        appendLog("getTirePressure(false) → ${s.getTirePressure(false)}  (null = TPMS not installed)")
    }

    // ── 7. CrossData (complex Parcelable) ─────────────────────────────────

    private fun demoBatchData() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        val d = s.getBatchData(7)
        appendLog("getBatchData(7) → id=${d.id} name=${d.name} tags=${d.tags} meta=${d.meta}")
    }

    // ── 8. List→Map ────────────────────────────────────────────────────────

    private fun demoBatchProcess() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        val r = s.batchProcess(1, listOf("abc", "de", "fghi", "jk"))
        appendLog("batchProcess(1, [abc,de,fghi,jk]) → $r")
    }

    // ── 9. Nullable Parcelable ─────────────────────────────────────────────

    private fun demoFindById() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        val found = s.findById("VIN12345")
        appendLog("findById(\"VIN12345\") → vin=${found?.vin} speed=${found?.speedKmh}")
        val missing = s.findById("AB")
        appendLog("findById(\"AB\") → $missing  (null = not found)")
    }

    // ── 10. Nullable primitive ─────────────────────────────────────────────

    private fun demoMaybePing() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("maybePing(\"hi\", false) → \"${s.maybePing("hi", false)}\"")
        appendLog("maybePing(\"hi\", true) → ${s.maybePing("hi", true)}  (null)")
    }

    // ── 11. callSafe ───────────────────────────────────────────────────────

    private fun demoCallSafe() = lifecycleScope.launch(Dispatchers.IO) {
        when (val r = Falcon.getInstance().callSafe<ICrossService, String> { it.ping("safe") }) {
            is IpcResult.Success            -> appendLog("callSafe → Success: ${r.data}")
            is IpcResult.Timeout            -> appendLog("callSafe → Timeout")
            is IpcResult.Failure            -> appendLog("callSafe → Failure[${r.code}]: ${r.message}")
            is IpcResult.ServiceUnavailable -> appendLog("callSafe → ServiceUnavailable")
        }
    }

    // ── 12. @IpcEvent — Vehicle telemetry ──────────────────────────────────

    private fun demoTelemetryToggle() {
        if (telemetryJob?.isActive == true) {
            telemetryJob?.cancel(); telemetryJob = null
            appendLog("vehicleTelemetry() unsubscribed")
            return
        }
        val s = svc() ?: return appendLog("service unavailable")
        appendLog("vehicleTelemetry() subscribed — snapshot every 500ms")
        telemetryJob = lifecycleScope.launch(Dispatchers.IO) {
            s.vehicleTelemetry()
                .take(8)
                .catch { appendLog("telemetry error: ${it.message}") }
                .collect { v -> appendLog("  telemetry: vin=${v.vin} speed=${v.speedKmh} fuel=${v.fuelLevelPct}%") }
            appendLog("vehicleTelemetry() done (take 8)")
        }
    }

    // ── 13. @IpcEvent — Speed alerts ───────────────────────────────────────

    private fun demoSpeedAlertsToggle() {
        if (speedAlertsJob?.isActive == true) {
            speedAlertsJob?.cancel(); speedAlertsJob = null
            appendLog("speedAlerts() unsubscribed")
            return
        }
        val s = svc() ?: return appendLog("service unavailable")
        appendLog("speedAlerts() subscribed — every 800ms")
        speedAlertsJob = lifecycleScope.launch(Dispatchers.IO) {
            s.speedAlerts()
                .take(6)
                .catch { appendLog("speedAlerts error: ${it.message}") }
                .collect { speed -> appendLog("  speed alert: ${speed}km/h") }
            appendLog("speedAlerts() done (take 6)")
        }
    }

    // ── 14. @IpcStream — Firmware chunks ───────────────────────────────────

    private fun demoFirmwareChunks() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("firmwareChunks() streaming...")
        var total = 0
        s.firmwareChunks()
            .take(10)
            .catch { appendLog("firmwareChunks error: ${it.message}") }
            .collect { chunk -> total += chunk.size; appendLog("  chunk ${chunk.size}B (total ${total}B)") }
        appendLog("firmwareChunks() complete: $total bytes")
    }

    // ── 15. @IpcCallback — Async lookup ────────────────────────────────────

    private fun demoSlowLookup() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("slowLookup(\"vehicle\") dispatched...")
        s.slowLookup("vehicle", object : IpcReply<com.falcon.cross.shared.CrossData> {
            override fun onResult(data: com.falcon.cross.shared.CrossData) =
                appendLog("  slowLookup onResult: id=${data.id} name=${data.name} meta=${data.meta}")
            override fun onError(code: Int, message: String) =
                appendLog("  slowLookup onError[$code]: $message")
        })
        // Also test error path
        s.slowLookup("", object : IpcReply<com.falcon.cross.shared.CrossData> {
            override fun onResult(data: com.falcon.cross.shared.CrossData) =
                appendLog("  slowLookup(\"\") onResult: id=${data.id} (unexpected)")
            override fun onError(code: Int, message: String) =
                appendLog("  slowLookup(\"\") onError[$code]: $message")
        })
    }

    // ── 16. @IpcCallback — Validate with error paths ───────────────────────

    private fun demoValidateVehicle() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        // success
        s.validateVehicle("VIN12345", object : IpcReply<VehicleData> {
            override fun onResult(data: VehicleData) =
                appendLog("  validateVehicle(\"VIN12345\") → OK: ${data.vin}")
            override fun onError(code: Int, message: String) =
                appendLog("  validateVehicle(\"VIN12345\") error[$code]: $message")
        })
        // blank → error code 2
        s.validateVehicle("  ", object : IpcReply<VehicleData> {
            override fun onResult(data: VehicleData) =
                appendLog("  validateVehicle(\"  \") → OK (unexpected)")
            override fun onError(code: Int, message: String) =
                appendLog("  validateVehicle(\"  \") → onError[$code]: $message")
        })
        // too short → error code 3
        s.validateVehicle("AB", object : IpcReply<VehicleData> {
            override fun onResult(data: VehicleData) =
                appendLog("  validateVehicle(\"AB\") → OK (unexpected)")
            override fun onError(code: Int, message: String) =
                appendLog("  validateVehicle(\"AB\") → onError[$code]: $message")
        })
    }

    // ── 17. Cross-app latency benchmark ────────────────────────────────────

    private fun demoBenchmark() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        appendLog("⏱ Benchmarking TRUE cross-app Falcon latency")
        appendLog("  (real app-to-app Binder + per-call signature check)")
        runCatching {
            appendLog("  ${CrossBenchmark.measure("ping()") { s.ping("x") }}")
            appendLog("  ${CrossBenchmark.measure("add()") { s.add(2, 3) }}")
            appendLog("  ${CrossBenchmark.measure("echoBytes(16B)") { s.echoBytes(ByteArray(16)) }}")
            appendLog("  ${CrossBenchmark.measure("echoBytes(4KB)") { s.echoBytes(ByteArray(4 * 1024)) }}")
            appendLog("  ${CrossBenchmark.measure("getVehicleData()") { s.getVehicleData("VIN12345") }}")
            appendLog("⏱ Benchmark complete.")
        }.onFailure { appendLog("benchmark error: ${it.message}") }
    }

    // ── 18. Concurrent stress test ─────────────────────────────────────────
    // Hammers the cross-app path with many parallel calls to surface proxy/
    // transport thread-safety issues, server-side concurrent-dispatch bugs,
    // connection instability, and correctness violations under load. Each call
    // is validated, so a wrong answer (e.g. a torn response) counts as an error.

    private fun demoStress() = lifecycleScope.launch(Dispatchers.IO) {
        val s = svc() ?: return@launch appendLog("service unavailable")
        val workers = 8
        val perWorker = 500
        val total = workers * perWorker
        appendLog("💥 Stress: $workers workers × $perWorker = $total concurrent cross-app calls")

        val ok = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        runCatching {
            coroutineScope {
                repeat(workers) { w ->
                    launch(Dispatchers.IO) {
                        repeat(perWorker) { i ->
                            try {
                                val valid = when (i % 4) {
                                    0 -> s.add(i, 1) == i + 1
                                    1 -> s.ping("w$w").startsWith("pong:w$w")
                                    2 -> s.echoBytes(ByteArray(256) { it.toByte() }).size == 256
                                    else -> s.getVehicleData("VIN$w").vin == "VIN$w"
                                }
                                if (valid) ok.incrementAndGet() else errors.incrementAndGet()
                            } catch (e: Exception) {
                                errors.incrementAndGet()
                            }
                        }
                    }
                }
            }
        }.onFailure { appendLog("stress aborted: ${it.message}") }

        val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val throughput = total / (elapsedMs / 1000.0)
        appendLog("💥 done: ok=${ok.get()} errors=${errors.get()} in ${"%.0f".format(elapsedMs)}ms")
        appendLog("   throughput=${"%.0f".format(throughput)} calls/sec (${workers} concurrent)")
        if (errors.get() == 0) appendLog("   ✓ all $total calls correct under concurrent load")
        else appendLog("   ✗ ${errors.get()} calls failed — see logcat")
    }

    override fun onDestroy() {
        super.onDestroy()
        telemetryJob?.cancel()
        speedAlertsJob?.cancel()
    }
}
