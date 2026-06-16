# P1 — Remove Over-Engineering Dead Weight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove four over-engineered subsystems (SharedMemory hybrid transport, CircuitBreaker, OTA/version negotiation, ThreadPool priority queue) that don't match the confirmed deployment (no large payloads, same-version, third-party callers).

**Architecture:** Pure subtraction. No replacements needed — removing the SharedMemory branch leaves the existing Binder path intact; removing CircuitBreaker/OTA removes unused gates/fields; ThreadPool keeps its plain executor. Each task is independent, ends green, and is one commit.

**Tech Stack:** Kotlin, Android (Binder/AIDL), JUnit/Robolectric.

**Spec:** `docs/superpowers/specs/2026-06-17-overengineering-audit.md` (P1 section)

**Build/test:** `./gradlew :falcon-core:compileDebugKotlin`, `./gradlew :falcon-core:testDebugUnitTest`, full `./gradlew build`. (JDK 17 is preconfigured via `~/.gradle/gradle.properties`.)

**Out of scope (do NOT touch):** `__check_service__` (coupled to P2/P3), `BatchRequest`/`EventBus` (optional features), `DiagnosticsManager` (used by MonitorFacade), `RateLimiter`/`PermissionChecker`/`SignatureGuard`/`Monitor`/`TimeoutController` (kept).

---

## Task 1: Remove SharedMemory hybrid transport (full chain)

Removing the ≥64KB SharedMemory path. The Binder inline-`args` path remains the only transport. This touches 8 files atomically (they compile together), so it is one commit.

**Files:**
- Delete: `falcon-core/src/main/java/com/falcon/ipc/transport/SharedMemoryTransport.kt`
- Delete: `falcon-core/src/main/java/com/falcon/ipc/transport/TransportSelector.kt`
- Delete: `falcon-core/src/test/java/com/falcon/ipc/transport/TransportSelectorTest.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/protocol/IpcEnvelope.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/ProxyFactory.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/transport/BinderTransport.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/PeerManager.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`

- [ ] **Step 1: Delete the three SharedMemory/TransportSelector files**

```bash
git rm falcon-core/src/main/java/com/falcon/ipc/transport/SharedMemoryTransport.kt \
       falcon-core/src/main/java/com/falcon/ipc/transport/TransportSelector.kt \
       falcon-core/src/test/java/com/falcon/ipc/transport/TransportSelectorTest.kt
```

- [ ] **Step 2: IpcEnvelope.kt — remove the two fields + parcel handling + FD describeContents**

Remove the `largePayload` and `sharedMemory` constructor properties. In `constructor(parcel: Parcel)` remove the two lines that read `largePayload` and `sharedMemory`. In `writeToParcel` remove the `parcel.writeByte(if (largePayload) 1 else 0)` line and the SDK-guarded `parcel.writeParcelable(sharedMemory, flags)` line. Revert `describeContents` to:

```kotlin
override fun describeContents(): Int = 0
```

Leave all other fields (serviceKey, method, args, requestId, timestamp, traceId, isError, errorCode, errorMessage), `equals`/`hashCode`, and the `error`/`response` factories unchanged. CRITICAL: the read order in `constructor(parcel)` must still mirror the write order in `writeToParcel` after removal — both lose the same trailing two reads/writes, so symmetry is preserved.

- [ ] **Step 3: MessageRouter.kt — drop sharedMemoryTransport param and payloadBytes helper**

Remove the `sharedMemoryTransport: SharedMemoryTransport? = null` constructor parameter and the `import com.falcon.ipc.transport.SharedMemoryTransport`. Delete the `payloadBytes` function. In `handleLocal`, replace `val bytes = payloadBytes(envelope)` with `val bytes = envelope.args ?: ByteArray(0)`. The constructor becomes:

```kotlin
class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter,
    private val circuitBreaker: CircuitBreaker = CircuitBreaker()
) {
```
(circuitBreaker stays for now; it is removed in Task 2.)

- [ ] **Step 4: ProxyFactory.kt — remove SharedMemory send path**

Remove the `sharedMemoryTransport`/`threshold` params from both `create(...)` and `IpcInvocationHandler`, and the imports `android.os.Build`, `com.falcon.ipc.transport.SharedMemoryTransport`, `com.falcon.ipc.transport.TransportSelector`. In `executeIpcCall`, replace the envelope-build + close block with the simple inline form:

```kotlin
private fun executeIpcCall(
    methodName: String,
    args: Array<Any?>,
    paramTypes: Array<Class<*>>,
    method: Method
): Any? {
    val serializedArgs = IpcSerializer.serializeArgs(args)
    val envelope = IpcEnvelope(serviceKey = serviceKey, method = methodName, args = serializedArgs)
    val result = transport.invoke(envelope)

    return when (result) {
        is TransportResult.Success -> {
            val data = result.data
            if (data is ByteArray) IpcSerializer.deserializeResult(data, method.returnType) else data
        }
        is TransportResult.Error -> throw RuntimeException("IPC error [${result.code}]: ${result.message}")
    }
}
```
And `create`:
```kotlin
fun <T : IpcService> create(
    serviceClass: Class<T>,
    serviceKey: String,
    transport: IpcTransport
): T {
    return Proxy.newProxyInstance(
        serviceClass.classLoader,
        arrayOf(serviceClass),
        IpcInvocationHandler(serviceKey, transport)
    ) as T
}
```
with `private class IpcInvocationHandler(private val serviceKey: String, private val transport: IpcTransport) : InvocationHandler`.

- [ ] **Step 5: IpcHostService.kt — remove response SharedMemory path**

Remove fields `sharedMemoryTransport` and `threshold`, their assignments in `onCreate`, and the imports `com.falcon.ipc.transport.SharedMemoryTransport` and `com.falcon.ipc.transport.TransportSelector`. In `invoke`, replace the result-response block with:

```kotlin
val result = messageRouter.handleLocal(request, callerProcess, callingPid)
IpcEnvelope.response(request.requestId,
    com.falcon.ipc.protocol.IpcSerializer.serializeResult(result))
```
Keep the `try/catch` mapping (SecurityException→PERMISSION_DENIED, IllegalStateException→RATE_LIMITED, Exception→UNKNOWN) and the `@SuppressLint("BinderGetCallingInMainThread")` on onBind. `Build` import may become unused — remove it only if nothing else in the file uses it.

- [ ] **Step 6: BinderTransport.kt — remove SharedMemory response read**

Remove the `sharedMemoryTransport: SharedMemoryTransport? = null` constructor param and its import. `invoke` becomes:

```kotlin
override fun invoke(envelope: IpcEnvelope): TransportResult {
    return try {
        val response = host.invoke(envelope)
        if (response.isError) {
            TransportResult.Error(response.errorCode, response.errorMessage)
        } else {
            TransportResult.Success(response.args)
        }
    } catch (e: Exception) {
        FalconLogger.e("BinderTransport", "Invoke failed", e)
        TransportResult.Error(-1, e.message ?: "Unknown error")
    }
}
```

- [ ] **Step 7: PeerManager.kt — drop sharedMemoryTransport**

Remove the `sharedMemoryTransport: SharedMemoryTransport? = null` constructor param and its import. In `onServiceConnected`, change `BinderTransport(host, sharedMemoryTransport)` back to `BinderTransport(host)`.

- [ ] **Step 8: FalconManager.kt — remove SharedMemory wiring**

Remove the `sharedMemoryTransport` field (the SDK-gated block) and `sharedMemoryThreshold` getter, and the `import com.falcon.ipc.transport.SharedMemoryTransport`. Update:
- `MessageRouter(serviceRegistry, monitor, permissionChecker, rateLimiter)` (drop the named `sharedMemoryTransport` arg).
- `PeerManager(context, registryUri, threadPool = threadPool)` (drop the named `sharedMemoryTransport` arg).
- In `getService`, `ProxyFactory.create(serviceClass.java, key, peer.transport)` (drop the named `sharedMemoryTransport`/`threshold` args).

- [ ] **Step 9: Build + full test suite**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL
Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (existing tests construct envelopes with plain `args` and transports without SharedMemory, so they pass unchanged). If any test referenced `largePayload`, `sharedMemory`, `TransportSelector`, or `SharedMemoryTransport`, update/remove it and report.

- [ ] **Step 10: Commit**

```bash
git add -A
git status   # confirm only intended files
git commit -m "refactor: remove SharedMemory hybrid transport (no large payloads in scope)"
```

---

## Task 2: Remove CircuitBreaker

CircuitBreaker protects callers from failing callees (network/microservice pattern); it doesn't fit same-device Binder. Abuse protection is RateLimiter's job (kept).

**Files:**
- Delete: `falcon-core/src/main/java/com/falcon/ipc/core/CircuitBreaker.kt`
- Delete: `falcon-core/src/test/java/com/falcon/ipc/core/CircuitBreakerTest.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`

- [ ] **Step 1: Delete files**

```bash
git rm falcon-core/src/main/java/com/falcon/ipc/core/CircuitBreaker.kt \
       falcon-core/src/test/java/com/falcon/ipc/core/CircuitBreakerTest.kt
```

- [ ] **Step 2: MessageRouter.kt — remove the circuit breaker param and usages**

Remove the `circuitBreaker: CircuitBreaker = CircuitBreaker()` constructor param. In `handleLocal` remove the `if (!circuitBreaker.allowCall(...)) throw ...` block, and the `circuitBreaker.recordSuccess(...)` / `circuitBreaker.recordFailure(...)` calls (keep the `monitor.recordCall` calls). The success branch becomes:
```kotlin
val result = method.invoke(service, *args)
monitor.recordCall(envelope.serviceKey, envelope.method, true, System.currentTimeMillis() - startTime)
result
```
and the catch branch:
```kotlin
} catch (e: Exception) {
    monitor.recordCall(envelope.serviceKey, envelope.method, false, System.currentTimeMillis() - startTime)
    throw e
}
```
Final constructor:
```kotlin
class MessageRouter(
    private val registry: ServiceRegistry,
    private val monitor: MonitorFacade,
    private val permissionChecker: PermissionChecker,
    private val rateLimiter: RateLimiter
) {
```

- [ ] **Step 3: FalconManager.kt — remove the field**

Remove `val circuitBreaker = CircuitBreaker()`.

- [ ] **Step 4: Build + tests**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If `MessageRouterTest` (or others) passed a `circuitBreaker` arg positionally, fix those constructor calls. The existing MessageRouterTest uses `MessageRouter(registry, MonitorFacade(), PermissionChecker(...), RateLimiter(...))` which still matches — verify.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove CircuitBreaker (no fit for same-device Binder)"
```

---

## Task 3: Remove OTA/version negotiation

Same-version deployment makes `OtaCompatManager` / `ServiceVersion` / `ServiceVersionRegistry` dead weight.

**Files:**
- Delete: `falcon-core/src/main/java/com/falcon/ipc/core/OtaCompat.kt`
- Delete: `falcon-core/src/main/java/com/falcon/ipc/core/ServiceVersion.kt`
- Delete: `falcon-core/src/test/java/com/falcon/ipc/core/OtaCompatTest.kt`
- Delete: `falcon-core/src/test/java/com/falcon/ipc/core/ServiceVersionTest.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`

- [ ] **Step 1: Confirm no other references**

Run: `grep -rn "OtaCompat\|ServiceVersion\|versionRegistry\|otaCompat" falcon-core/src/main`
Expected: only the two FalconManager field lines. If anything else references them, STOP and report.

- [ ] **Step 2: Delete files**

```bash
git rm falcon-core/src/main/java/com/falcon/ipc/core/OtaCompat.kt \
       falcon-core/src/main/java/com/falcon/ipc/core/ServiceVersion.kt \
       falcon-core/src/test/java/com/falcon/ipc/core/OtaCompatTest.kt \
       falcon-core/src/test/java/com/falcon/ipc/core/ServiceVersionTest.kt
```

- [ ] **Step 3: FalconManager.kt — remove the two fields**

Remove `val versionRegistry = ServiceVersionRegistry()` and `val otaCompat = OtaCompatManager()`.

- [ ] **Step 4: Build + tests**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove OTA/version negotiation (same-version deployment)"
```

---

## Task 4: Simplify IpcThreadPool (remove priority queue)

`IpcPriority` / `priorityQueue` / `PriorityRunnable` are unused (no caller passes a priority). Keep a plain background executor for off-main-thread work (PeerManager uses `submit { }`).

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/IpcThreadPool.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/core/IpcThreadPoolTest.kt`

- [ ] **Step 1: Read the existing test first**

Read `falcon-core/src/test/java/com/falcon/ipc/core/IpcThreadPoolTest.kt`. Note any tests that exercise `IpcPriority`, `priorityQueue`, or `submit(priority, ...)` — these will be updated to the simplified API in Step 3.

- [ ] **Step 2: Rewrite IpcThreadPool.kt (drop priority machinery)**

```kotlin
package com.falcon.ipc.core

import com.falcon.ipc.util.FalconLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ThreadPoolConfig(
    val corePoolSize: Int = 4,
    val maxPoolSize: Int = 8,
    val keepAliveMs: Long = 60_000
)

class IpcThreadPool(
    private val config: ThreadPoolConfig = ThreadPoolConfig()
) {
    private val threadCounter = AtomicInteger(0)

    private val executor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            config.corePoolSize,
            config.maxPoolSize,
            config.keepAliveMs,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            ThreadFactory { r ->
                Thread(r, "falcon-io-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
            }
        )
    }

    val dispatcher: CoroutineDispatcher by lazy { executor.asCoroutineDispatcher() }

    fun submit(block: () -> Unit) {
        executor.execute(block)
    }

    fun <T> submitCallable(block: () -> T): Future<T> = executor.submit(Callable { block() })

    fun getActiveCount(): Int = executor.activeCount
    fun getPoolSize(): Int = executor.poolSize
    fun getQueueSize(): Int = executor.queue.size
    fun getCompletedCount(): Long = executor.completedTaskCount

    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
        FalconLogger.d("ThreadPool", "Shutdown complete")
    }
}
```

- [ ] **Step 3: Update IpcThreadPoolTest.kt**

Remove or rewrite any test referencing `IpcPriority`, `priorityQueue`, or `submit(priority, …)`. Keep tests for plain `submit { }`, `submitCallable { }`, the stats getters, and `shutdown()`. If a test asserted priority ordering, delete it (the feature is gone) and note it in the report. `PeerManager` calls `threadPool.submit { … }` (trailing lambda, no priority) — that still compiles against the new `submit(block)`.

- [ ] **Step 4: Build + tests**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.core.IpcThreadPoolTest"`
Expected: PASS. Then full suite `./gradlew :falcon-core:testDebugUnitTest` — PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: simplify IpcThreadPool to plain executor (priority queue unused)"
```

---

## Task 5: Final verification + docs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Full multi-module build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (lint + all modules). If `falcon-benchmark` referenced any removed API, fix or report.

- [ ] **Step 2: Update CLAUDE.md**

Update the architecture/security sections to reflect the removals:
- "Core design" transport section: state transport is Binder-only now (remove the ≥64KB SharedMemory bullet and the TransportSelector line; keep the <64KB Binder description as the single path, or simply describe Binder transport).
- Remove the SharedMemory line from the security model.
- Remove "circuit breaker" from the fault-tolerance description (if present) and any OTA/`OtaCompat` references in the "Service discovery"/security sections.
- Keep rate-limiting (note: per-PID sliding window) and signature/permission bullets.

Make the edits surgically; do not rewrite unrelated parts of CLAUDE.md.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: align CLAUDE.md with P1 removals (Binder-only transport, no circuit breaker/OTA)"
```

---

## Self-Review Notes

- **Spec coverage (audit §3 P1 items):** SharedMemory→Task 1; CircuitBreaker→Task 2; OtaCompat/ServiceVersion→Task 3; ThreadPool priority queue→Task 4; docs→Task 5. `__check_service__`, BatchRequest, EventBus, DiagnosticsManager intentionally excluded per scope note.
- **Type consistency:** MessageRouter constructor shrinks across Task 1 (drop sharedMemoryTransport) then Task 2 (drop circuitBreaker) — final form `(registry, monitor, permissionChecker, rateLimiter)`. ProxyFactory.create final form `(serviceClass, serviceKey, transport)`. BinderTransport final form `(host)`. PeerManager loses only the sharedMemoryTransport param (keeps `threadPool`). All call sites in FalconManager updated in Task 1 Step 8.
- **Regression guard:** these are deletions; the existing test suite (minus tests for deleted features) is the safety net, plus full `./gradlew build`.
