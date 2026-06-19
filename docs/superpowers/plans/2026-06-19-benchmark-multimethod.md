# Benchmark Multi-Method IPC Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the AIDL-only benchmark into a real cross-process comparison of AIDL, Messenger, ContentProvider, Falcon, and Broadcast, printed in one table.

**Architecture:** All host logic runs in `:benchmark_remote`. AIDL/Messenger reuse the existing `BenchmarkHostService`. ContentProvider gets a real `BenchmarkProvider`. Falcon registers its service in the remote process (via an `Application` per-process init) and the client directly binds `IpcHostService`, calling through the generated proxy over `BinderTransport`. Broadcast does a real cross-process round-trip via AMS.

**Tech Stack:** Kotlin, Android (AIDL/Messenger/ContentProvider/BroadcastReceiver/Binder), Falcon (KSP-generated proxy/dispatcher).

**Spec:** `docs/superpowers/specs/2026-06-19-benchmark-multimethod.md`

**Verification:** The benchmark is an on-device APK (an Activity). It CANNOT be run in this environment — tasks are verified by `./gradlew :falcon-benchmark:assembleDebug` (compile) + code review. Real numbers require running the APK on a device/emulator. Do NOT claim runtime results.

**Key facts (confirmed):**
- `Falcon.init(context){ block }` then `Falcon.getInstance().register(KClass, impl)`. Generated registry object: `com.falcon.ipc.generated.BenchmarkFalconGeneratedRegistry`. Generated proxy: `com.falcon.benchmark.BenchmarkFalconService_Proxy(transport, serviceKey)`. Service interface: `com.falcon.benchmark.IBenchmarkFalconService`.
- `IpcHostService` (in falcon-core) is NOT declared in any manifest — the benchmark must declare it in `:benchmark_remote` with the bind action `com.falcon.ipc.HOST_SERVICE` (the action PeerManager uses).
- `IIpcHost` AIDL: `IIpcHost.Stub.asInterface(binder)`. `BinderTransport(host)` wraps it.
- `IBenchmarkFalconService` has `@IpcMethod echoString(String):String`, `computeSum(Int,Int):Long`, `echoBytes(ByteArray):ByteArray` (and ticks/fetch which the benchmark ignores).
- Existing `BenchmarkHostService` runs in `:benchmark_remote`, serves AIDL (default binder) + Messenger (`transport`=="messenger").

---

## File Structure

**Create:**
- `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkApp.kt` — `Application`; per-process Falcon init + register (remote only)
- `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkProvider.kt` — ContentProvider echo
- `falcon-benchmark/src/main/java/com/falcon/benchmark/FalconTest.kt` — Falcon benchmark via direct bind + generated proxy
- `falcon-benchmark/src/main/java/com/falcon/benchmark/BroadcastTest.kt` — cross-process broadcast round-trip (client side)
- `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkRequestReceiver.kt` — remote receiver that replies

**Modify:**
- `falcon-benchmark/src/main/AndroidManifest.xml` — app name, provider, receiver, IpcHostService (all `:benchmark_remote`)
- `falcon-benchmark/src/main/java/com/falcon/benchmark/MessengerTest.kt` — add large
- `falcon-benchmark/src/main/java/com/falcon/benchmark/ContentProviderTest.kt` — add medium + use real provider semantics
- `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkActivity.kt` — orchestrate all 5

---

## Task 1: Manifest — declare provider, receiver, Falcon host, Application

**Files:**
- Modify: `falcon-benchmark/src/main/AndroidManifest.xml`

- [ ] **Step 1: Rewrite the manifest**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name=".BenchmarkApp"
        android:label="Falcon Benchmark"
        android:theme="@style/Theme.AppCompat.Light">
        <activity
            android:name=".BenchmarkActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- AIDL + Messenger host (existing) -->
        <service
            android:name=".BenchmarkHostService"
            android:exported="false"
            android:process=":benchmark_remote" />

        <!-- Falcon host, in the remote process; bound directly by FalconTest -->
        <service
            android:name="com.falcon.ipc.core.IpcHostService"
            android:exported="false"
            android:process=":benchmark_remote">
            <intent-filter>
                <action android:name="com.falcon.ipc.HOST_SERVICE" />
            </intent-filter>
        </service>

        <!-- ContentProvider, in the remote process -->
        <provider
            android:name=".BenchmarkProvider"
            android:authorities="${applicationId}.falcon.benchmark.provider"
            android:exported="false"
            android:process=":benchmark_remote" />

        <!-- Broadcast request receiver, in the remote process -->
        <receiver
            android:name=".BenchmarkRequestReceiver"
            android:exported="false"
            android:process=":benchmark_remote">
            <intent-filter>
                <action android:name="com.falcon.benchmark.BENCH_REQUEST" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

- [ ] **Step 2: Verify it compiles/merges**

Run: `./gradlew :falcon-benchmark:processDebugManifest`
Expected: BUILD SUCCESSFUL (referenced classes don't exist yet, but manifest merge/parse should pass; if AGP validates class existence it will fail — in that case proceed to create the classes in later tasks and run this at the end. If it fails ONLY due to missing classes, that's expected; note it and continue.)

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/AndroidManifest.xml
git commit -m "benchmark: declare provider, broadcast receiver, Falcon IpcHostService (:benchmark_remote)"
```

---

## Task 2: BenchmarkApp — per-process Falcon init + register (remote)

**Files:**
- Create: `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkApp.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.falcon.benchmark

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import com.falcon.ipc.Falcon
import com.falcon.ipc.generated.BenchmarkFalconGeneratedRegistry
import com.falcon.ipc.register

class BenchmarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only the remote process hosts the Falcon service (where IpcHostService runs).
        if (currentProcessName().endsWith(":benchmark_remote")) {
            val falcon = Falcon.init(this) { generated(BenchmarkFalconGeneratedRegistry) }
            falcon.register(IBenchmarkFalconService::class, BenchmarkFalconServiceImpl())
        }
    }

    private fun currentProcessName(): String {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = android.os.Process.myPid()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: packageName
    }
}

/** Falcon service implementation used by the benchmark (echo + sum). */
class BenchmarkFalconServiceImpl : IBenchmarkFalconService {
    override fun echoString(input: String): String = input
    override fun computeSum(from: Int, to: Int): Long {
        var s = 0L; for (i in from..to) s += i; return s
    }
    override fun echoBytes(input: ByteArray): ByteArray = input
    // ticks()/fetch() exist on the interface for KSP event/callback coverage; benchmark doesn't use them.
    override fun ticks(): kotlinx.coroutines.flow.Flow<Int> = kotlinx.coroutines.flow.flowOf()
    override fun fetch(id: Int, reply: com.falcon.ipc.service.IpcReply<String>) { reply.onResult("v$id") }
}
```
NOTE: confirm the exact method set on `IBenchmarkFalconService` first (`cat falcon-benchmark/src/main/java/com/falcon/benchmark/IBenchmarkFalconService.kt`) and implement ALL of them. If signatures differ, match them.

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (generated registry/proxy already exist from prior work; `register` extension resolves).

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkApp.kt
git commit -m "benchmark: BenchmarkApp inits+registers Falcon service in remote process"
```

---

## Task 3: BenchmarkProvider — ContentProvider echo

**Files:**
- Create: `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkProvider.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.falcon.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Minimal echo provider: insert stashes the last value; query returns it. */
class BenchmarkProvider : ContentProvider() {
    @Volatile private var lastValue: String? = null

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        lastValue = values?.getAsString("value")
        return uri
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        return MatrixCursor(arrayOf("value")).apply { addRow(arrayOf<Any?>(lastValue)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkProvider.kt
git commit -m "benchmark: add BenchmarkProvider (echo via ContentProvider)"
```

---

## Task 4: ContentProviderTest — wire small + medium against the real provider

**Files:**
- Modify: `falcon-benchmark/src/main/java/com/falcon/benchmark/ContentProviderTest.kt`

- [ ] **Step 1: Rewrite to small + medium and read the echoed value**

```kotlin
package com.falcon.benchmark

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Base64

class ContentProviderTest(private val context: Context) {
    private val authority = "${context.packageName}.falcon.benchmark.provider"
    private val uri = Uri.parse("content://$authority/benchmark")

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run("ContentProvider", "Small (${data.length} bytes)", iterations = 500) {
            roundTrip(data)
        }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        // 16KB encoded as a String value column
        val data = Base64.encodeToString(BenchmarkRunner.generateMediumData(16), Base64.NO_WRAP)
        return BenchmarkRunner.run("ContentProvider", "Medium (~16384 bytes)", iterations = 300) {
            roundTrip(data)
        }
    }

    private fun roundTrip(value: String) {
        context.contentResolver.insert(uri, ContentValues().apply { put("value", value) })
        context.contentResolver.query(uri, null, null, null, null)?.use { it.moveToFirst() }
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/ContentProviderTest.kt
git commit -m "benchmark: ContentProviderTest small+medium against real provider"
```

---

## Task 5: MessengerTest — add large

**Files:**
- Modify: `falcon-benchmark/src/main/java/com/falcon/benchmark/MessengerTest.kt`

- [ ] **Step 1: Add a large benchmark method**

Add to `MessengerTest`:
```kotlin
fun runLargeDataBenchmark(): BenchmarkResult {
    val data = BenchmarkRunner.generateLargeData(256)
    return BenchmarkRunner.run("Messenger", "Large (${data.size} bytes)", iterations = 200) {
        val bundle = android.os.Bundle().apply { putByteArray(KEY_DATA, data) }
        sendAndWait(MSG_ECHO_BYTES, bundle)
    }
}
```
(The host messenger handler already echoes `MSG_ECHO_BYTES` byte arrays — no host change.)

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/MessengerTest.kt
git commit -m "benchmark: MessengerTest add large (256KB)"
```

---

## Task 6: FalconTest — direct-bind IpcHostService + generated proxy

**Files:**
- Create: `falcon-benchmark/src/main/java/com/falcon/benchmark/FalconTest.kt`

- [ ] **Step 1: Implement**

`FalconTest` is given a connected `IpcTransport` (the Activity binds `IpcHostService` and supplies it). It builds the generated proxy and benchmarks echo calls.
```kotlin
package com.falcon.benchmark

import com.falcon.ipc.transport.IpcTransport

class FalconTest(transport: IpcTransport) {
    private val proxy: IBenchmarkFalconService =
        BenchmarkFalconService_Proxy(transport, IBenchmarkFalconService::class.qualifiedName!!)

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData()
        return BenchmarkRunner.run("Falcon", "Small (${data.length} bytes)", iterations = 1000) {
            proxy.echoString(data)
        }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateMediumData(16)
        return BenchmarkRunner.run("Falcon", "Medium (${data.size} bytes)", iterations = 500) {
            proxy.echoBytes(data)
        }
    }

    fun runLargeDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateLargeData(256)
        return BenchmarkRunner.run("Falcon", "Large (${data.size} bytes)", iterations = 200) {
            proxy.echoBytes(data)
        }
    }
}
```
NOTE: confirm the generated proxy constructor signature by reading `falcon-benchmark/build/generated/ksp/debug/kotlin/com/falcon/benchmark/BenchmarkFalconService_Proxy.kt` (it is `(transport: IpcTransport, serviceKey: String)`). If `echoString`/`echoBytes` are NOT suspend on the interface, the calls above work directly; if they are suspend, wrap in `kotlinx.coroutines.runBlocking { }`. Check `IBenchmarkFalconService.kt`.

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/FalconTest.kt
git commit -m "benchmark: FalconTest via generated proxy over BinderTransport"
```

---

## Task 7: Broadcast — remote receiver + client round-trip

**Files:**
- Create: `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkRequestReceiver.kt`
- Create: `falcon-benchmark/src/main/java/com/falcon/benchmark/BroadcastTest.kt`

- [ ] **Step 1: Remote request receiver (echoes back via reply broadcast)**

```kotlin
package com.falcon.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs in :benchmark_remote. Echoes the payload back via a reply broadcast. */
class BenchmarkRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = Intent(BroadcastTest.ACTION_REPLY).apply {
            setPackage(context.packageName)
            putExtra(BroadcastTest.EXTRA_ID, intent.getIntExtra(BroadcastTest.EXTRA_ID, -1))
            putExtra(BroadcastTest.EXTRA_DATA, intent.getByteArrayExtra(BroadcastTest.EXTRA_DATA))
        }
        context.sendBroadcast(reply)
    }
}
```

- [ ] **Step 2: Client round-trip benchmark**

```kotlin
package com.falcon.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real cross-process round-trip over AMS: request broadcast -> remote receiver -> reply broadcast. */
class BroadcastTest(private val context: Context) {
    companion object {
        const val ACTION_REQUEST = "com.falcon.benchmark.BENCH_REQUEST"
        const val ACTION_REPLY = "com.falcon.benchmark.BENCH_REPLY"
        const val EXTRA_ID = "id"
        const val EXTRA_DATA = "data"
    }

    private val ids = AtomicInteger(0)
    @Volatile private var latch: CountDownLatch? = null

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { latch?.countDown() }
    }

    fun register() {
        val filter = IntentFilter(ACTION_REPLY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(replyReceiver, filter)
        }
    }

    fun unregister() { runCatching { context.unregisterReceiver(replyReceiver) } }

    private fun roundTrip(data: ByteArray) {
        latch = CountDownLatch(1)
        context.sendBroadcast(Intent(ACTION_REQUEST).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ID, ids.incrementAndGet())
            putExtra(EXTRA_DATA, data)
        })
        latch?.await(5, TimeUnit.SECONDS)
    }

    fun runSmallDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateSmallData().toByteArray()
        return BenchmarkRunner.run("Broadcast", "Small (${data.size} bytes)", iterations = 300) { roundTrip(data) }
    }

    fun runMediumDataBenchmark(): BenchmarkResult {
        val data = BenchmarkRunner.generateMediumData(16)
        return BenchmarkRunner.run("Broadcast", "Medium (${data.size} bytes)", iterations = 200) { roundTrip(data) }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkRequestReceiver.kt falcon-benchmark/src/main/java/com/falcon/benchmark/BroadcastTest.kt
git commit -m "benchmark: cross-process broadcast round-trip (AMS)"
```

---

## Task 8: BenchmarkActivity — orchestrate all 5 methods

**Files:**
- Modify: `falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkActivity.kt`

- [ ] **Step 1: Rewrite to bind both hosts and run all methods**

Replace `runBenchmarks()` so it: keeps the AIDL binding; additionally binds `BenchmarkHostService` with `transport=messenger` for Messenger; binds `IpcHostService` (action `com.falcon.ipc.HOST_SERVICE`) for Falcon; registers the broadcast reply receiver. Each method's results are added; failures are caught per-method and recorded as an N/A note rather than crashing the whole run. Use the existing `printComparison()` table.

Concrete orchestration (add to BenchmarkActivity; keep `results`, `updateResults`, `printComparison`):
```kotlin
private var messengerBinder: android.os.Messenger? = null
private var falconTransport: com.falcon.ipc.transport.IpcTransport? = null

private fun runBenchmarks() {
    lifecycleScope.launch(Dispatchers.Default) {
        // AIDL
        runCatching {
            val t = AidlTest().apply { setService(aidlService!!) }
            results += t.runSmallDataBenchmark(); results += t.runMediumDataBenchmark(); results += t.runLargeDataBenchmark()
        }.onFailure { results += na("Raw AIDL", it) }

        // Messenger
        runCatching {
            val m = MessengerTest().apply { setup(messengerBinder!!) }
            results += m.runSmallDataBenchmark(); results += m.runMediumDataBenchmark(); results += m.runLargeDataBenchmark()
        }.onFailure { results += na("Messenger", it) }

        // ContentProvider
        runCatching {
            val c = ContentProviderTest(this@BenchmarkActivity)
            results += c.runSmallDataBenchmark(); results += c.runMediumDataBenchmark()
        }.onFailure { results += na("ContentProvider", it) }

        // Falcon
        runCatching {
            val f = FalconTest(falconTransport!!)
            results += f.runSmallDataBenchmark(); results += f.runMediumDataBenchmark(); results += f.runLargeDataBenchmark()
        }.onFailure { results += na("Falcon", it) }

        // Broadcast
        runCatching {
            val b = BroadcastTest(this@BenchmarkActivity).apply { register() }
            results += b.runSmallDataBenchmark(); results += b.runMediumDataBenchmark(); b.unregister()
        }.onFailure { results += na("Broadcast", it) }

        printComparison()
    }
}

private fun na(name: String, e: Throwable) = BenchmarkResult(
    name = name, dataSize = "N/A", iterations = 0, totalMs = 0,
    avgMs = -1.0, minMs = -1.0, maxMs = -1.0, p50Ms = -1.0, p95Ms = -1.0, p99Ms = -1.0
).also { android.util.Log.e("Bench", "$name failed", e) }
```
And in `onCreate`, after the AIDL bind succeeds, ALSO bind the messenger transport and the Falcon host, storing `messengerBinder` (`android.os.Messenger(binder)`) and `falconTransport` (`com.falcon.ipc.transport.BinderTransport(com.falcon.ipc.aidl.IIpcHost.Stub.asInterface(binder))`), then call `runBenchmarks()` only once all three connections are ready (track with a counter or sequential binds). Bind intents:
- Messenger: `Intent(this, BenchmarkHostService::class.java).putExtra("transport","messenger")`.
- Falcon: `Intent("com.falcon.ipc.HOST_SERVICE").setPackage(packageName)`.
Use distinct `ServiceConnection`s. Gate `runBenchmarks()` on all three connected (e.g., an `AtomicInteger` reaching 3).

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/main/java/com/falcon/benchmark/BenchmarkActivity.kt
git commit -m "benchmark: orchestrate AIDL/Messenger/ContentProvider/Falcon/Broadcast in one run"
```

---

## Task 9: Full assemble + docs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Assemble the benchmark APK (compile-verify everything)**

Run: `./gradlew :falcon-benchmark:assembleDebug`
Expected: BUILD SUCCESSFUL. Then `./gradlew build` — BUILD SUCCESSFUL (all modules). Fix any compile fallout.

- [ ] **Step 2: Update CLAUDE.md benchmark description**

Update the `falcon-benchmark` line/section to state it compares Falcon vs raw AIDL vs Messenger vs ContentProvider vs Broadcast across small/medium/large payloads, printing avg/p50/p99; note Falcon is measured via direct `IpcHostService` bind + generated proxy (steady-state call path, discovery excluded — same as AIDL); note Broadcast goes through AMS and is not request/reply (caveat); note results require running the APK on a device.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document benchmark multi-method comparison"
```

---

## Self-Review Notes

- **Spec coverage:** manifest provider/receiver/host → Task 1; per-process Falcon init+register → Task 2; ContentProvider real → Task 3,4; Messenger large → Task 5; Falcon via generated proxy → Task 6; Broadcast cross-process → Task 7; orchestration table → Task 8; assemble+docs → Task 9. All spec methods + sizes covered.
- **Type/name consistency:** `BroadcastTest.ACTION_REQUEST/ACTION_REPLY/EXTRA_ID/EXTRA_DATA` referenced by `BenchmarkRequestReceiver` (Task 7) — defined in same task. `IBenchmarkFalconService` impl in Task 2 must match the interface (verify before writing). `BenchmarkFalconService_Proxy(transport, serviceKey)` used in Task 6 — confirm generated signature. `BenchmarkFalconGeneratedRegistry` in `com.falcon.ipc.generated` used in Task 2.
- **Verification reality:** everything is compile-verified via `assembleDebug`; the benchmark only produces numbers on a device. No task claims runtime results.
- **Failure isolation:** Task 8 wraps each method in `runCatching` so one method failing on-device (e.g., Falcon bring-up or broadcast throttling) doesn't abort the others; failures show as N/A.
- **Known device risks (documented, not blocking compile):** Falcon two-process bind is first real deployment; broadcast latency/throttling via AMS. Both surface only on device.
