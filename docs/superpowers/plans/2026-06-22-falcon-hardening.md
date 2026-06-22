# Falcon Production-Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six gaps found in the 2026-06-22 architecture review so Falcon is safe to ship in a vehicle (automotive) context: no main-thread ANR, no silent event loss, no uninterruptible hangs, configurable timeouts, regression-locked code generation, and interface-version safety.

**Architecture:** Changes are surgical and backward-leaning. The blocking `getService` gains a `suspend` sibling and a main-thread guard; generated code reads timeouts/version from the runtime instead of hardcoding; event flows gain a configurable overflow policy; the Binder transport gains a watchdog so a hung peer can't pin a thread forever; KSP gains regression tests and a per-interface version hash that is checked during discovery.

**Tech Stack:** Kotlin, KSP (`1.9.22-1.0.17`), Android Binder/AIDL, kotlinx-coroutines, JUnit4 + Robolectric (JVM tests), AndroidJUnit (instrumented tests).

**Priorities:** P0 = correctness/safety, ship first (Tasks 1–3). P1 = robustness (Tasks 4–6). P2 = evolvability (Task 7).

**Build/verify reference (macOS, this repo):**
- Core unit tests: `./gradlew :falcon-core:test`
- Demo JVM round-trip: `./gradlew :falcon-demo:testDebugUnitTest`
- KSP regenerates on every build of a consumer module; after generator edits run a `clean` of the consumer (`./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest`) to avoid stale incremental output.
- `timeout` is not on macOS; do not use it in commands.
- An emulator (`emulator-5558`) is available for instrumented tests; adb is at `~/Library/Android/sdk/platform-tools/adb`.

---

## File Structure

| File | Responsibility | Tasks |
|------|----------------|-------|
| `falcon-core/.../core/FalconManager.kt` | add `suspend getServiceSuspending`, main-thread guard, refactor blocking `getService` to reuse it | 1 |
| `falcon-core/.../Falcon.kt` | add `suspend` extension `getServiceSuspending` | 1 |
| `falcon-core/.../FalconConfig.kt` | add `EventConfig`; `strictThreadPolicy` flag | 1, 4 |
| `falcon-demo/.../DemoRoundTripTest.kt` | already exists — reused as the generator regression harness | 2 |
| `falcon-demo/.../IDemoService.kt` + `DemoServiceImpl.kt` | add a method with collision-prone param names | 2 |
| `falcon-ksp/.../generator/DispatcherGenerator.kt` | read timeout from runtime instead of hardcoded 5000 | 3 |
| `falcon-core/.../core/EventProxy.kt` | apply configurable buffer/overflow policy | 4 |
| `falcon-core/.../transport/BinderTransport.kt` | timeout-guarded `invoke` (watchdog) | 5 |
| `falcon-demo/src/androidTest/.../DemoCrossProcessTest.kt` | new instrumented two-process test | 6 |
| `falcon-ksp/.../FalconProcessor.kt` + generators | emit per-interface version hash | 7 |
| `falcon-core/.../core/MessageRouter.kt` + `FalconManager.kt` | check interface version in `__check_service__` | 7 |

---

## P0 — Safety (ship first)

### Task 1: `suspend` getService + main-thread guard

**Why:** `FalconManager.getService` (`FalconManager.kt:111`) wraps discovery in `runBlocking`. Called on the main thread it blocks the UI up to `connectMs` (3 s) → ANR. This was the demo "卡死". Provide a non-blocking `suspend` path and make accidental main-thread use loud.

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/Falcon.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/core/GetServiceThreadTest.kt` (create)

- [ ] **Step 1: Add `strictThreadPolicy` flag to config**

In `FalconConfig.kt`, add a top-level field to `FalconConfig` (not a nested data class):

```kotlin
class FalconConfig {
    var transport = TransportConfig()
    var reconnect = ReconnectConfig()
    var timeout = TimeoutConfig()
    var security = SecurityConfig()
    var monitorLevel: MonitorLevel = MonitorLevel.NONE
    /** When true, calling the blocking getService() on the main thread throws instead of warning. */
    var strictThreadPolicy: Boolean = false
    internal val interceptors = mutableListOf<IpcInterceptor>()
    internal val generatedRegistries = mutableListOf<com.falcon.ipc.runtime.FalconGeneratedRegistry>()
    // ... (existing DSL methods unchanged)
}
```

- [ ] **Step 2: Write the failing test**

Create `falcon-core/src/test/java/com/falcon/ipc/core/GetServiceThreadTest.kt`:

```kotlin
package com.falcon.ipc.core

import android.os.Looper
import com.falcon.ipc.Falcon
import com.falcon.ipc.FalconConfig
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GetServiceThreadTest {

    @After fun tearDown() { Falcon.instance?.shutdown() }

    private fun initFalcon(strict: Boolean) {
        Falcon.init(RuntimeEnvironment.getApplication()) {
            strictThreadPolicy = strict
        }
    }

    @Test
    fun `blocking getService on main thread with strict policy throws`() {
        initFalcon(strict = true)
        // Robolectric runs tests on the main looper thread by default.
        assert(Looper.myLooper() == Looper.getMainLooper())
        assertThrows(IllegalStateException::class.java) {
            Falcon.getInstance().getService(com.falcon.ipc.service.IpcService::class)
        }
    }

    @Test
    fun `blocking getService with no peers returns null off strict`() {
        initFalcon(strict = false)
        // No peers registered → null, and (on main thread) only a warning.
        assertNull(Falcon.getInstance().getService(com.falcon.ipc.service.IpcService::class))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :falcon-core:test --tests "com.falcon.ipc.core.GetServiceThreadTest"`
Expected: FAIL — strict policy not implemented yet, no throw.

- [ ] **Step 4: Refactor `getService` and add the suspend + guard**

In `FalconManager.kt`, replace the body of `getService` (currently lines ~111-155) so the probing logic lives in a private `suspend` function, the public blocking version guards the main thread, and a public `suspend` version exists. Keep the existing probing logic verbatim inside `probeRemote`.

```kotlin
@Suppress("UNCHECKED_CAST")
fun <T : IpcService> getService(serviceClass: KClass<T>): T? {
    // Fast local path never blocks.
    val key = serviceClass.qualifiedName ?: return null
    serviceRegistry.getService(key)?.let { return it as T }

    // Remote path is blocking — guard the main thread.
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        val msg = "Falcon.getService() does blocking IPC discovery and must not be " +
            "called on the main thread. Use getServiceSuspending() from a coroutine."
        if (config.strictThreadPolicy) throw IllegalStateException(msg)
        FalconLogger.w("Falcon", msg)
    }
    return kotlinx.coroutines.runBlocking { getServiceSuspending(serviceClass) }
}

/** Non-blocking discovery — call from a coroutine. Returns null if no peer serves [serviceClass]. */
@Suppress("UNCHECKED_CAST")
suspend fun <T : IpcService> getServiceSuspending(serviceClass: KClass<T>): T? {
    val key = serviceClass.qualifiedName ?: return null
    serviceRegistry.getService(key)?.let { return it as T }

    val peers = peerManager?.getAllConnections() ?: return null
    if (peers.isEmpty()) return null

    val found = withTimeoutOrNull(config.timeout.connectMs) {
        coroutineScope {
            peers.map { (_, peer) ->
                async {
                    try {
                        kotlinx.coroutines.withTimeout(500L) {
                            val checkEnvelope = IpcEnvelope(
                                serviceKey = "",
                                method = "__check_service__",
                                argsBundle = android.os.Bundle().apply { putString("key", key) }
                            )
                            val result = peer.transport.invoke(checkEnvelope)
                            if (result is TransportResult.Success) {
                                val statusCode = (result.data as? android.os.Bundle)?.getInt("r", 1) ?: 1
                                if (statusCode == 0) peer else null
                            } else null
                        }
                    } catch (e: Exception) {
                        FalconLogger.w("Falcon", "peer probe failed for ${peer.processName}: ${e.message}")
                        null
                    }
                }
            }.mapNotNull { it.await() }.firstOrNull()
        }
    } ?: return null

    val factory = config.generatedRegistries.firstNotNullOfOrNull { it.proxyFactories[key] }
    if (factory == null) {
        FalconLogger.w("Falcon", "No proxy factory for $key — did you call generated(XxxFalconGeneratedRegistry)?")
        return null
    }
    return factory(found.transport, key) as T
}
```

Remove the now-duplicated old `runBlocking { ... }` block. Keep imports (`async`, `coroutineScope`, `withTimeoutOrNull`, `withTimeout`) — they are already imported.

- [ ] **Step 5: Add the suspend extension in `Falcon.kt`**

```kotlin
suspend inline fun <reified T : IpcService> FalconManager.getServiceSuspending(): T? {
    return getServiceSuspending(T::class)
}
```

- [ ] **Step 6: Run tests to verify pass**

Run: `./gradlew :falcon-core:test --tests "com.falcon.ipc.core.GetServiceThreadTest"`
Expected: PASS (both tests).

- [ ] **Step 7: Update the demo to use the non-blocking API**

In `falcon-demo/.../DemoActivity.kt`, change the discovery loop (`waitForServiceThenEnable`) to use the suspend variant (it already runs in `launch(Dispatchers.IO)`):

```kotlin
val svc = Falcon.getInstance().getServiceSuspending<IDemoService>()
```

Add import: `import com.falcon.ipc.getServiceSuspending`.

- [ ] **Step 8: Run the demo round-trip + rebuild APK**

Run: `./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest :falcon-demo:assembleDebug`
Expected: BUILD SUCCESSFUL, 7 tests pass.

- [ ] **Step 9: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt \
        falcon-core/src/main/java/com/falcon/ipc/Falcon.kt \
        falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt \
        falcon-core/src/test/java/com/falcon/ipc/core/GetServiceThreadTest.kt \
        falcon-demo/src/main/java/com/falcon/demo/DemoActivity.kt
git commit -m "feat(core): add suspend getServiceSuspending + main-thread guard for blocking getService

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Lock the generator parameter-name-collision fix with regression tests

**Why:** The proxy generator previously hardcoded the local Bundle as `b`, colliding with a param named `b`. It was fixed to `__falcon*` prefixes but there is no test asserting collision-prone names compile and round-trip. Add an interface method whose params are named exactly like the generator's internal locals.

**Files:**
- Modify: `falcon-demo/src/main/java/com/falcon/demo/IDemoService.kt`
- Modify: `falcon-demo/src/main/java/com/falcon/demo/DemoServiceImpl.kt`
- Modify: `falcon-demo/src/test/java/com/falcon/demo/DemoRoundTripTest.kt`

- [ ] **Step 1: Add a collision-prone method to the interface**

In `IDemoService.kt`, add inside the interface:

```kotlin
    /** Regression guard: params named like the generator's internal locals (b, out, env, result, args). */
    @IpcMethod
    suspend fun collisionProbe(b: Int, out: Int, env: Int, result: Int, args: Int): Int
```

- [ ] **Step 2: Implement it**

In `DemoServiceImpl.kt`:

```kotlin
    override suspend fun collisionProbe(b: Int, out: Int, env: Int, result: Int, args: Int): Int =
        b + out + env + result + args
```

- [ ] **Step 3: Write the failing test**

In `DemoRoundTripTest.kt`, add:

```kotlin
    @Test
    fun `collisionProbe with generator-local param names round-trips`() = runBlocking {
        // 1+2+4+8+16 = 31. If a param name collided with a generated local,
        // the generated proxy would not compile (build failure) — this test
        // also guards the runtime correctness of positional encoding.
        assertEquals(31, proxy.collisionProbe(1, 2, 4, 8, 16))
    }
```

- [ ] **Step 4: Run — verify it compiles and passes**

Run: `./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest --tests "com.falcon.demo.DemoRoundTripTest"`
Expected: PASS (8 tests). If the generator regresses to a hardcoded local, this fails at compile time.

- [ ] **Step 5: Commit**

```bash
git add falcon-demo/src/main/java/com/falcon/demo/IDemoService.kt \
        falcon-demo/src/main/java/com/falcon/demo/DemoServiceImpl.kt \
        falcon-demo/src/test/java/com/falcon/demo/DemoRoundTripTest.kt
git commit -m "test(ksp): regression-lock proxy param-name collision (b/out/env/result/args)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Wire generated dispatch timeout to `TimeoutConfig`

**Why:** `DispatcherGenerator.kt:82` hardcodes `withTimeout(5000L)` for suspend methods, ignoring `TimeoutConfig.callMs`. Read it from the runtime at dispatch time.

**Files:**
- Modify: `falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/DispatcherGenerator.kt`
- Test: `falcon-demo/src/test/java/com/falcon/demo/DemoRoundTripTest.kt` (assert generated source path still round-trips; timeout value is config-driven)

- [ ] **Step 1: Change the generated wrapper to read the configured timeout**

In `DispatcherGenerator.kt`, line ~82, replace:

```kotlin
val wrapped = if (isSuspend) "kotlinx.coroutines.runBlocking(Dispatchers.IO) { withTimeout(5000L) { $call } }" else call
```

with:

```kotlin
val wrapped = if (isSuspend)
    "kotlinx.coroutines.runBlocking(Dispatchers.IO) { withTimeout(com.falcon.ipc.Falcon.getInstance().callTimeoutMs) { $call } }"
else call
```

`FalconManager.callTimeoutMs` already exists (`FalconManager.kt:27`) and returns `config.timeout.callMs`. At dispatch time the server process has called `Falcon.init`, so `getInstance()` is valid.

- [ ] **Step 2: Rebuild generated code and run the existing round-trip**

Run: `./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest`
Expected: PASS (8 tests). The round-trip uses `runBlocking` and a real `Falcon` instance is not required for the fake transport path — but the dispatcher's suspend methods (`ping`, `add`, `getUser`, `collisionProbe`) now reference `Falcon.getInstance()`. Because the test invokes the dispatcher directly, ensure `Falcon` is initialized in the test setup.

- [ ] **Step 3: Initialize Falcon in the round-trip test setup**

In `DemoRoundTripTest.kt`, add to the top of `setUp()` (before constructing the dispatcher):

```kotlin
        com.falcon.ipc.Falcon.init(org.robolectric.RuntimeEnvironment.getApplication()) {
            timeout { callMs = 2_000 }
        }
```

and add teardown:

```kotlin
    @org.junit.After fun tearDown() { com.falcon.ipc.Falcon.instance?.shutdown() }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/DispatcherGenerator.kt \
        falcon-demo/src/test/java/com/falcon/demo/DemoRoundTripTest.kt
git commit -m "feat(ksp): dispatch timeout reads TimeoutConfig.callMs instead of hardcoded 5000ms

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## P1 — Robustness

### Task 4: Configurable event backpressure (stop silent drops)

**Why:** `EventProxy.typedRemoteFlow` uses a default `callbackFlow` buffer (64) and `trySend`, dropping events when the collector is slow (`EventProxy.kt:22`). High-rate `@IpcEvent` (vehicle speed, sensors) loses data silently. Make the policy configurable: `SUSPEND` (apply backpressure), `DROP_OLDEST`, `DROP_LATEST`, or an explicit capacity.

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/EventProxy.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/core/EventProxyTest.kt` (create)

- [ ] **Step 1: Add `EventConfig` to `FalconConfig.kt`**

```kotlin
data class EventConfig(
    /** Channel capacity for cross-process event delivery. */
    var bufferCapacity: Int = 64,
    /** What to do when the buffer is full: SUSPEND, DROP_OLDEST, DROP_LATEST. */
    var onOverflow: kotlinx.coroutines.channels.BufferOverflow =
        kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
)
```

Add to `FalconConfig`:

```kotlin
    var event = EventConfig()
    fun event(block: EventConfig.() -> Unit) { event.block() }
```

- [ ] **Step 2: Write the failing test**

Create `falcon-core/src/test/java/com/falcon/ipc/core/EventProxyTest.kt`:

```kotlin
package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.transport.TransportResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventProxyTest {

    /** A transport that, on subscribe, immediately pushes N integer events. */
    private fun fakeTransport(count: Int) = object : IpcTransport {
        override val maxPayloadSize: Int get() = 1 shl 20
        override fun invoke(envelope: IpcEnvelope): TransportResult = TransportResult.Success(Bundle())
        override fun subscribe(eventKey: String, callback: IIpcEventCallback) {
            for (i in 0 until count) {
                callback.onEvent(IpcEnvelope(serviceKey = eventKey,
                    argsBundle = Bundle().apply { putInt("r", i) }))
            }
        }
        override fun unsubscribe(eventKey: String, callback: IIpcEventCallback) {}
    }

    @Test
    fun `typedRemoteFlow delivers events with SUSPEND backpressure`() {
        val flow = EventProxy.typedRemoteFlow(
            "svc#1", fakeTransport(5),
            capacity = 8, overflow = BufferOverflow.SUSPEND
        ) { b -> b.getInt("r") }
        val got = runBlocking { flow.take(3).toList() }
        assertEquals(listOf(0, 1, 2), got)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :falcon-core:test --tests "com.falcon.ipc.core.EventProxyTest"`
Expected: FAIL — `typedRemoteFlow` has no `capacity`/`overflow` parameters yet.

- [ ] **Step 4: Add parameters and apply the policy in `EventProxy.kt`**

```kotlin
package com.falcon.ipc.core

import android.os.Bundle
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.transport.IpcTransport
import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

object EventProxy {

    fun <T> typedRemoteFlow(
        eventKey: String,
        transport: IpcTransport,
        capacity: Int = 64,
        overflow: BufferOverflow = BufferOverflow.DROP_OLDEST,
        decode: (Bundle) -> T
    ): Flow<T> = callbackFlow {
        val callback = object : IIpcEventCallback.Stub() {
            override fun onEvent(event: IpcEnvelope) {
                val result: ChannelResult<Unit> = trySend(decode(event.argsBundle ?: Bundle()))
                if (result.isFailure && !result.isClosed) {
                    FalconLogger.w("EventProxy", "Event buffer pressure on $eventKey (overflow=$overflow)")
                }
            }
            override fun getEventKey(): String = eventKey
        }
        transport.subscribe(eventKey, callback)
        awaitClose { transport.unsubscribe(eventKey, callback) }
    }.buffer(capacity = capacity, onBufferOverflow = overflow)
}
```

Note: `callbackFlow`'s own channel is RENDEZVOUS-then-`.buffer()`-applied; `.buffer(capacity, onBufferOverflow)` installs the configured policy. With `SUSPEND`, `trySend` from the binder callback still cannot suspend, so for true backpressure the downstream `.buffer(SUSPEND)` provides the bounded queue and the producer side relies on the channel; document that `SUSPEND` bounds memory but a binder callback that outruns the collector will still see `trySend` failures logged. `DROP_OLDEST` is the safe default for telemetry.

- [ ] **Step 5: Thread the config through the generated proxy**

In `falcon-ksp/.../generator/ProxyGenerator.kt`, the event/stream generation currently emits:

```kotlin
sb.appendLine("        com.falcon.ipc.core.EventProxy.typedRemoteFlow(serviceKey + \"#$id\", transport) { __falconBundle -> $getExpr }")
```

Change it to read the runtime config:

```kotlin
sb.appendLine("        com.falcon.ipc.core.EventProxy.typedRemoteFlow(")
sb.appendLine("            serviceKey + \"#$id\", transport,")
sb.appendLine("            capacity = com.falcon.ipc.Falcon.getInstance().eventBufferCapacity,")
sb.appendLine("            overflow = com.falcon.ipc.Falcon.getInstance().eventOverflow")
sb.appendLine("        ) { __falconBundle -> $getExpr }")
```

- [ ] **Step 6: Expose the accessors on `FalconManager`**

In `FalconManager.kt`, next to `callTimeoutMs`:

```kotlin
val eventBufferCapacity: Int get() = config.event.bufferCapacity
val eventOverflow: kotlinx.coroutines.channels.BufferOverflow get() = config.event.onOverflow
```

- [ ] **Step 7: Run core + demo tests**

Run: `./gradlew :falcon-core:test --tests "com.falcon.ipc.core.EventProxyTest" && ./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest`
Expected: PASS. The demo's `clock`/`download` flows now require `Falcon.getInstance()` at flow construction — already initialized by Task 3's setUp change.

- [ ] **Step 8: Document the policy in USAGE.md**

In `docs/USAGE.md`, under the `@IpcEvent` section's hot-subscription note, add:

```markdown
> 背压策略可配：`Falcon.init { event { bufferCapacity = 256; onOverflow = BufferOverflow.SUSPEND } }`。
> 默认 `DROP_OLDEST`（丢最旧，适合遥测）。`SUSPEND` 提供有界内存背压。
```

- [ ] **Step 9: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt \
        falcon-core/src/main/java/com/falcon/ipc/core/EventProxy.kt \
        falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt \
        falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/ProxyGenerator.kt \
        falcon-core/src/test/java/com/falcon/ipc/core/EventProxyTest.kt \
        docs/USAGE.md
git commit -m "feat(core): configurable event backpressure (BufferOverflow) replacing silent drops

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Timeout-guarded `BinderTransport.invoke` (watchdog for blocking Binder)

**Why:** `transport.invoke` is a blocking JNI Binder call; `withTimeout` cannot interrupt it (`BinderTransport.kt:16`). A hung peer pins the calling thread forever. Run the transaction on a dedicated executor and `Future.get(timeout)`; on timeout return a `TRANSPORT_ERROR` so the caller is unblocked (the orphaned thread completes when the binder eventually returns or the peer dies).

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/transport/BinderTransport.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt` (add `invokeTimeoutMs` to TransportConfig)
- Test: `falcon-core/src/test/java/com/falcon/ipc/transport/BinderTransportTimeoutTest.kt` (create)

- [ ] **Step 1: Add `invokeTimeoutMs` to `TransportConfig`**

```kotlin
data class TransportConfig(
    var binderPoolSize: Int = 4,
    var maxBinderPayloadSize: Int = 256 * 1024,
    /** Max wall-clock for a single blocking invoke() before returning TRANSPORT_ERROR. 0 = no watchdog. */
    var invokeTimeoutMs: Long = 5_000
)
```

- [ ] **Step 2: Write the failing test**

Create `falcon-core/src/test/java/com/falcon/ipc/transport/BinderTransportTimeoutTest.kt`:

```kotlin
package com.falcon.ipc.transport

import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BinderTransportTimeoutTest {

    /** A host whose invoke() blocks longer than the watchdog. */
    private val hangingHost = object : IIpcHost.Stub() {
        override fun invoke(request: IpcEnvelope): IpcEnvelope {
            Thread.sleep(2_000)
            return IpcEnvelope(requestId = request.requestId)
        }
        override fun subscribe(k: String?, c: com.falcon.ipc.aidl.IIpcEventCallback?) {}
        override fun unsubscribe(k: String?, c: com.falcon.ipc.aidl.IIpcEventCallback?) {}
        override fun getServiceInfo(): String = ""
        override fun invokeCallback(r: IpcEnvelope?, c: com.falcon.ipc.aidl.IIpcEventCallback?) {}
    }

    @Test
    fun `invoke returns TRANSPORT_ERROR when peer exceeds watchdog`() {
        val transport = BinderTransport(hangingHost, maxPayloadSize = 1 shl 20, invokeTimeoutMs = 200)
        val start = System.currentTimeMillis()
        val result = transport.invoke(IpcEnvelope(serviceKey = "x", method = "m"))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("should return well before 2s", elapsed < 1_000)
        assertTrue(result is TransportResult.Error)
        assertEquals(ErrorCode.TRANSPORT_ERROR, (result as TransportResult.Error).code)
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew :falcon-core:test --tests "com.falcon.ipc.transport.BinderTransportTimeoutTest"`
Expected: FAIL — constructor has no `invokeTimeoutMs`, no watchdog.

- [ ] **Step 4: Implement the watchdog in `BinderTransport.kt`**

Add a constructor parameter and a shared executor; wrap the existing invoke body:

```kotlin
class BinderTransport(
    private val host: IIpcHost,
    override val maxPayloadSize: Int = 256 * 1024,
    private val invokeTimeoutMs: Long = 5_000
) : IpcTransport {

    private companion object {
        // Daemon pool sized for concurrent in-flight calls; orphaned (timed-out) tasks
        // finish when the binder finally returns. CallerRuns avoids unbounded growth.
        val watchdogPool = java.util.concurrent.ThreadPoolExecutor(
            2, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            { r -> Thread(r, "falcon-invoke-watchdog").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        )
    }

    override fun invoke(envelope: IpcEnvelope): TransportResult {
        if (invokeTimeoutMs <= 0) return invokeBlocking(envelope)
        val future = watchdogPool.submit<TransportResult> { invokeBlocking(envelope) }
        return try {
            future.get(invokeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            FalconLogger.e("BinderTransport", "invoke watchdog fired after ${invokeTimeoutMs}ms — peer unresponsive")
            // Do NOT cancel(true): interrupting won't unblock a native binder call; let it finish and be GC'd.
            TransportResult.Error(ErrorCode.TRANSPORT_ERROR, "Peer unresponsive (>${invokeTimeoutMs}ms)")
        } catch (e: java.util.concurrent.ExecutionException) {
            TransportResult.Error(ErrorCode.TRANSPORT_ERROR, e.cause?.message ?: "invoke failed")
        }
    }

    private fun invokeBlocking(envelope: IpcEnvelope): TransportResult {
        return try {
            val response = host.invoke(envelope)
            if (response.isError) TransportResult.Error(response.errorCode, response.errorMessage)
            else TransportResult.Success(response.argsBundle)
        } catch (e: android.os.DeadObjectException) {
            FalconLogger.w("BinderTransport", "Remote process died during invoke")
            TransportResult.Error(ErrorCode.PEER_NOT_CONNECTED, "Remote process died")
        } catch (e: android.os.TransactionTooLargeException) {
            FalconLogger.e("BinderTransport", "Payload exceeds Binder transaction limit", e)
            TransportResult.Error(ErrorCode.SERIALIZATION_ERROR, "Payload too large: ${e.message}")
        } catch (e: SecurityException) {
            FalconLogger.e("BinderTransport", "Binder-level security rejection", e)
            TransportResult.Error(ErrorCode.UNAUTHORIZED, e.message ?: "Security rejection")
        } catch (e: Exception) {
            FalconLogger.e("BinderTransport", "Invoke failed", e)
            TransportResult.Error(ErrorCode.TRANSPORT_ERROR, e.message ?: "Unknown transport error")
        }
    }

    // subscribe / unsubscribe / invokeCallback / isAlive / linkToDeath / unlinkToDeath unchanged
}
```

- [ ] **Step 5: Pass the configured timeout where `BinderTransport` is constructed**

In `PeerManager.kt` (`connectPeer`), update the constructor call:

```kotlin
val transport = BinderTransport(host, transportConfig.maxBinderPayloadSize, transportConfig.invokeTimeoutMs)
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :falcon-core:test --tests "com.falcon.ipc.transport.BinderTransportTimeoutTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/transport/BinderTransport.kt \
        falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt \
        falcon-core/src/main/java/com/falcon/ipc/core/PeerManager.kt \
        falcon-core/src/test/java/com/falcon/ipc/transport/BinderTransportTimeoutTest.kt
git commit -m "feat(transport): watchdog-guarded invoke so a hung peer cannot pin a thread

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Instrumented two-process test (real Binder in CI-on-emulator)

**Why:** The only cross-process verification today is manual. Add an `androidTest` that drives the real `:server` process: request/response, Parcelable, event flow, callback success AND error, and death/reconnect. Runs on the available emulator.

**Files:**
- Modify: `falcon-demo/build.gradle.kts` (androidTest deps)
- Create: `falcon-demo/src/androidTest/java/com/falcon/demo/DemoCrossProcessTest.kt`

- [ ] **Step 1: Add androidTest dependencies**

In `falcon-demo/build.gradle.kts` `dependencies { }`:

```kotlin
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
```

Confirm `defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }` exists in `falcon-demo/build.gradle.kts`; add it if missing.

- [ ] **Step 2: Write the instrumented test**

Create `falcon-demo/src/androidTest/java/com/falcon/demo/DemoCrossProcessTest.kt`:

```kotlin
package com.falcon.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.falcon.ipc.Falcon
import com.falcon.ipc.getServiceSuspending
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DemoCrossProcessTest {

    private fun service(): IDemoService = runBlocking {
        withTimeout(10_000) {
            var svc: IDemoService? = null
            while (svc == null) { svc = Falcon.getInstance().getServiceSuspending<IDemoService>(); if (svc == null) Thread.sleep(200) }
            svc!!
        }
    }

    @Test fun request_response_crosses_process() = runBlocking {
        val r = service().ping("ci")
        assertTrue(r.startsWith("pong: ci"))
    }

    @Test fun parcelable_crosses_process() = runBlocking {
        val u = service().getUser(42)
        assertEquals(42, u.id); assertTrue(u.vip)
    }

    @Test fun event_flow_crosses_process() = runBlocking {
        val ticks = service().clock().take(3).toList()
        assertEquals(listOf(0L, 1L, 2L), ticks)
    }

    @Test fun callback_success_crosses_process() {
        val latch = CountDownLatch(1); var got = ""
        service().loadAsync(7, object : IpcReply<String> {
            override fun onResult(data: String) { got = data; latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals("result for task #7", got)
    }

    @Test fun callback_error_crosses_process() {
        val latch = CountDownLatch(1); var code = 0
        service().loadAsync(-1, object : IpcReply<String> {
            override fun onResult(data: String) {}
            override fun onError(c: Int, m: String) { code = c; latch.countDown() }
        })
        assertTrue(latch.await(5, TimeUnit.SECONDS)); assertEquals(1, code)
    }
}
```

The Falcon client is initialized by `DemoApp` in the instrumentation's app process; the `:server` process is started on first discovery (same as production).

- [ ] **Step 3: Run on the emulator**

Run: `./gradlew :falcon-demo:connectedDebugAndroidTest`
Expected: 5 instrumented tests pass on `emulator-5558`. (If the emulator is offline, start it first; this task requires a device.)

- [ ] **Step 4: Commit**

```bash
git add falcon-demo/build.gradle.kts \
        falcon-demo/src/androidTest/java/com/falcon/demo/DemoCrossProcessTest.kt
git commit -m "test(demo): instrumented two-process Binder round-trip (request/event/callback+error)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## P2 — Cross-app safety

### Task 7: Lightweight `METHOD_NOT_FOUND` for unmatched methods

**Why:** Falcon targets **automotive multi-app** — client and server are independent APKs updated on different schedules. When a client calls a method the server's build doesn't have (added/removed/renamed → different `methodId`), the server currently flattens the dispatcher's `IllegalArgumentException("Unknown methodId")` into `ErrorCode.UNKNOWN` (`IpcHostService.kt` generic catch). Make it return the already-defined `ErrorCode.METHOD_NOT_FOUND` (1002), consistently across request/response, callback, and event/stream. This is **per-method**, reuses the existing `methodId` mechanism, and needs **no** interface hashing, version annotations, or discovery changes.

**Contract-evolution rule (document, don't enforce):** never mutate an existing method's signature/return type in place — add a new method. Changing a signature changes its `methodId`, so old peers calling the old id correctly get `METHOD_NOT_FOUND` instead of silently mis-decoding. (The one case this does NOT catch — same name+params but a changed `Parcelable` field layout — is out of scope for the lightweight check; the rule above avoids it by convention.)

**Files:**
- Modify: `falcon-ksp/.../generator/DispatcherGenerator.kt` (dispatch `else` throws typed exception)
- Modify: `falcon-core/.../core/IpcHostService.kt` (catch `IpcException` → its code; unknown event → error envelope)
- Modify: `falcon-core/.../runtime/IpcDispatcher.kt` (default `invokeCallback` replies an error envelope)
- Modify: `falcon-core/.../core/EventProxy.kt` (propagate error envelopes as flow failures)
- Test: `falcon-demo/.../IDemoService.kt` + `DemoServiceImpl.kt` (a server that omits a method the proxy knows), `falcon-demo/.../DemoRoundTripTest.kt`

- [ ] **Step 1: Write the failing test (request/response path)**

The round-trip harness lets the server dispatcher reject an id by simulating an "older server". Add to `DemoRoundTripTest.kt` a test that calls a method id the dispatcher does not handle. Simplest deterministic approach: construct an envelope with a bogus `methodId` directly through the transport and assert the error code.

```kotlin
@Test
fun `unknown methodId returns METHOD_NOT_FOUND`() {
    // Drive the dispatcher with an id it does not have (simulates client newer than server).
    val dispatcher = IDemoService_Dispatcher(impl)
    val ex = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
        dispatcher.dispatch(0x0BADF00D.toInt(), android.os.Bundle())
    }
    // Before the fix this is a bare IllegalArgumentException; after, it is an IpcException(METHOD_NOT_FOUND).
    org.junit.Assert.assertTrue(ex is com.falcon.ipc.protocol.IpcException)
    org.junit.Assert.assertEquals(
        com.falcon.ipc.protocol.ErrorCode.METHOD_NOT_FOUND,
        (ex as com.falcon.ipc.protocol.IpcException).errorCode
    )
}
```

Run: `./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest --tests "com.falcon.demo.DemoRoundTripTest"`
Expected: FAIL — dispatch throws plain `IllegalArgumentException`, not `IpcException`.

- [ ] **Step 2: Make the generated dispatch throw a typed exception**

In `DispatcherGenerator.kt`, change the `dispatch` else branch (currently line ~100):

```kotlin
sb.appendLine("            else -> throw com.falcon.ipc.protocol.IpcException(com.falcon.ipc.protocol.ErrorCode.METHOD_NOT_FOUND, \"No method for id \$methodId in $ifaceName\")")
```

(`IpcException(val errorCode: Int, message)` already exists in `falcon-core/.../protocol/IpcException.kt`; it is on the consumer's classpath via `falcon-core`.)

- [ ] **Step 3: Run the request/response test to verify pass**

Run: `./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest --tests "com.falcon.demo.DemoRoundTripTest"`
Expected: PASS (the new test plus the existing 8).

- [ ] **Step 4: Surface the code at the host boundary**

In `IpcHostService.kt` `invoke()`, add an `IpcException` catch **before** the generic `Exception` catch so the real code (not `UNKNOWN`) is returned:

```kotlin
            } catch (e: com.falcon.ipc.protocol.IpcException) {
                IpcEnvelope.error(e.errorCode, e.message ?: "IPC error", request.requestId)
            } catch (e: Exception) {
                IpcEnvelope.error(ErrorCode.UNKNOWN, e.message ?: "Error", request.requestId)
            }
```

The generated proxy already maps `TransportResult.Error -> IpcException(code, …)`, and `callSafe` maps that to `IpcResult.Failure(METHOD_NOT_FOUND, …)`, so clients get a typed result end-to-end.

- [ ] **Step 5: Callback path — default reply is an error envelope**

In `IpcDispatcher.kt`, give the interface's default `invokeCallback` a real body so an unmatched callback id replies an error (the generated dispatcher's `else -> super.invokeCallback(...)` already routes here):

```kotlin
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope

interface IpcDispatcher {
    fun dispatch(methodId: Int, args: Bundle): Bundle
    fun eventFlow(methodId: Int): kotlinx.coroutines.flow.Flow<Bundle>? = null
    fun invokeCallback(methodId: Int, args: Bundle, reply: (IpcEnvelope) -> Unit) {
        reply(IpcEnvelope.error(ErrorCode.METHOD_NOT_FOUND, "No callback for methodId $methodId"))
    }
}
```

The generated proxy's callback stub already checks `event.isError` → `reply.onError(code, message)`, so the client's `IpcReply.onError(METHOD_NOT_FOUND, …)` fires.

- [ ] **Step 6: Event/stream path — emit an error envelope for unknown ids**

In `EventProxy.kt`, make the subscriber close the flow on an error envelope (today it blindly decodes):

```kotlin
override fun onEvent(event: IpcEnvelope) {
    if (event.isError) {
        close(com.falcon.ipc.protocol.IpcException(event.errorCode, event.errorMessage))
        return
    }
    val result: ChannelResult<Unit> = trySend(decode(event.argsBundle ?: Bundle()))
    if (result.isFailure && !result.isClosed) {
        FalconLogger.w("EventProxy", "Event buffer pressure on $eventKey")
    }
}
```

In `IpcHostService.kt` `subscribe()`, when the dispatcher has no flow for the methodId, push an error envelope instead of silently registering nothing:

```kotlin
if (methodId != null) {
    val serviceKey = parts[0]
    val dispatcher = serviceRegistry.getDispatcher(serviceKey)
    if (dispatcher == null || dispatcher.eventFlow(methodId) == null) {
        try { callback.onEvent(IpcEnvelope.error(ErrorCode.METHOD_NOT_FOUND, "No event for methodId $methodId")) }
        catch (e: Exception) { FalconLogger.w("Host", "event error reply failed: ${e.message}") }
        return
    }
    // ... existing eventCollector.onSubscribe(...) registration unchanged
}
```

(`eventFlow(methodId)` builds a cold flow only; it does not start collection, so probing it is cheap.)

- [ ] **Step 7: Add callback + event coverage to the test**

In `DemoRoundTripTest.kt`, drive an unknown id through the dispatcher's callback and event paths:

```kotlin
@Test
fun `unknown callback id replies METHOD_NOT_FOUND`() {
    val dispatcher = IDemoService_Dispatcher(impl)
    var code = 0
    dispatcher.invokeCallback(0x0BADF00D.toInt(), android.os.Bundle()) { env ->
        if (env.isError) code = env.errorCode
    }
    assertEquals(com.falcon.ipc.protocol.ErrorCode.METHOD_NOT_FOUND, code)
}

@Test
fun `unknown event id yields null flow`() {
    val dispatcher = IDemoService_Dispatcher(impl)
    org.junit.Assert.assertNull(dispatcher.eventFlow(0x0BADF00D.toInt()))
}
```

- [ ] **Step 8: Run core + demo suites**

Run: `./gradlew :falcon-core:test && ./gradlew :falcon-demo:clean :falcon-demo:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Document in USAGE.md**

Under "methodId 稳定性", add:

```markdown
> **方法匹配（多 App 独立升级）**：客户端调用服务端不存在的方法（增删/改签名 → methodId 不同）时，
> 服务端返回 `METHOD_NOT_FOUND`（1002），三种调用类型一致：`@IpcMethod` 抛 `IpcException`、
> `@IpcCallback` 走 `onError`、`@IpcEvent`/`@IpcStream` 让 Flow 以异常结束。
> 演进约定：**不要原地改方法签名/返回类型，新增方法**——老调用方会干净地拿到 `METHOD_NOT_FOUND`。
```

- [ ] **Step 10: Commit**

```bash
git add falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/DispatcherGenerator.kt \
        falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt \
        falcon-core/src/main/java/com/falcon/ipc/runtime/IpcDispatcher.kt \
        falcon-core/src/main/java/com/falcon/ipc/core/EventProxy.kt \
        falcon-demo/src/test/java/com/falcon/demo/DemoRoundTripTest.kt docs/USAGE.md
git commit -m "feat: return METHOD_NOT_FOUND for unmatched methodId across method/callback/event

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage** (the 6 review items → tasks):
1. suspend getService + main-thread guard → **Task 1** ✓
2. event backpressure → **Task 4** ✓
3. cross-process CI test → **Task 6** ✓
4. dispatch timeout → config → **Task 3** ✓
5. lightweight method matching (METHOD_NOT_FOUND, multi-app) → **Task 7** ✓
6. generator malicious-naming regression → **Task 2** ✓

**Type/name consistency:** `getServiceSuspending` (Task 1) is reused identically in Task 6. `callTimeoutMs` (existing) reused in Task 3. `eventBufferCapacity`/`eventOverflow` accessors defined in Task 4 Step 6 and consumed in Task 4 Step 5. `invokeTimeoutMs` defined in Task 5 Step 1, consumed Steps 4-5. Task 7 reuses the existing `ErrorCode.METHOD_NOT_FOUND` (1002) and `IpcException(errorCode, message)` — no new types or status codes; the `EventProxy.onEvent` change in Task 7 Step 6 is compatible with the buffer/overflow change in Task 4 (same method body).

**Placeholder scan:** no TBD/"handle appropriately"; every code step shows complete code.

**Known device dependency:** Task 6 requires the emulator; all others run on JVM (`:test` / `testDebugUnitTest`).

**Ordering note:** Task 3 Step 3 initializes `Falcon` in the demo round-trip `setUp()`; this is a prerequisite for Task 4's generated event flows (which call `Falcon.getInstance()`), so execute Task 3 before Task 4.
