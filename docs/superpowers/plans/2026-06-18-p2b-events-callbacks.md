# P2-B — Events / Streams / Callbacks + Legacy Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route `@IpcEvent`/`@IpcStream`/`@IpcCallback` through KSP-generated code with typed Bundle payloads, then delete the legacy `IpcSerializer`, MessageRouter reflective fallback, and `IpcEnvelope.args` byte field — completing the Binder-native convergence.

**Architecture:** Generated `IpcDispatcher` gains `eventFlow(methodId)` and `invokeCallback(methodId,args,reply)`. Generated proxies implement Flow/callback methods. The host lazily collects an impl's event Flow on first subscriber and cancels on last (ref-counted `EventCollector`). Legacy removal follows expand→migrate→contract so every commit compiles.

**Tech Stack:** Kotlin, KSP, Android (Binder/AIDL/Bundle), coroutines/Flow, JUnit/Robolectric.

**Spec:** `docs/superpowers/specs/2026-06-18-p2b-events-callbacks.md`

**Refinement vs spec §C:** `IpcHostService` uses its own `eventSubscribers` map; `EventBus` is unused. So we add a small testable `EventCollector` (ref-counted) used by the host, and DELETE the unused `EventBus` during cleanup (instead of retrofitting it).

**Build/test:** `./gradlew :falcon-core:testDebugUnitTest`, `:falcon-benchmark:testDebugUnitTest`, full `./gradlew build`. JDK 17 preconfigured.

---

## File Structure

**Create:**
- `falcon-core/src/main/java/com/falcon/ipc/core/EventCollector.kt` — ref-counted lazy Flow collection
- KSP tests + core tests per task

**Modify:**
- `transport/IpcTransport.kt` (+subscribe/unsubscribe/invokeCallback defaults)
- `transport/BinderTransport.kt` (implement them via host)
- `aidl/IIpcHost.aidl` (+invokeCallback)
- `runtime/IpcDispatcher.kt` (+eventFlow/invokeCallback defaults)
- `core/EventProxy.kt` (+typedRemoteFlow)
- `ksp/generator/MethodIds.kt` (exclude IpcReply param)
- `ksp/generator/TypeCodec.kt` (getAllSuperTypes)
- `ksp/generator/DispatcherGenerator.kt` (eventFlow + invokeCallback)
- `ksp/generator/ProxyGenerator.kt` (Flow + callback + mixed-annotation fix)
- `core/IpcHostService.kt` (wire subscribe/unsubscribe/invokeCallback to dispatcher via EventCollector)
- `core/MessageRouter.kt` (delete reflective fallback — cleanup)
- `protocol/IpcSerializer.kt` (delete — cleanup), `protocol/IpcEnvelope.kt` (drop args byte field — cleanup)
- `core/EventBus.kt` (delete — unused), `core/BatchRequest.kt` (delete if IpcSerializer-only & unused — cleanup)

---

## Phase 1 — Transport + AIDL plumbing

### Task 1: IpcTransport/IIpcHost invokeCallback + BinderTransport + EventProxy.typedRemoteFlow

**Files:**
- Modify: `transport/IpcTransport.kt`, `transport/BinderTransport.kt`, `aidl/IIpcHost.aidl`, `core/EventProxy.kt`

- [ ] **Step 1: AIDL — add invokeCallback**

Edit `falcon-core/src/main/aidl/com/falcon/ipc/aidl/IIpcHost.aidl`, add inside the interface:
```aidl
void invokeCallback(in IpcEnvelope request, IIpcEventCallback reply);
```

- [ ] **Step 2: IpcTransport — add event/callback methods (default no-op throw)**

In `transport/IpcTransport.kt`, add to the `IpcTransport` interface:
```kotlin
fun subscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
    throw UnsupportedOperationException("subscribe not supported by this transport")
}
fun unsubscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
    throw UnsupportedOperationException("unsubscribe not supported by this transport")
}
fun invokeCallback(envelope: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
    throw UnsupportedOperationException("invokeCallback not supported by this transport")
}
```

- [ ] **Step 3: BinderTransport — implement via host**

In `transport/BinderTransport.kt`, override the three:
```kotlin
override fun subscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
    host.subscribe(eventKey, callback)
}
override fun unsubscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
    host.unsubscribe(eventKey, callback)
}
override fun invokeCallback(envelope: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
    host.invokeCallback(envelope, reply)
}
```

- [ ] **Step 4: EventProxy — add typedRemoteFlow**

In `core/EventProxy.kt`, add (keep existing `remoteEventFlow`):
```kotlin
fun <T> typedRemoteFlow(
    eventKey: String,
    transport: com.falcon.ipc.transport.IpcTransport,
    decode: (android.os.Bundle) -> T
): kotlinx.coroutines.flow.Flow<T> = kotlinx.coroutines.flow.callbackFlow {
    val callback = object : IIpcEventCallback.Stub() {
        override fun onEvent(event: IpcEnvelope) {
            trySend(decode(event.argsBundle ?: android.os.Bundle()))
        }
        override fun getEventKey(): String = eventKey
    }
    transport.subscribe(eventKey, callback)
    awaitClose { transport.unsubscribe(eventKey, callback) }
}
```

- [ ] **Step 5: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (additive; AIDL regenerates `IIpcHost.Stub` with the new method — `IpcHostService`'s `hostBinder` won't compile until Task 5 implements `invokeCallback`). If `IpcHostService` fails to compile because the AIDL stub now requires `invokeCallback`, add a TEMPORARY stub override in `IpcHostService.hostBinder`:
```kotlin
override fun invokeCallback(request: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
    // wired in Task 5
}
```
and note it. (Task 5 fills it in.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: transport+AIDL invokeCallback, EventProxy.typedRemoteFlow"
```

---

## Phase 2 — KSP generation

### Task 2: IpcDispatcher 3-capability + MethodIds excludes IpcReply

**Files:**
- Modify: `runtime/IpcDispatcher.kt`, `ksp/generator/MethodIds.kt`, `ksp/generator/TypeCodec.kt`

- [ ] **Step 1: Extend IpcDispatcher with default methods**

In `runtime/IpcDispatcher.kt`:
```kotlin
interface IpcDispatcher {
    fun dispatch(methodId: Int, args: android.os.Bundle): android.os.Bundle
    fun eventFlow(methodId: Int): kotlinx.coroutines.flow.Flow<android.os.Bundle>? = null
    fun invokeCallback(methodId: Int, args: android.os.Bundle, reply: (android.os.Bundle) -> Unit) {}
}
```
(Defaults keep existing P2-A dispatchers valid.)

- [ ] **Step 2: MethodIds — exclude IpcReply param from the id**

In `ksp/generator/MethodIds.kt`, change `of` to skip `com.falcon.ipc.service.IpcReply` params so a @IpcCallback method's id is computed from its business params only (proxy and dispatcher agree):
```kotlin
object MethodIds {
    private const val IPC_REPLY = "com.falcon.ipc.service.IpcReply"
    fun of(m: com.google.devtools.ksp.symbol.KSFunctionDeclaration): Int {
        val paramQNs = m.parameters
            .map { it.type.resolve().declaration.qualifiedName?.asString() ?: "?" }
            .filter { it != IPC_REPLY }
        return com.falcon.ipc.annotations.MethodId.signatureHash(m.simpleName.asString(), paramQNs)
    }
}
```

- [ ] **Step 3: TypeCodec — recognize indirect Parcelable via getAllSuperTypes**

In `ksp/generator/TypeCodec.kt`, replace the direct `decl.superTypes.any { ... == "android.os.Parcelable" }` checks (in both `put` and `get`) with a transitive check:
```kotlin
private fun isParcelable(decl: com.google.devtools.ksp.symbol.KSClassDeclaration): Boolean =
    decl.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == "android.os.Parcelable" }
```
and call `isParcelable(decl)`.

- [ ] **Step 4: Build benchmark (KSP module compiles; generation unchanged yet)**

Run: `./gradlew :falcon-core:compileDebugKotlin :falcon-ksp:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: IpcDispatcher event/callback capabilities; methodId excludes IpcReply; transitive Parcelable detection"
```

### Task 3: DispatcherGenerator — eventFlow + invokeCallback

**Files:**
- Modify: `ksp/generator/DispatcherGenerator.kt`, `ksp/FalconProcessor.kt`

- [ ] **Step 1: Generate eventFlow + invokeCallback**

In `DispatcherGenerator.generate`, accept ALL annotated methods (not just @IpcMethod) and classify by annotation. Read the annotations on each method (qualified names `com.falcon.ipc.annotations.IpcMethod/IpcEvent/IpcStream/IpcCallback`). Emit:
- existing `dispatch(methodId,args)` `when` for `@IpcMethod` (unchanged).
- `override fun eventFlow(methodId: Int): Flow<Bundle>?` with a `when` over `@IpcEvent`/`@IpcStream` method ids:
```kotlin
<evtId> -> impl.<name>().map { v -> android.os.Bundle().also { <TypeCodec.put(elemType, "it", "r", "v")> } }
```
where `elemType` = the Flow's type argument: `m.returnType!!.resolve().arguments.first().type!!.resolve()`. `else -> null`. Add `import kotlinx.coroutines.flow.Flow` and `import kotlinx.coroutines.flow.map`.
- `override fun invokeCallback(methodId: Int, args: Bundle, reply: (Bundle) -> Unit)` with a `when` over `@IpcCallback` method ids:
```kotlin
<cbId> -> {
    <decode non-reply args a0,a1,...>
    impl.<name>(<a0,...>, object : com.falcon.ipc.service.IpcReply<T> {
        override fun onResult(data: T) { reply(android.os.Bundle().also { <TypeCodec.put(replyType,"it","r","data")> }) }
    })
}
```
`replyType` = the `IpcReply<T>`'s type arg of the reply parameter. Decode the non-reply params positionally (index "0".."n-1" excluding the reply param) using `TypeCodec.get`. Use `MethodIds.of(m)` for all ids (it already excludes IpcReply). If any element/reply/param type is unsupported, `logger.error(...)` and return false (same as @IpcMethod).

Keep the existing collision `seen` set spanning all ids in the class.

- [ ] **Step 2: FalconProcessor — pass all annotated methods to DispatcherGenerator**

Currently `DispatcherGenerator.generate` is called with `ipcMethodMethods` (only @IpcMethod). Change to pass `annotatedMethods` (all four annotations); the generator classifies internally. Keep generating only when there is ≥1 of any supported annotation.

- [ ] **Step 3: Add benchmark service with an event + a callback to exercise generation**

Edit `falcon-benchmark/src/main/java/com/falcon/benchmark/IBenchmarkFalconService.kt` to add:
```kotlin
@IpcEvent fun ticks(): kotlinx.coroutines.flow.Flow<Int>
@IpcCallback fun fetch(id: Int, reply: com.falcon.ipc.service.IpcReply<String>)
```
(Imports as needed.)

- [ ] **Step 4: Build benchmark; verify generated dispatcher has eventFlow + invokeCallback**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.
`find falcon-benchmark/build/generated/ksp -name "*_Dispatcher.kt"` → read it; confirm `eventFlow` maps the Int flow to Bundle("r") and `invokeCallback` decodes `id` and wires an `IpcReply<String>`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(ksp): DispatcherGenerator emits eventFlow + invokeCallback"
```

### Task 4: ProxyGenerator — Flow + callback (mixed-annotation fix)

**Files:**
- Modify: `ksp/generator/ProxyGenerator.kt`

- [ ] **Step 1: Generate event/stream + callback method bodies**

In `ProxyGenerator`, for each method, classify by annotation and emit:
- `@IpcMethod`: unchanged (Bundle + methodId + invoke).
- `@IpcEvent`/`@IpcStream` (returns `Flow<T>`):
```kotlin
override fun <name>(): kotlinx.coroutines.flow.Flow<T> =
    com.falcon.ipc.core.EventProxy.typedRemoteFlow("${serviceKey}#${id}", transport) { b -> <TypeCodec.get(elemType,"b","r")> }
```
where `id = MethodIds.of(method)` and `elemType` is the Flow type arg.
- `@IpcCallback` (`fun m(<args>, reply: IpcReply<T>)`):
```kotlin
override fun <name>(<args>, reply: com.falcon.ipc.service.IpcReply<T>) {
    val b = android.os.Bundle()
    <put each non-reply arg by index>
    val stub = object : com.falcon.ipc.aidl.IIpcEventCallback.Stub() {
        override fun onEvent(event: com.falcon.ipc.protocol.IpcEnvelope) {
            val out = event.argsBundle ?: android.os.Bundle()
            reply.onResult(<TypeCodec.get(replyType,"out","r")>)
        }
        override fun getEventKey(): String = ""
    }
    transport.invokeCallback(
        com.falcon.ipc.protocol.IpcEnvelope(serviceKey = serviceKey, method = "<name>", methodId = <id>, argsBundle = b),
        stub
    )
}
```
Remove the old "Flow handled by runtime" punt. **Mixed-annotation fix:** every annotated method now generates a real override; no silent `return@forEach`. If a type is unsupported, `logger.error(...)`.

- [ ] **Step 2: Build benchmark; verify proxy implements all methods**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Read the generated `*_Proxy.kt`; confirm `ticks()` returns a typedRemoteFlow and `fetch(id, reply)` builds a Bundle + invokeCallback, and the `ticks`/`fetch` methodIds match the dispatcher's.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(ksp): ProxyGenerator emits event/stream Flow + callback (no more punts)"
```

---

## Phase 3 — Runtime wiring

### Task 5: EventCollector + IpcHostService subscribe/invokeCallback

**Files:**
- Create: `core/EventCollector.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/core/EventCollectorTest.kt`
- Modify: `core/IpcHostService.kt`

- [ ] **Step 1: Write EventCollector test (ref-counting)**

```kotlin
package com.falcon.ipc.core

import android.os.Bundle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EventCollectorTest {
    @Test fun `collects on first subscribe and stops on last`() = runBlocking {
        val collector = EventCollector()
        val flow = MutableSharedFlow<Bundle>(extraBufferCapacity = 8)
        var providerCalls = 0
        val received = mutableListOf<Bundle>()
        val provider: () -> Flow<Bundle>? = { providerCalls++; flow }

        collector.onSubscribe("k", provider) { received.add(it) }
        collector.onSubscribe("k", provider) { received.add(it) } // second subscriber, no new collect
        delay(50)
        assertEquals(1, providerCalls) // collected once

        flow.emit(Bundle().apply { putInt("r", 7) })
        delay(50)
        assertTrue(received.isNotEmpty())

        collector.onUnsubscribe("k")
        collector.onUnsubscribe("k") // last -> cancels
        delay(50)
        assertFalse(collector.isCollecting("k"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.core.EventCollectorTest"`
Expected: FAIL (unresolved EventCollector)

- [ ] **Step 3: Implement EventCollector**

```kotlin
package com.falcon.ipc.core

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Lazily collects an event Flow while ≥1 subscriber is present (ref-counted). */
class EventCollector(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val counts = ConcurrentHashMap<String, Int>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    fun onSubscribe(eventKey: String, flowProvider: () -> Flow<Bundle>?, emit: (Bundle) -> Unit) {
        synchronized(lock) {
            val n = (counts[eventKey] ?: 0) + 1
            counts[eventKey] = n
            if (n == 1) {
                val flow = flowProvider() ?: return
                jobs[eventKey] = scope.launch { flow.collect { emit(it) } }
            }
        }
    }

    fun onUnsubscribe(eventKey: String) {
        synchronized(lock) {
            val n = (counts[eventKey] ?: 0) - 1
            if (n <= 0) {
                counts.remove(eventKey)
                jobs.remove(eventKey)?.cancel()
            } else counts[eventKey] = n
        }
    }

    fun isCollecting(eventKey: String): Boolean = jobs.containsKey(eventKey)

    fun shutdown() { scope.cancel() }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.core.EventCollectorTest"`
Expected: PASS

- [ ] **Step 5: Wire IpcHostService**

In `IpcHostService`:
- Add `private val eventCollector = EventCollector()`.
- `subscribe(eventKey, callback)`: keep `eventSubscribers.getOrPut(...).add(callback)`; then
```kotlin
val parts = eventKey.split("#")
if (parts.size == 2) {
    val serviceKey = parts[0]; val methodId = parts[1].toIntOrNull()
    if (methodId != null) {
        eventCollector.onSubscribe(eventKey,
            { serviceRegistry.getDispatcher(serviceKey)?.eventFlow(methodId) },
            { bundle -> emitBundle(eventKey, bundle) })
    }
}
```
- Add `private fun emitBundle(eventKey: String, bundle: Bundle)` that fans out to `eventSubscribers[eventKey]` via `callback.onEvent(IpcEnvelope(serviceKey = eventKey, method = "__event__", argsBundle = bundle))`, removing dead callbacks.
- `unsubscribe(eventKey, callback)`: keep removing from `eventSubscribers`; then `eventCollector.onUnsubscribe(eventKey)`.
- Implement `invokeCallback(request, reply)` (replacing the temporary stub from Task 1):
```kotlin
override fun invokeCallback(request: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
    val d = serviceRegistry.getDispatcher(request.serviceKey) ?: return
    d.invokeCallback(request.methodId, request.argsBundle ?: Bundle()) { b ->
        try { reply.onEvent(IpcEnvelope(requestId = request.requestId, argsBundle = b)) }
        catch (e: Exception) { FalconLogger.w("Host", "callback reply failed: ${e.message}") }
    }
}
```
- In `onDestroy()` (add if absent), call `eventCollector.shutdown()`.

- [ ] **Step 6: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest` then `./gradlew :falcon-core:compileDebugKotlin` — BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: EventCollector ref-counted collection; wire host subscribe/invokeCallback to dispatcher"
```

---

## Phase 4 — End-to-end test (JVM, generated code)

### Task 6: Generated event + callback round-trip test

**Files:**
- Modify: `falcon-benchmark/src/test/java/com/falcon/benchmark/FalconGeneratedRoundTripTest.kt`

- [ ] **Step 1: Add event + callback round-trip cases**

Extend the existing test. Implement the benchmark service's new methods (`ticks()` returns a small fixed Flow e.g. `flowOf(1,2,3)`; `fetch(id, reply)` calls `reply.onResult("v$id")`). Build a fake `IpcTransport` whose `invokeCallback(env, reply)` routes to `dispatcher.invokeCallback(env.methodId, env.argsBundle ?: Bundle()) { b -> reply.onEvent(IpcEnvelope(argsBundle=b)) }`, and whose `subscribe` starts collecting `dispatcher.eventFlow(methodId)` and pushes to the callback. Then:
```kotlin
// callback
val results = mutableListOf<String>()
proxy.fetch(42, object : IpcReply<String> { override fun onResult(data: String){ results.add(data) } })
assertEquals(listOf("v42"), results)

// event
val collected = runBlocking { proxy.ticks().take(3).toList() }
assertEquals(listOf(1,2,3), collected)
```
Adapt to the actual generated proxy/dispatcher signatures (read them). Use `@RunWith(RobolectricTestRunner::class)` (already).

- [ ] **Step 2: Run**

Run: `./gradlew :falcon-benchmark:testDebugUnitTest --tests "com.falcon.benchmark.FalconGeneratedRoundTripTest"`
Expected: PASS (must actually pass — iterate to match generated signatures).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: generated event Flow + callback round-trip"
```

---

## Phase 5 — Legacy removal (contract; one step at a time, green each commit)

### Task 7: Delete MessageRouter reflective fallback

**Files:**
- Modify: `core/MessageRouter.kt`

- [ ] **Step 1: Confirm dispatcher path covers all production calls**

`@IpcMethod` → dispatcher (P2-A); events/callbacks → subscribe/invokeCallback (Task 5). The reflective branch in `handleLocal` (methodId==0 / no dispatcher) is now unused in production. Confirm no test depends on it: `grep -rn "handleLocal" falcon-core/src` — only MessageRouterTest. Update MessageRouterTest: its tests register a dispatcher? No — they use the reflective path. So those tests must be migrated to use a dispatcher OR rewritten. Read MessageRouterTest; for tests asserting reflective dispatch of a plain impl, replace with a fake `IpcDispatcher` registered via `registry.registerDispatcher` and assert delegation. Keep rate-limit/`__check_service__` tests.

- [ ] **Step 2: Remove reflective code**

Delete `findMethod`/`resolveMethod`/`methodCache` and the reflective invoke branch in `handleLocal`. `handleLocal` keeps: rate-limit, `__check_service__`, permission, then `registry.getDispatcher(serviceKey)?.dispatch(methodId, argsBundle ?: Bundle()) ?: throw IllegalStateException("Service not found: ...")`. Remove the now-unused `monitor.recordCall` timing around reflection only if it referenced the removed code — keep monitor recording around the dispatch.

- [ ] **Step 3: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest` — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete MessageRouter reflective fallback (dispatcher-only)"
```

### Task 8: Delete IpcSerializer + migrate/delete its users

**Files:**
- Delete: `protocol/IpcSerializer.kt`, `protocol/IpcSerializerTest.kt`, `core/EventBus.kt` (unused), and `core/BatchRequest.kt` + `BatchRequestTest.kt` IF BatchRequest only exists via IpcSerializer and has no production caller.
- Modify: `core/IpcHostService.kt` (remove legacy serializeResult branch)

- [ ] **Step 1: Inventory IpcSerializer users**

Run: `grep -rln "IpcSerializer" falcon-core/src`. Expected after Task 7: `IpcHostService` (legacy response branch), `BatchRequest`, `EventBus`, and `IpcSerializer` itself + its test. Check `grep -rn "BatchExecutor\|EventBus" falcon-core/src/main` for production callers (the over-engineering audit found none for these beyond their own files).

- [ ] **Step 2: Remove legacy response branch in IpcHostService**

The dispatcher path always returns a Bundle now. In `IpcHostService.invoke`, the result is always a Bundle (from `handleLocal` → dispatcher). Simplify:
```kotlin
val result = messageRouter.handleLocal(request, callerPackage, callingPid)
val resp = if (result is Bundle) IpcEnvelope(requestId = request.requestId, argsBundle = result)
           else IpcEnvelope.response(request.requestId, null) // __check_service__ returns Boolean -> see note
```
NOTE: `__check_service__` returns Boolean (not Bundle). Keep handling it: if `result is Boolean`, encode into a Bundle (`Bundle().apply{ putBoolean("r", result) }`) so the response is uniform, and update `FalconManager.getService`'s probe to read `argsBundle.getBoolean("r")` instead of deserializeResult. Adjust both sides consistently. (This removes the last IpcSerializer use.)

- [ ] **Step 3: Delete IpcSerializer, EventBus, and (if unused) BatchRequest**

```bash
git rm falcon-core/src/main/java/com/falcon/ipc/protocol/IpcSerializer.kt falcon-core/src/test/java/com/falcon/ipc/protocol/IpcSerializerTest.kt
git rm falcon-core/src/main/java/com/falcon/ipc/core/EventBus.kt
```
If `grep` confirms BatchRequest/BatchExecutor has no production caller (only tests): `git rm falcon-core/src/main/java/com/falcon/ipc/core/BatchRequest.kt falcon-core/src/test/java/com/falcon/ipc/core/BatchRequestTest.kt`. If it DOES have a caller, instead migrate it to dispatcher+Bundle and report. Fix any remaining references.

- [ ] **Step 4: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest` — BUILD SUCCESSFUL. Fix fallout (update `getService` probe decode to Bundle boolean).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: delete IpcSerializer + unused EventBus/BatchRequest; __check_service__ via Bundle"
```

### Task 9: Drop IpcEnvelope.args byte field

**Files:**
- Modify: `protocol/IpcEnvelope.kt`, plus any remaining referencers

- [ ] **Step 1: Confirm no remaining users of args byte field**

Run: `grep -rn "\.args\b\|args =" falcon-core/src falcon-benchmark/src | grep -i envelope` and `grep -rn "envelope.args\|\.args ?:" falcon-core/src`. Expected: BinderTransport's `Success(response.args)` fallback and IpcEnvelope itself. Remove the BinderTransport fallback (responses are always Bundle now): `TransportResult.Success(response.argsBundle)`.

- [ ] **Step 2: Remove the field**

In `IpcEnvelope`, remove `val args: ByteArray? = null`, its Parcel read (`createByteArray`) and write (`writeByteArray`), and update `equals`/`hashCode` (drop args). Update the `response(requestId, data)` factory: change signature to `response(requestId, bundle: Bundle?)` setting `argsBundle = bundle`, OR remove it if unused after Task 8. Update all construction sites flagged by the compiler.

- [ ] **Step 3: Build + suite + full build**

Run: `./gradlew :falcon-core:testDebugUnitTest` then `./gradlew build` — BUILD SUCCESSFUL (all modules).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: drop IpcEnvelope byte-array args (Bundle-only wire format)"
```

---

## Phase 6 — Instrumented test + docs

### Task 10: Instrumented event+callback test (device-deferred) + docs + final build

**Files:**
- Create: `falcon-benchmark/src/androidTest/java/com/falcon/benchmark/FalconEventCallbackInstrumentedTest.kt`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Write the instrumented test (best-effort; requires device)**

Write a two-process test that subscribes to an `@IpcEvent` and invokes an `@IpcCallback` across processes, mirroring the req/resp instrumented test approach. Mark clearly in a top comment: "Requires a connected device/emulator; not verified in CI-less environments." Read the existing `FalconReqRespInstrumentedTest` if present, else the benchmark host bootstrap, to mirror wiring.

- [ ] **Step 2: Attempt to run (if a device is available)**

Run: `./gradlew :falcon-benchmark:connectedDebugAndroidTest`
Expected: PASS on device. If no device, report that it is written but unverified — do NOT mark the task complete as "verified"; note the gap explicitly.

- [ ] **Step 3: Update CLAUDE.md**

Document the completed convergence: ALL IPC (request/response, events/streams, callbacks) now go through KSP-generated typed code with Bundle payloads + stable methodId; `IpcSerializer`, dynamic `ProxyFactory`, reflective dispatch, `EventBus`, and the byte-array wire field are gone. Note the device-only instrumented coverage gap.

- [ ] **Step 4: Full build + commit**

Run: `./gradlew build` — BUILD SUCCESSFUL.
```bash
git add -A
git commit -m "test+docs: instrumented event/callback test (device-deferred); document full Binder-native convergence"
```

---

## Self-Review Notes

- **Spec coverage:** A (transport/AIDL/typedRemoteFlow)→Task 1; B (IpcDispatcher 3-cap + methodId excl IpcReply)→Task 2,3; event payload Bundle→Task 3,4,5; C (lazy ref-count)→Task 5 (EventCollector, refined from EventBus); D (@IpcCallback)→Task 3,4,5; E (proxy gen + mixed-annotation fix)→Task 4; F (delete legacy)→Task 7,8,9; G (types + getAllSuperTypes)→Task 2; H (tests)→Task 6,10; minor findings (mixed-annotation, getAllSuperTypes)→Task 4,2.
- **Type consistency:** `IpcDispatcher.eventFlow(Int): Flow<Bundle>?` / `invokeCallback(Int, Bundle, (Bundle)->Unit)` defined Task 2, generated Task 3, consumed Task 5. `MethodIds.of` (excl IpcReply) Task 2, used Task 3/4. `EventProxy.typedRemoteFlow(eventKey, transport, decode)` Task 1, used Task 4. `EventCollector.onSubscribe/onUnsubscribe/isCollecting/shutdown` Task 5. `IpcTransport.subscribe/unsubscribe/invokeCallback` Task 1, used Task 4/5.
- **Green-at-each-commit:** Task 1 may need a temporary `invokeCallback` stub in IpcHostService (filled Task 5) — noted. Legacy removal split into Task 7→8→9 (reflective → IpcSerializer → byte field), each compilable.
- **Device gap:** Task 10 instrumented test cannot be verified without a device — explicitly not marked verified.
- **Deviation from spec §C:** EventBus is unused; replaced by testable EventCollector and EventBus deleted in Task 8.
