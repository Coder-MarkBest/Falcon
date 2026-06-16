# Falcon IPC Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 11 audited issues across security correctness, performance, and robustness in the Falcon IPC framework.

**Architecture:** P0 security fixes wire real caller identity + rate limiting + SharedMemory zero-copy into the request path; P1 adds caching and moves blocking work off the main thread; P2 hardens reconnection and service lookup. Transport selection is isolated into a pure function for JVM unit testing; Android-framework-bound code (Binder PID, PackageManager, SharedMemory FD) is verified by review + integration tests.

**Tech Stack:** Kotlin, Android (Binder/AIDL, SharedMemory, ActivityManager), kotlinx.coroutines, JUnit.

**Spec:** `docs/superpowers/specs/2026-06-16-falcon-fixes-design.md`

**Test command:** `./gradlew :falcon-core:testDebugUnitTest`

---

## File Structure

**Create:**
- `falcon-core/src/main/java/com/falcon/ipc/transport/TransportSelector.kt` — pure size→transport decision
- `falcon-core/src/main/java/com/falcon/ipc/util/CallerResolver.kt` — PID→process name (cached)
- `falcon-core/src/test/java/com/falcon/ipc/transport/TransportSelectorTest.kt`

**Modify:**
- `security/RateLimiter.kt` — sliding window
- `core/MessageRouter.kt` — call rate limiter, method cache, callerPid param, `__check_service__`
- `core/IpcHostService.kt` — real caller PID, reuse SignatureGuard
- `protocol/IpcEnvelope.kt` (+ `.aidl` unchanged) — add `sharedMemory`/`largePayload`
- `transport/SharedMemoryTransport.kt` — stateless write/read
- `core/ProxyFactory.kt` — size-based send + shared-memory receive
- `protocol/IpcSerializer.kt` — fail-fast + ByteArray fix
- `protocol/ErrorCode.kt` — add `RATE_LIMITED`
- `security/SignatureGuard.kt` — per-UID cache
- `core/PeerManager.kt` — off-main-thread query, reconnect dedup
- `core/FalconManager.kt` — own IpcThreadPool, expose SignatureGuard, getService fix, shutdown threadpool

---

## Phase P0 — Security Correctness

### Task 1: ErrorCode.RATE_LIMITED

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/protocol/ErrorCode.kt`

- [ ] **Step 1: Read ErrorCode.kt and add the constant**

Add a `RATE_LIMITED` code alongside existing codes (pick an unused integer, follow the existing style in the file, e.g. after `UNAUTHORIZED`).

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/protocol/ErrorCode.kt
git commit -m "feat: add ErrorCode.RATE_LIMITED"
```

---

### Task 2: RateLimiter sliding window

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/security/RateLimiter.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/security/RateLimiterTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `RateLimiterTest.kt` (inject a controllable clock so the window is testable):

```kotlin
@Test
fun `allows calls within window then blocks over limit`() {
    var now = 0L
    val rl = RateLimiter(maxCallsPerSecond = 3, maxConcurrentCalls = 100, clock = { now })
    assertTrue(rl.tryAcquire(1)); rl.release(1)
    assertTrue(rl.tryAcquire(1)); rl.release(1)
    assertTrue(rl.tryAcquire(1)); rl.release(1)
    assertFalse(rl.tryAcquire(1)) // 4th within same second
}

@Test
fun `window slides - old timestamps expire`() {
    var now = 0L
    val rl = RateLimiter(maxCallsPerSecond = 2, maxConcurrentCalls = 100, clock = { now })
    assertTrue(rl.tryAcquire(1)); rl.release(1)
    assertTrue(rl.tryAcquire(1)); rl.release(1)
    assertFalse(rl.tryAcquire(1))
    now = 1001L // advance past 1s window
    assertTrue(rl.tryAcquire(1))
}

@Test
fun `concurrent limit enforced and released`() {
    val rl = RateLimiter(maxCallsPerSecond = 1000, maxConcurrentCalls = 2, clock = { 0L })
    assertTrue(rl.tryAcquire(1))
    assertTrue(rl.tryAcquire(1))
    assertFalse(rl.tryAcquire(1)) // concurrent == 2 already
    rl.release(1)
    assertTrue(rl.tryAcquire(1))
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.security.RateLimiterTest"`
Expected: FAIL (constructor has no `clock` param / sliding behavior absent)

- [ ] **Step 3: Implement sliding window**

Replace `RateLimiter.kt` body:

```kotlin
package com.falcon.ipc.security

import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RateLimiter(
    private val maxCallsPerSecond: Int = 1000,
    private val maxConcurrentCalls: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val windows = ConcurrentHashMap<Int, ArrayDeque<Long>>()
    private val concurrentCalls = ConcurrentHashMap<Int, AtomicInteger>()

    fun tryAcquire(callerPid: Int): Boolean {
        val concurrent = concurrentCalls.getOrPut(callerPid) { AtomicInteger(0) }
        if (concurrent.incrementAndGet() > maxConcurrentCalls) {
            concurrent.decrementAndGet()
            FalconLogger.w("Security", "Concurrent limit: PID=$callerPid")
            return false
        }

        val now = clock()
        val window = windows.getOrPut(callerPid) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() >= 1000L) {
                window.removeFirst()
            }
            if (window.size >= maxCallsPerSecond) {
                concurrent.decrementAndGet()
                FalconLogger.w("Security", "Rate limit: PID=$callerPid")
                return false
            }
            window.addLast(now)
        }
        return true
    }

    fun release(callerPid: Int) {
        concurrentCalls[callerPid]?.decrementAndGet()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.security.RateLimiterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/security/RateLimiter.kt falcon-core/src/test/java/com/falcon/ipc/security/RateLimiterTest.kt
git commit -m "feat: RateLimiter sliding window with injectable clock"
```

---

### Task 3: CallerResolver

**Files:**
- Create: `falcon-core/src/main/java/com/falcon/ipc/util/CallerResolver.kt`

- [ ] **Step 1: Write implementation** (Android-framework bound; no JVM unit test)

```kotlin
package com.falcon.ipc.util

import android.app.ActivityManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Resolves a Binder calling PID to a process name, cached. */
class CallerResolver(private val context: Context) {
    private val cache = ConcurrentHashMap<Int, String>()

    fun resolve(pid: Int): String {
        cache[pid]?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            ?: "pid:$pid"
        cache[pid] = name
        return name
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/util/CallerResolver.kt
git commit -m "feat: add CallerResolver (PID to process name, cached)"
```

---

### Task 4: MessageRouter — caller PID, rate limiting, method cache, __check_service__

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/core/MessageRouterTest.kt`

- [ ] **Step 1: Write failing tests**

Add to `MessageRouterTest.kt`. Use a small fake service registered in a `ServiceRegistry`. (`__check_service__` returns Boolean; rate limit denial throws.)

```kotlin
@Test
fun `check_service returns true for registered and false otherwise`() {
    val registry = ServiceRegistry()
    registry.register(EchoService::class, EchoServiceImpl())
    val router = MessageRouter(registry, MonitorFacade(), PermissionChecker(emptyMap()),
        RateLimiter(clock = { 0L }))
    val key = EchoService::class.qualifiedName!!
    val present = router.handleLocal(
        IpcEnvelope(serviceKey = "", method = "__check_service__", args = key.toByteArray()),
        "proc", 1234)
    assertEquals(true, present)
    val absent = router.handleLocal(
        IpcEnvelope(serviceKey = "", method = "__check_service__", args = "no.such.Svc".toByteArray()),
        "proc", 1234)
    assertEquals(false, absent)
}

@Test
fun `rate limit denial throws`() {
    val registry = ServiceRegistry()
    registry.register(EchoService::class, EchoServiceImpl())
    val router = MessageRouter(registry, MonitorFacade(), PermissionChecker(emptyMap()),
        RateLimiter(maxCallsPerSecond = 1, clock = { 0L }))
    val key = EchoService::class.qualifiedName!!
    fun call() = router.handleLocal(
        IpcEnvelope(serviceKey = key, method = "echo", args = IpcSerializer.serializeArgs(arrayOf("hi"))),
        "proc", 1234)
    call() // first allowed
    assertThrows(IllegalStateException::class.java) { call() } // second over limit
}
```

If `EchoService`/`EchoServiceImpl` test fixtures don't exist, define them in the test file: an `IpcService` interface with `fun echo(s: String): String` and an impl returning the input.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.core.MessageRouterTest"`
Expected: FAIL (handleLocal signature has no callerPid; no `__check_service__`; no rate limiting)

- [ ] **Step 3: Implement**

Rewrite `MessageRouter.kt`:

```kotlin
package com.falcon.ipc.core

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.MonitorFacade
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.security.PermissionChecker
import com.falcon.ipc.security.RateLimiter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker()
) {
    private var interceptors: List<IpcInterceptor> = emptyList()
    private val methodCache = ConcurrentHashMap<String, Method>()

    fun setInterceptors(interceptors: List<IpcInterceptor>) {
        this.interceptors = interceptors
    }

    fun handleLocal(envelope: IpcEnvelope, callerProcess: String, callerPid: Int): Any? {
        if (envelope.method == "__check_service__") {
            val key = String(envelope.args ?: ByteArray(0))
            return registry.getService(key) != null
        }

        if (!permissionChecker.check(envelope.serviceKey, callerProcess)) {
            throw SecurityException("Permission denied: $callerProcess → ${envelope.serviceKey}")
        }
        if (!rateLimiter.tryAcquire(callerPid)) {
            throw IllegalStateException("Rate limit exceeded for PID=$callerPid")
        }
        try {
            if (!circuitBreaker.allowCall(envelope.serviceKey)) {
                throw IllegalStateException("Circuit open for ${envelope.serviceKey}")
            }
            val service = registry.getService(envelope.serviceKey)
                ?: throw IllegalStateException("Service not found: ${envelope.serviceKey}")

            val probeArgs = IpcSerializer.deserializeArgs(envelope.args ?: ByteArray(0), emptyArray())
            val method = resolveMethod(service.javaClass, envelope.method, probeArgs.size)
                ?: throw IllegalStateException("Method not found: ${envelope.method}")

            val startTime = System.currentTimeMillis()
            return try {
                val args = IpcSerializer.deserializeArgs(envelope.args ?: ByteArray(0), method.parameterTypes)
                val result = method.invoke(service, *args)
                monitor.recordCall(envelope.serviceKey, envelope.method, true, System.currentTimeMillis() - startTime)
                circuitBreaker.recordSuccess(envelope.serviceKey)
                result
            } catch (e: Exception) {
                monitor.recordCall(envelope.serviceKey, envelope.method, false, System.currentTimeMillis() - startTime)
                circuitBreaker.recordFailure(envelope.serviceKey)
                throw e
            }
        } finally {
            rateLimiter.release(callerPid)
        }
    }

    private fun resolveMethod(clazz: Class<*>, methodName: String, argCount: Int): Method? {
        val key = "${clazz.name}#$methodName/$argCount"
        methodCache[key]?.let { return it }
        val found = findMethod(clazz, methodName, argCount) ?: return null
        found.isAccessible = true
        methodCache[key] = found
        return found
    }

    private fun findMethod(clazz: Class<*>, methodName: String, argCount: Int): Method? {
        return clazz.methods.filter { it.name == methodName }
            .let { c -> if (c.size == 1) c.first() else c.firstOrNull { it.parameterCount == argCount } ?: c.firstOrNull() }
            ?: clazz.interfaces.flatMap { it.methods.toList() }
                .filter { it.name == methodName }
                .let { c -> if (c.size == 1) c.first() else c.firstOrNull { it.parameterCount == argCount } ?: c.firstOrNull() }
    }
}
```

- [ ] **Step 4: Update callers of handleLocal**

`BatchRequest.kt:25-30` calls `router.handleLocal(envelope, callerProcess)`. Change `execute(batch, callerProcess)` to `execute(batch, callerProcess, callerPid)` and pass `callerPid` through to `handleLocal`. Update `BatchRequestTest.kt` calls accordingly (pass a dummy pid like `0`).

- [ ] **Step 5: Run tests**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.core.MessageRouterTest" --tests "com.falcon.ipc.core.BatchRequestTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt falcon-core/src/main/java/com/falcon/ipc/core/BatchRequest.kt falcon-core/src/test/java/com/falcon/ipc/core/MessageRouterTest.kt falcon-core/src/test/java/com/falcon/ipc/core/BatchRequestTest.kt
git commit -m "feat: MessageRouter rate limiting, method cache, caller PID, __check_service__"
```

---

### Task 5: IpcHostService — real caller PID + reuse SignatureGuard

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt` (expose signatureGuard + callerResolver)

- [ ] **Step 1: Expose dependencies in FalconManager**

In `FalconManager.kt`, change `private val signatureGuard` to `internal val signatureGuard` and add `internal val callerResolver = CallerResolver(context)` (import `com.falcon.ipc.util.CallerResolver`).

- [ ] **Step 2: Update IpcHostService**

In `IpcHostService.kt`:
- In `onCreate`, replace `signatureGuard = SignatureGuard().apply { init(this@IpcHostService) }` with `signatureGuard = falconManager.signatureGuard`, and add `callerResolver = falconManager.callerResolver`. Declare `private lateinit var callerResolver: CallerResolver` (import it).
- In `hostBinder.invoke`, replace caller-process derivation:

```kotlin
override fun invoke(request: IpcEnvelope): IpcEnvelope {
    val callingUid = Binder.getCallingUid()
    val callingPid = Binder.getCallingPid()
    if (!signatureGuard.verify(this@IpcHostService, callingUid)) {
        return IpcEnvelope.error(ErrorCode.UNAUTHORIZED, "Signature mismatch")
    }
    val callerProcess = callerResolver.resolve(callingPid)
    return try {
        val result = messageRouter.handleLocal(request, callerProcess, callingPid)
        IpcEnvelope.response(request.requestId,
            com.falcon.ipc.protocol.IpcSerializer.serializeResult(result))
    } catch (e: SecurityException) {
        IpcEnvelope.error(ErrorCode.PERMISSION_DENIED, e.message ?: "Denied", request.requestId)
    } catch (e: IllegalStateException) {
        IpcEnvelope.error(ErrorCode.RATE_LIMITED, e.message ?: "Rate limited", request.requestId)
    } catch (e: Exception) {
        IpcEnvelope.error(ErrorCode.UNKNOWN, e.message ?: "Error", request.requestId)
    }
}
```

Note: capturing `callingUid`/`callingPid` at method entry is required — they are only valid inside the Binder transaction frame.

- [ ] **Step 3: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt
git commit -m "fix: use real Binder caller PID for permission checks; reuse SignatureGuard"
```

---

### Task 6: IpcSerializer fail-fast + ByteArray fix

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/protocol/IpcSerializer.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/protocol/IpcSerializerTest.kt`

NOTE: `IpcSerializer` uses `android.os.Parcel`. The existing `IpcSerializerTest` is annotated `@RunWith(RobolectricTestRunner::class)` (confirmed) — Parcel works on JVM via Robolectric. Add the new tests to that existing class so they inherit the Robolectric runner.

- [ ] **Step 1: Write failing test (add to the existing `@RunWith(RobolectricTestRunner::class)` class)**

```kotlin
@Test
fun `unsupported type throws instead of silently stringifying`() {
    class Weird(val x: Int)
    assertThrows(IllegalArgumentException::class.java) {
        IpcSerializer.serializeArgs(arrayOf(Weird(1)))
    }
}

@Test
fun `byte array round trips`() {
    val original = byteArrayOf(1, 2, 3, 4, 5)
    val bytes = IpcSerializer.serializeArgs(arrayOf(original))
    val out = IpcSerializer.deserializeArgs(bytes, arrayOf(ByteArray::class.java))
    assertArrayEquals(original, out[0] as ByteArray)
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.protocol.IpcSerializerTest"`
Expected: FAIL on the unsupported-type test (currently stringifies)

- [ ] **Step 3: Implement**

In `IpcSerializer.kt`:
- `ByteArray` branch: remove `parcel.writeInt(arg.size)`, keep only `parcel.writeByteArray(arg)`.
- `TYPE_BYTE_ARRAY` deserialize branch: replace manual size read + `readByteArray` with `parcel.createByteArray() ?: ByteArray(0)`.
- Replace the `else ->` fallback in `serializeArg` with:

```kotlin
else -> throw IllegalArgumentException(
    "Unsupported IPC type: ${arg!!::class.java.name}. Implement Parcelable or use a supported type."
)
```

Remove the now-unused `json`/kotlinx.serialization imports if nothing else uses them.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.protocol.IpcSerializerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/protocol/IpcSerializer.kt falcon-core/src/test/java/com/falcon/ipc/protocol/IpcSerializerTest.kt
git commit -m "fix: IpcSerializer fail-fast on unsupported types; fix ByteArray double-length"
```

---

### Task 7: TransportSelector (pure function)

**Files:**
- Create: `falcon-core/src/main/java/com/falcon/ipc/transport/TransportSelector.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/transport/TransportSelectorTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.falcon.ipc.transport

import org.junit.Assert.*
import org.junit.Test

class TransportSelectorTest {
    @Test fun `below threshold uses binder`() {
        assertFalse(TransportSelector.shouldUseSharedMemory(63 * 1024, 64 * 1024))
    }
    @Test fun `at threshold uses shared memory`() {
        assertTrue(TransportSelector.shouldUseSharedMemory(64 * 1024, 64 * 1024))
    }
    @Test fun `above threshold uses shared memory`() {
        assertTrue(TransportSelector.shouldUseSharedMemory(128 * 1024, 64 * 1024))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.transport.TransportSelectorTest"`
Expected: FAIL (unresolved reference TransportSelector)

- [ ] **Step 3: Implement**

```kotlin
package com.falcon.ipc.transport

object TransportSelector {
    fun shouldUseSharedMemory(payloadSize: Int, threshold: Int): Boolean =
        payloadSize >= threshold
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.transport.TransportSelectorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/transport/TransportSelector.kt falcon-core/src/test/java/com/falcon/ipc/transport/TransportSelectorTest.kt
git commit -m "feat: add TransportSelector pure size-based decision"
```

---

### Task 8: SharedMemoryTransport stateless write/read

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/transport/SharedMemoryTransport.kt`

Android-framework bound (SharedMemory FD); no JVM unit test — verified by review + benchmark.

- [ ] **Step 1: Rewrite SharedMemoryTransport**

```kotlin
package com.falcon.ipc.transport

import android.os.Build
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.falcon.ipc.util.FalconLogger
import java.nio.ByteBuffer

/**
 * Stateless helper for zero-copy payloads. The SharedMemory object itself is
 * Parcelable and travels in the IpcEnvelope across Binder (kernel dups the FD),
 * so no local registry / token model is needed.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
class SharedMemoryTransport(
    private val maxAllocationSize: Int = 32 * 1024 * 1024
) {
    fun writeToShared(data: ByteArray): SharedMemory? {
        if (data.size > maxAllocationSize) {
            FalconLogger.w("SharedMemory", "Payload too large: ${data.size} > $maxAllocationSize")
            return null
        }
        val shm = SharedMemory.create("falcon_shm", data.size.coerceAtLeast(1))
        val buffer: ByteBuffer = shm.mapReadWrite()
        try {
            buffer.put(data)
        } finally {
            SharedMemory.unmap(buffer)
        }
        shm.setProtect(android.system.OsConstants.PROT_READ)
        return shm
    }

    fun readFromShared(shm: SharedMemory): ByteArray {
        val buffer: ByteBuffer = shm.mapReadOnly()
        try {
            val data = ByteArray(shm.size)
            buffer.get(data)
            return data
        } finally {
            SharedMemory.unmap(buffer)
        }
    }
}
```

Note: `SharedMemory.unmap` and `setProtect` are public API on API 27+ (the prior reflection hack is no longer needed). The class is now `@RequiresApi(O_MR1)`; callers must guard on SDK level.

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (if `androidx.annotation` not present, drop the `@RequiresApi` import and annotation, keep an inline SDK check in callers)

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/transport/SharedMemoryTransport.kt
git commit -m "refactor: SharedMemoryTransport to stateless FD-passing model"
```

---

### Task 9: IpcEnvelope carries SharedMemory

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/protocol/IpcEnvelope.kt`

- [ ] **Step 1: Add fields + parcel marshalling**

Add to the data class constructor (after `errorMessage`):

```kotlin
val largePayload: Boolean = false,
val sharedMemory: android.os.SharedMemory? = null
```

In the `constructor(parcel: Parcel)`, read after `errorMessage`:

```kotlin
largePayload = parcel.readByte() != 0.toByte(),
sharedMemory = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1)
    parcel.readParcelable(android.os.SharedMemory::class.java.classLoader) else null
```

In `writeToParcel`, after writing `errorMessage`:

```kotlin
parcel.writeByte(if (largePayload) 1 else 0)
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1)
    parcel.writeParcelable(sharedMemory, flags)
```

Leave `equals`/`hashCode` keyed on existing fields (do not include `sharedMemory`; FDs aren't value-comparable). The `.aidl` parcelable declaration needs no change.

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/protocol/IpcEnvelope.kt
git commit -m "feat: IpcEnvelope carries SharedMemory for zero-copy large payloads"
```

---

### Task 10: Wire size-based routing into ProxyFactory + MessageRouter + IpcHostService

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/ProxyFactory.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt`

Android-framework bound; verified by review + benchmark. Threshold and transport are passed in so the decision uses `TransportSelector`.

- [ ] **Step 1: Add a payload-extraction helper to MessageRouter**

In `MessageRouter`, add a helper to obtain request bytes from either source, and accept a `SharedMemoryTransport?` + threshold via constructor (default null for tests):

```kotlin
// constructor adds:
//   private val sharedMemoryTransport: SharedMemoryTransport? = null
private fun payloadBytes(envelope: IpcEnvelope): ByteArray {
    if (envelope.largePayload && envelope.sharedMemory != null && sharedMemoryTransport != null) {
        return try { sharedMemoryTransport.readFromShared(envelope.sharedMemory) }
        finally { envelope.sharedMemory.close() }
    }
    return envelope.args ?: ByteArray(0)
}
```

Replace the two `envelope.args ?: ByteArray(0)` uses inside `handleLocal` with `val bytes = payloadBytes(envelope)` computed once, then use `bytes` for both `probeArgs` and the real deserialize.

- [ ] **Step 2: Result path in IpcHostService**

In `IpcHostService.invoke`, after computing `result`, build the response with size-based transport. Add a `sharedMemoryTransport` + threshold reference (from `falconManager` — see Step 4) and helper:

```kotlin
val resultBytes = IpcSerializer.serializeResult(result)
val response = if (resultBytes.size >= threshold && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    val shm = sharedMemoryTransport?.writeToShared(resultBytes)
    if (shm != null) IpcEnvelope(requestId = request.requestId, largePayload = true, sharedMemory = shm)
    else IpcEnvelope.response(request.requestId, resultBytes)
} else IpcEnvelope.response(request.requestId, resultBytes)
return response
```

- [ ] **Step 3: Request + response path in ProxyFactory**

`IpcInvocationHandler` needs the threshold + a `SharedMemoryTransport` (pass via `ProxyFactory.create(... , sharedMemoryTransport, threshold)`; default null/64KB to keep existing call sites compiling, then update `FalconManager.getService` call site in Task 13).

In `executeIpcCall`:
- After `serializeArgs`, if `TransportSelector.shouldUseSharedMemory(serializedArgs.size, threshold)` and transport non-null and SDK≥27: write to shared, build `IpcEnvelope(serviceKey, method, args = null, largePayload = true, sharedMemory = shm)`; else build as today.
- On `TransportResult.Success`, if the underlying response carried shared memory, the bytes are already resolved by BinderTransport (see Step 4) — keep deserializing `result.data as ByteArray`.

- [ ] **Step 4: BinderTransport resolves shared-memory responses**

In `BinderTransport.invoke`, after `host.invoke(envelope)`, if `response.largePayload && response.sharedMemory != null`: read bytes via an injected `SharedMemoryTransport`, `close()` the shm, and return `TransportResult.Success(bytes)`. Otherwise `TransportResult.Success(response.args)`. Add `SharedMemoryTransport?` to `BinderTransport` constructor (default null).

- [ ] **Step 5: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run full core test suite (no regressions)**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/ProxyFactory.kt falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt falcon-core/src/main/java/com/falcon/ipc/transport/BinderTransport.kt
git commit -m "feat: size-based SharedMemory routing in request and response paths"
```

---

## Phase P1 — Performance

### Task 11: SignatureGuard per-UID cache

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt`

- [ ] **Step 1: Add cache**

Add `private val verifiedUids = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()`. At the top of `verify`, `verifiedUids[callingUid]?.let { return it }`. Before each `return true/false` in `verify`, store the result in the cache (wrap the body so every return path caches). Keep the UID-mismatch fast path caching `false`.

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt
git commit -m "perf: cache signature verification result per UID"
```

---

### Task 12: IpcThreadPool ownership + PeerManager off-main-thread query + lifecycle

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/PeerManager.kt`

- [ ] **Step 1: FalconManager owns the thread pool and passes it to PeerManager**

Add `internal val threadPool = IpcThreadPool()`. Change `PeerManager(context, registryUri)` construction to `PeerManager(context, registryUri, threadPool)`. In both `stop()` and `shutdown()`, after stopping peers, call `threadPool.shutdown()`.

- [ ] **Step 2: PeerManager uses the pool for the query**

Add constructor param `private val threadPool: IpcThreadPool`. In `refreshPeers`, run the `contentResolver.query` block on `threadPool.submit { ... }`; collect process names; then post the `bindPeer` loop back to the main thread via the existing `handler.post { ... }`.

- [ ] **Step 3: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run IpcThreadPool tests (no regression)**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.core.IpcThreadPoolTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt falcon-core/src/main/java/com/falcon/ipc/core/PeerManager.kt
git commit -m "perf: own IpcThreadPool, move registry query off main thread, shutdown pool"
```

---

## Phase P2 — Robustness

### Task 13: PeerManager reconnect dedup + getService fix + logging

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/PeerManager.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`

- [ ] **Step 1: Reconnect dedup in PeerManager**

Add `private val reconnecting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()`. In `scheduleReconnect`, `if (!reconnecting.add(processName)) return` at the top. In `onServiceConnected`, `reconnecting.remove(processName)`. In the `postDelayed` body, `reconnecting.remove(processName)` before calling `bindPeer` so a later failure can re-schedule. Replace the empty `catch (_: Exception) {}` in `stop()` with `catch (e: Exception) { FalconLogger.w("Peer", "stop cleanup: ${e.message}") }`.

- [ ] **Step 2: getService fix in FalconManager**

In `getService`, delete the step-3 optimistic fallback block (the `firstPeer` proxy creation). The loop already returns a proxy only after a successful `__check_service__` invoke; wrap the `peer.transport.invoke(checkEnvelope)` so it only creates a proxy when the result indicates the service exists, and replace the empty `catch` with `catch (e: Exception) { FalconLogger.w("Falcon", "peer probe failed: ${e.message}") }`. Return `null` at the end. Also pass `sharedMemoryTransport` + `config.transport.sharedMemoryThreshold` into `ProxyFactory.create(...)` (from Task 10 Step 3).

- [ ] **Step 3: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run full suite**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/PeerManager.kt falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt
git commit -m "fix: reconnect dedup, remove optimistic proxy, log swallowed exceptions"
```

---

## Final Verification

### Task 14: Full build + test + benchmark sanity

- [ ] **Step 1: Full core test suite**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: PASS (all)

- [ ] **Step 2: Full build (all modules incl. KSP + benchmark)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Update CLAUDE.md note on SharedMemory model (if architecture text now stale)**

Verify the "MessageRouter auto-selects transport based on payload size" and SharedMemory token wording match the new FD-passing model; update the security-model bullet (SharedMemory now FD-passed, not HMAC-token) if needed.

- [ ] **Step 4: Commit any doc updates**

```bash
git add CLAUDE.md
git commit -m "docs: align CLAUDE.md with FD-passing SharedMemory model"
```

---

## Self-Review Notes

- **Spec coverage:** Fix 1→Task 3,5; Fix 2→Task 1,2,4; Fix 3→Task 7,8,9,10; Fix 4→Task 6; Fix 5→Task 4; Fix 6→Task 11; Fix 7→Task 12; Fix 8→Task 12; Fix 9→Task 13; Fix 10→Task 13; Fix 11→Task 5,13. All covered.
- **Signatures:** `handleLocal(envelope, callerProcess, callerPid)` consistent across Task 4/5/10. `RateLimiter(maxCallsPerSecond, maxConcurrentCalls, clock)` consistent in Task 2/4. `ProxyFactory.create(serviceClass, key, transport, sharedMemoryTransport, threshold)` introduced in Task 10, consumed in Task 13.
- **Android-bound tasks** (3, 8, 9, 10, 11, 12) verified by build + review + benchmark, not JVM unit tests, per spec.
