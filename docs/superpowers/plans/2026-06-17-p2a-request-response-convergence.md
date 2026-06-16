# P2-A — Request/Response Binder-native Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Parcel.marshall()` byte-array args with a Bundle + stable per-method id, and dispatch `@IpcMethod` calls through KSP-generated typed Proxy/Dispatcher (zero server reflection, no overload ambiguity), deleting `IpcSerializer` and the dynamic-proxy `ProxyFactory`.

**Architecture:** Expand→migrate→contract. First add `methodId`/`argsBundle` to `IpcEnvelope` alongside the existing `args` (every commit stays green). Then KSP generates `Xxx_Proxy`/`Xxx_Dispatcher`/`<Module>FalconGeneratedRegistry` and the runtime is rewired to use them. Finally delete the old reflective/serializer path.

**Tech Stack:** Kotlin, KSP, Android (Binder/Bundle/Parcelable), JUnit/Robolectric/Mockito, androidTest (instrumented).

**Spec:** `docs/superpowers/specs/2026-06-17-p2-binder-native-convergence.md` (phases 1–3 + req/resp instrumented test). Events/streams/callbacks (phase 4) are Plan B.

**Scope of Plan A:** `@IpcMethod` (suspend request/response) only. `@IpcEvent`/`@IpcStream`/`@IpcCallback` keep working through the EXISTING envelope path during Plan A (they still use `args: ByteArray` + IpcSerializer until Plan B), so `IpcSerializer` deletion happens in Plan B, not here. Plan A deletes only the reflective dispatch and dynamic proxy for `@IpcMethod`.

**Build/test:** `./gradlew :falcon-core:testDebugUnitTest`, `./gradlew build`. JDK 17 preconfigured.

---

## File Structure

**Create:**
- `falcon-annotations/src/main/java/com/falcon/ipc/annotations/MethodId.kt` — pure FNV-1a signature hash (shared by runtime + KSP)
- `falcon-core/src/main/java/com/falcon/ipc/runtime/IpcDispatcher.kt` — `IpcDispatcher` + `FalconGeneratedRegistry` interfaces
- `falcon-core/src/main/java/com/falcon/ipc/protocol/BundleCodec.kt` — typed put/get helpers used by generated code
- `falcon-ksp/.../generator/DispatcherGenerator.kt`, `ProxyGenerator.kt` (rewrite), `RegistryGenerator.kt`
- Tests as listed per task

**Modify:**
- `protocol/IpcEnvelope.kt` (add methodId + argsBundle; later drop ByteArray args for @IpcMethod path)
- `core/ServiceRegistry.kt` (store IpcDispatcher)
- `core/MessageRouter.kt` (delegate to dispatcher)
- `core/FalconManager.kt` (generated registries; register/getService via factories)
- `FalconConfig.kt` (`generated(...)` DSL)
- `Falcon.kt` (init DSL passthrough if needed)
- `core/IpcHostService.kt` (Bundle response)

---

## Phase 1 — Protocol foundation

### Task 1: MethodId signature hash (pure, shared)

**Files:**
- Create: `falcon-annotations/src/main/java/com/falcon/ipc/annotations/MethodId.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/protocol/MethodIdTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.falcon.ipc.protocol

import com.falcon.ipc.annotations.MethodId
import org.junit.Assert.*
import org.junit.Test

class MethodIdTest {
    @Test fun `same signature is stable`() {
        assertEquals(
            MethodId.signatureHash("getName", listOf("kotlin.Int")),
            MethodId.signatureHash("getName", listOf("kotlin.Int"))
        )
    }
    @Test fun `overloads differ by param types`() {
        assertNotEquals(
            MethodId.signatureHash("set", listOf("kotlin.Int")),
            MethodId.signatureHash("set", listOf("kotlin.String"))
        )
    }
    @Test fun `different names differ`() {
        assertNotEquals(
            MethodId.signatureHash("a", emptyList()),
            MethodId.signatureHash("b", emptyList())
        )
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.protocol.MethodIdTest"`
Expected: FAIL (unresolved `MethodId`)

- [ ] **Step 3: Implement**

```kotlin
package com.falcon.ipc.annotations

/** Stable method identity for IPC dispatch. Shared by the KSP processor and the runtime. */
object MethodId {
    fun signatureHash(name: String, paramTypeQualifiedNames: List<String>): Int {
        val sig = name + "(" + paramTypeQualifiedNames.joinToString(",") + ")"
        var hash = -0x7ee3623b // 0x811c9dc5 FNV offset basis as Int
        for (c in sig) {
            hash = hash xor c.code
            hash *= 0x01000193 // FNV prime
        }
        return hash
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.protocol.MethodIdTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-annotations/src/main/java/com/falcon/ipc/annotations/MethodId.kt falcon-core/src/test/java/com/falcon/ipc/protocol/MethodIdTest.kt
git commit -m "feat: add MethodId.signatureHash (stable per-method id, shared runtime+KSP)"
```

### Task 2: IpcEnvelope — add methodId + argsBundle (parallel to existing args)

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/protocol/IpcEnvelope.kt`

- [ ] **Step 1: Add fields + Parcel handling**

Add to the data class (after `errorMessage`):
```kotlin
val methodId: Int = 0,
val argsBundle: android.os.Bundle? = null
```
In `constructor(parcel)`, append (after `errorMessage`):
```kotlin
methodId = parcel.readInt(),
argsBundle = parcel.readBundle(IpcEnvelope::class.java.classLoader)
```
In `writeToParcel`, append (after `errorMessage`):
```kotlin
parcel.writeInt(methodId)
parcel.writeBundle(argsBundle)
```
Leave `args: ByteArray?` in place (parallel). Keep `equals`/`hashCode` on existing keys (do NOT add Bundle). Read order mirrors write order.

- [ ] **Step 2: Build + full suite (no behavior change yet)**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (new fields default, nothing uses them yet)

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/protocol/IpcEnvelope.kt
git commit -m "feat: IpcEnvelope carries methodId + Bundle args (parallel to byte args)"
```

### Task 3: BundleCodec typed helpers + IpcDispatcher/registry interfaces

**Files:**
- Create: `falcon-core/src/main/java/com/falcon/ipc/protocol/BundleCodec.kt`
- Create: `falcon-core/src/main/java/com/falcon/ipc/runtime/IpcDispatcher.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/protocol/BundleCodecTest.kt`

- [ ] **Step 1: Write the failing test (Robolectric — Bundle is Android)**

```kotlin
package com.falcon.ipc.protocol

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BundleCodecTest {
    @Test fun `int round trips`() {
        val b = Bundle(); BundleCodec.putInt(b, "0", 42)
        assertEquals(42, BundleCodec.getInt(b, "0"))
    }
    @Test fun `string round trips`() {
        val b = Bundle(); BundleCodec.putString(b, "0", "hi")
        assertEquals("hi", BundleCodec.getString(b, "0"))
    }
    @Test fun `byte array round trips`() {
        val b = Bundle(); BundleCodec.putByteArray(b, "0", byteArrayOf(1,2,3))
        assertArrayEquals(byteArrayOf(1,2,3), BundleCodec.getByteArray(b, "0"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.protocol.BundleCodecTest"`
Expected: FAIL (unresolved `BundleCodec`)

- [ ] **Step 3: Implement BundleCodec**

```kotlin
package com.falcon.ipc.protocol

import android.os.Bundle
import android.os.Parcelable

/**
 * Typed Bundle put/get used by KSP-generated proxies/dispatchers.
 * Keys are parameter indices as strings ("0".."n"); the return value uses key "r".
 */
object BundleCodec {
    fun putInt(b: Bundle, k: String, v: Int) = b.putInt(k, v)
    fun getInt(b: Bundle, k: String): Int = b.getInt(k)
    fun putLong(b: Bundle, k: String, v: Long) = b.putLong(k, v)
    fun getLong(b: Bundle, k: String): Long = b.getLong(k)
    fun putFloat(b: Bundle, k: String, v: Float) = b.putFloat(k, v)
    fun getFloat(b: Bundle, k: String): Float = b.getFloat(k)
    fun putDouble(b: Bundle, k: String, v: Double) = b.putDouble(k, v)
    fun getDouble(b: Bundle, k: String): Double = b.getDouble(k)
    fun putBoolean(b: Bundle, k: String, v: Boolean) = b.putBoolean(k, v)
    fun getBoolean(b: Bundle, k: String): Boolean = b.getBoolean(k)
    fun putString(b: Bundle, k: String, v: String?) = b.putString(k, v)
    fun getString(b: Bundle, k: String): String? = b.getString(k)
    fun putByteArray(b: Bundle, k: String, v: ByteArray?) = b.putByteArray(k, v)
    fun getByteArray(b: Bundle, k: String): ByteArray? = b.getByteArray(k)
    fun <T : Parcelable> putParcelable(b: Bundle, k: String, v: T?) = b.putParcelable(k, v)
    @Suppress("DEPRECATION")
    fun <T : Parcelable> getParcelable(b: Bundle, k: String, cls: Class<T>): T? =
        b.getParcelable(k)
    fun putEnum(b: Bundle, k: String, v: Enum<*>?) = b.putString(k, v?.name)
    fun <T : Enum<T>> getEnum(b: Bundle, k: String, cls: Class<T>): T? =
        b.getString(k)?.let { java.lang.Enum.valueOf(cls, it) }
}
```

- [ ] **Step 4: Implement IpcDispatcher + registry interfaces**

Create `falcon-core/src/main/java/com/falcon/ipc/runtime/IpcDispatcher.kt`:
```kotlin
package com.falcon.ipc.runtime

import android.os.Bundle
import com.falcon.ipc.transport.IpcTransport

/** Server-side typed dispatch generated by KSP per @IpcService. */
interface IpcDispatcher {
    fun dispatch(methodId: Int, args: Bundle): Bundle
}

/** Aggregated factories generated by KSP per compilation module. */
interface FalconGeneratedRegistry {
    val dispatcherFactories: Map<String, (Any) -> IpcDispatcher>
    val proxyFactories: Map<String, (IpcTransport, String) -> Any>
}
```

- [ ] **Step 5: Run to verify codec test passes + build**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.protocol.BundleCodecTest"`
Expected: PASS
Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/protocol/BundleCodec.kt falcon-core/src/main/java/com/falcon/ipc/runtime/IpcDispatcher.kt falcon-core/src/test/java/com/falcon/ipc/protocol/BundleCodecTest.kt
git commit -m "feat: BundleCodec typed helpers + IpcDispatcher/FalconGeneratedRegistry interfaces"
```

---

## Phase 2 — KSP generation (request/response)

> KSP type mapping table (used by both generators). For a parameter/return type qualified name `t`, the codec calls are:
> - `kotlin.Int`→putInt/getInt, `kotlin.Long`→Long, `kotlin.Float`→Float, `kotlin.Double`→Double, `kotlin.Boolean`→Boolean, `kotlin.String`→putString/getString, `kotlin.ByteArray`→putByteArray/getByteArray, `kotlin.Unit`→(no value)
> - enum class → putEnum / `getEnum(b,k,T::class.java)`
> - Parcelable subtype → putParcelable / `getParcelable(b,k,T::class.java)`
> - anything else → **KSP compile error** `"@IpcMethod param/return type <t> unsupported; make it Parcelable"`

### Task 4: DispatcherGenerator (server)

**Files:**
- Create: `falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/DispatcherGenerator.kt`
- Create: `falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/TypeCodec.kt` (shared mapping helper)
- Modify: `falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/FalconProcessor.kt`

- [ ] **Step 1: Implement TypeCodec mapping helper**

```kotlin
package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSType

/** Emits BundleCodec put/get expressions for a resolved type, or null if unsupported. */
object TypeCodec {
    private val primitives = mapOf(
        "kotlin.Int" to "Int", "kotlin.Long" to "Long", "kotlin.Float" to "Float",
        "kotlin.Double" to "Double", "kotlin.Boolean" to "Boolean",
        "kotlin.String" to "String", "kotlin.ByteArray" to "ByteArray"
    )
    fun isUnit(t: KSType) = t.declaration.qualifiedName?.asString() == "kotlin.Unit"

    /** put expression: BundleCodec.putX(<bundleVar>, <key>, <valueExpr>) */
    fun put(t: KSType, bundleVar: String, key: String, valueExpr: String): String? {
        val q = t.declaration.qualifiedName?.asString() ?: return null
        primitives[q]?.let { return "com.falcon.ipc.protocol.BundleCodec.put$it($bundleVar, \"$key\", $valueExpr)" }
        val decl = t.declaration
        if (decl is com.google.devtools.ksp.symbol.KSClassDeclaration) {
            if (decl.classKind == ClassKind.ENUM_CLASS)
                return "com.falcon.ipc.protocol.BundleCodec.putEnum($bundleVar, \"$key\", $valueExpr)"
            if (decl.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "android.os.Parcelable" })
                return "com.falcon.ipc.protocol.BundleCodec.putParcelable($bundleVar, \"$key\", $valueExpr)"
        }
        return null
    }

    /** get expression returning the typed value. */
    fun get(t: KSType, bundleVar: String, key: String): String? {
        val q = t.declaration.qualifiedName?.asString() ?: return null
        primitives[q]?.let { return "com.falcon.ipc.protocol.BundleCodec.get$it($bundleVar, \"$key\")" }
        val decl = t.declaration
        val qn = q
        if (decl is com.google.devtools.ksp.symbol.KSClassDeclaration) {
            if (decl.classKind == ClassKind.ENUM_CLASS)
                return "com.falcon.ipc.protocol.BundleCodec.getEnum($bundleVar, \"$key\", $qn::class.java)"
            if (decl.superTypes.any { it.resolve().declaration.qualifiedName?.asString() == "android.os.Parcelable" })
                return "com.falcon.ipc.protocol.BundleCodec.getParcelable($bundleVar, \"$key\", $qn::class.java)"
        }
        return null
    }
}
```

- [ ] **Step 2: Implement DispatcherGenerator**

```kotlin
package com.falcon.ipc.ksp.generator

import com.falcon.ipc.annotations.MethodId
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

object DispatcherGenerator {
    fun generate(cg: CodeGenerator, logger: KSPLogger, iface: KSClassDeclaration, methods: List<KSFunctionDeclaration>): Boolean {
        val pkg = iface.packageName.asString()
        val ifaceName = iface.simpleName.asString()
        val name = "${ifaceName}_Dispatcher"
        val seen = HashSet<Int>()
        val sb = StringBuilder()
        sb.appendLine("package $pkg")
        sb.appendLine("import android.os.Bundle")
        sb.appendLine("import com.falcon.ipc.runtime.IpcDispatcher")
        sb.appendLine("class $name(private val impl: $ifaceName) : IpcDispatcher {")
        sb.appendLine("    override fun dispatch(methodId: Int, args: Bundle): Bundle {")
        sb.appendLine("        val out = Bundle()")
        sb.appendLine("        when (methodId) {")
        for (m in methods) {
            val mName = m.simpleName.asString()
            val paramTypes = m.parameters.map { it.type.resolve() }
            val paramQNs = paramTypes.map { it.declaration.qualifiedName?.asString() ?: "?" }
            val id = MethodId.signatureHash(mName, paramQNs)
            if (!seen.add(id)) { logger.error("methodId collision in $ifaceName for $mName"); return false }
            // decode each arg
            val argExprs = m.parameters.mapIndexed { i, p ->
                val t = p.type.resolve()
                TypeCodec.get(t, "args", i.toString())
                    ?: run { logger.error("@IpcMethod param type ${paramQNs[i]} unsupported; make it Parcelable"); return false }
            }
            val ret = m.returnType!!.resolve()
            sb.appendLine("            $id -> {")
            sb.appendLine("                val r = impl.$mName(${argExprs.joinToString(", ")})")
            if (!TypeCodec.isUnit(ret)) {
                val putR = TypeCodec.put(ret, "out", "r", "r")
                    ?: run { logger.error("@IpcMethod return type unsupported; make it Parcelable"); return false }
                sb.appendLine("                $putR")
            }
            sb.appendLine("            }")
        }
        sb.appendLine("            else -> throw IllegalArgumentException(\"Unknown methodId: \$methodId in $ifaceName\")")
        sb.appendLine("        }")
        sb.appendLine("        return out")
        sb.appendLine("    }")
        sb.appendLine("}")
        cg.createNewFile(Dependencies(false, iface.containingFile!!), pkg, name).use { it.write(sb.toString().toByteArray()) }
        return true
    }
}
```
NOTE: suspend `@IpcMethod` — the generated `dispatch` calls `impl.$mName(...)`. Suspend impls require a coroutine context. For Plan A, the dispatcher is invoked from `MessageRouter` on the Binder thread; generate `kotlinx.coroutines.runBlocking { impl.$mName(...) }` when the method is suspend (check `Modifier.SUSPEND`). Adjust Step 2: wrap the call in `kotlinx.coroutines.runBlocking { ... }` if `m.modifiers.contains(Modifier.SUSPEND)`.

- [ ] **Step 3: Wire FalconProcessor to call DispatcherGenerator (keep old StubGenerator for now)**

In `FalconProcessor.process`, for each interface, filter `@IpcMethod` methods (request/response) and call `DispatcherGenerator.generate(...)`. Keep the existing `StubGenerator`/`ProxyGenerator` calls for now (they still serve events until Plan B); the new dispatcher is additive.

- [ ] **Step 4: Build the benchmark (which has annotated services) to exercise generation**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, and generated `*_Dispatcher.kt` present under `falcon-benchmark/build/generated/ksp/`. Verify with:
`find falcon-benchmark/build/generated/ksp -name "*_Dispatcher.kt"` → at least one file.

- [ ] **Step 5: Commit**

```bash
git add falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/TypeCodec.kt falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/DispatcherGenerator.kt falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/FalconProcessor.kt
git commit -m "feat(ksp): generate typed IpcDispatcher for @IpcMethod"
```

### Task 5: ProxyGenerator rewrite (Bundle + methodId)

**Files:**
- Modify: `falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/ProxyGenerator.kt`

- [ ] **Step 1: Rewrite generation for @IpcMethod using Bundle + methodId**

Generate, per `@IpcMethod` method, a body that builds a Bundle of args via `TypeCodec.put`, sets `methodId`, calls `transport.invoke`, and reads `"r"` via `TypeCodec.get`. Emitted shape per method:
```kotlin
override fun <name>(<params>): <ret> {
    val b = Bundle()
    // for each param i: <TypeCodec.put(t,"b", i, paramName)>
    val env = com.falcon.ipc.protocol.IpcEnvelope(serviceKey = serviceKey, method = "<name>", methodId = <id>, argsBundle = b)
    val result = transport.invoke(env)
    return when (result) {
        is com.falcon.ipc.transport.TransportResult.Success -> {
            val out = (result.data as? Bundle) ?: Bundle()
            <if Unit: Unit else: <TypeCodec.get(ret,"out","r")> as <ret>>
        }
        is com.falcon.ipc.transport.TransportResult.Error -> throw RuntimeException("IPC error [${'$'}{result.code}]: ${'$'}{result.message}")
    }
}
```
Keep Flow methods punted (existing comment) — Plan B. Use `MethodId.signatureHash(name, paramQNs)` for `<id>` (same as dispatcher). Add `import android.os.Bundle`. The transport result for Bundle responses requires `TransportResult.Success.data` to carry a Bundle — handled in Task 7 (BinderTransport returns the response `argsBundle`).

- [ ] **Step 2: Build benchmark to verify generated proxy compiles**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `find falcon-benchmark/build/generated/ksp -name "*_Proxy.kt"` shows the rewritten proxy.

- [ ] **Step 3: Commit**

```bash
git add falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/ProxyGenerator.kt
git commit -m "feat(ksp): generate Bundle+methodId Proxy for @IpcMethod"
```

### Task 6: RegistryGenerator (aggregated factories)

**Files:**
- Create: `falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/RegistryGenerator.kt`
- Modify: `falcon-ksp/.../FalconProcessor.kt`

- [ ] **Step 1: Implement RegistryGenerator**

Generate one object per module implementing `FalconGeneratedRegistry`, mapping `serviceKey` (interface qualified name) → dispatcher/proxy factories:
```kotlin
package com.falcon.ipc.generated
import com.falcon.ipc.runtime.FalconGeneratedRegistry
import com.falcon.ipc.runtime.IpcDispatcher
import com.falcon.ipc.transport.IpcTransport
object <ModuleId>FalconGeneratedRegistry : FalconGeneratedRegistry {
    override val dispatcherFactories: Map<String, (Any) -> IpcDispatcher> = mapOf(
        "<iface.qualifiedName>" to { impl -> <pkg>.<Iface>_Dispatcher(impl as <pkg>.<Iface>) },
        // ...
    )
    override val proxyFactories: Map<String, (IpcTransport, String) -> Any> = mapOf(
        "<iface.qualifiedName>" to { t, k -> <pkg>.<Iface>_Proxy(t, k) },
        // ...
    )
}
```
`<ModuleId>` = KSP option `falcon.moduleId` if present else the last segment of the first interface's package, capitalized. Collect all processed interfaces across `process()` calls and emit once (KSP multi-round: accumulate in the processor, emit in `finish()`).

- [ ] **Step 2: Modify FalconProcessor to accumulate interfaces and emit registry in finish()**

Accumulate processed `(serviceKey, pkg, ifaceName)` into a processor field; override `finish()` to call `RegistryGenerator.generate(...)` once with the accumulated list and the `falcon.moduleId` option (read from the processor's options).

- [ ] **Step 3: Build benchmark; verify registry generated**

Run: `./gradlew :falcon-benchmark:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; `find falcon-benchmark/build/generated/ksp -name "*FalconGeneratedRegistry.kt"` → one file.

- [ ] **Step 4: Commit**

```bash
git add falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/generator/RegistryGenerator.kt falcon-ksp/src/main/kotlin/com/falcon/ipc/ksp/FalconProcessor.kt
git commit -m "feat(ksp): generate aggregated FalconGeneratedRegistry"
```

---

## Phase 3 — Runtime rewiring + contract (delete reflection/dynamic proxy)

### Task 7: ServiceRegistry stores dispatcher; BinderTransport/IpcHostService carry Bundle

**Files:**
- Modify: `core/ServiceRegistry.kt`, `core/MessageRouter.kt`, `core/IpcHostService.kt`, `transport/BinderTransport.kt`, `transport/IpcTransport.kt` (TransportResult already carries `Any?`)

- [ ] **Step 1: ServiceRegistry — store IpcDispatcher**

Add parallel storage: `private val dispatchers = ConcurrentHashMap<String, IpcDispatcher>()`, `fun registerDispatcher(key, d)`, `fun getDispatcher(key): IpcDispatcher?`. Keep existing `IpcService` storage for the event path during Plan A.

- [ ] **Step 2: MessageRouter — delegate @IpcMethod to dispatcher**

In `handleLocal`, after the cross-cutting checks and `__check_service__`, if `envelope.methodId != 0` and a dispatcher exists, dispatch via Bundle:
```kotlin
val dispatcher = registry.getDispatcher(envelope.serviceKey)
if (dispatcher != null && envelope.methodId != 0) {
    return dispatcher.dispatch(envelope.methodId, envelope.argsBundle ?: android.os.Bundle())
}
```
Place this BEFORE the legacy reflective path (which remains for the event/legacy path in Plan A). Keep rate-limit/permission/monitor wrapping around it.

- [ ] **Step 3: IpcHostService — return Bundle responses**

When the router returns a `Bundle` (dispatcher path), put it in the response envelope's `argsBundle`:
```kotlin
val result = messageRouter.handleLocal(request, callerPackage, callingPid)
val resp = if (result is android.os.Bundle)
    IpcEnvelope(requestId = request.requestId, argsBundle = result)
else IpcEnvelope.response(request.requestId, com.falcon.ipc.protocol.IpcSerializer.serializeResult(result))
return resp
```
(The legacy branch stays until IpcSerializer is removed in Plan B.)

- [ ] **Step 4: BinderTransport — surface Bundle responses**

In `invoke`, when not error: if `response.argsBundle != null` return `TransportResult.Success(response.argsBundle)` else `TransportResult.Success(response.args)`. (Generated proxy reads `result.data as? Bundle`.)

- [ ] **Step 5: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/core/ServiceRegistry.kt falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt falcon-core/src/main/java/com/falcon/ipc/transport/BinderTransport.kt
git commit -m "feat: route @IpcMethod through generated dispatcher with Bundle payloads"
```

### Task 8: FalconManager — generated registries; register/getService via factories

**Files:**
- Modify: `FalconConfig.kt`, `Falcon.kt`, `core/FalconManager.kt`

- [ ] **Step 1: FalconConfig — generated(...) DSL**

```kotlin
internal val generatedRegistries = mutableListOf<com.falcon.ipc.runtime.FalconGeneratedRegistry>()
fun generated(registry: com.falcon.ipc.runtime.FalconGeneratedRegistry) { generatedRegistries.add(registry) }
```

- [ ] **Step 2: FalconManager.register — build dispatcher via factory**

```kotlin
fun <T : IpcService> register(serviceClass: KClass<T>, impl: T) {
    val key = serviceClass.qualifiedName ?: throw IllegalArgumentException("no qualified name")
    val factory = config.generatedRegistries.firstNotNullOfOrNull { it.dispatcherFactories[key] }
        ?: throw IllegalStateException("No generated dispatcher for $key — is the KSP registry passed via generated(...)?")
    serviceRegistry.registerDispatcher(key, factory(impl))
    FalconLogger.i("Falcon", "Service registered: $key")
}
```

- [ ] **Step 3: FalconManager.getService — build proxy via factory**

In the remote branch, when `__check_service__` confirms, build the proxy from the generated factory instead of `ProxyFactory.create`:
```kotlin
val factory = config.generatedRegistries.firstNotNullOfOrNull { it.proxyFactories[key] }
    ?: return null
@Suppress("UNCHECKED_CAST")
return factory(peer.transport, key) as T
```
Keep the local-registry short-circuit at the top — but note local now holds dispatchers, not impls; for local in-process calls return null here (local same-process direct calls are out of scope; same-process callers use the impl directly). Remove the `local impl` early-return that cast to T (it no longer holds the impl). Document this.

- [ ] **Step 4: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Update any unit test that called `register`/`getService` expecting the old behavior; if a test registered a raw impl and dispatched reflectively, point it at a generated dispatcher or move it to the instrumented test (Task 10). Report changes.

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt falcon-core/src/main/java/com/falcon/ipc/Falcon.kt falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt
git commit -m "feat: register/getService via generated factories (generated(...) DSL)"
```

### Task 9: Contract — delete dynamic ProxyFactory + reflective @IpcMethod dispatch

**Files:**
- Delete: `core/ProxyFactory.kt` (+ its test if any)
- Modify: `core/MessageRouter.kt` (remove reflective findMethod/resolveMethod/methodCache used only by @IpcMethod)

- [ ] **Step 1: Confirm no remaining references to ProxyFactory**

Run: `grep -rn "ProxyFactory" falcon-core/src` — expected: none after Task 8 (getService uses factories). If the legacy event path still references it, leave a minimal shim and report; otherwise delete.

- [ ] **Step 2: Delete ProxyFactory + remove reflective dispatch**

`git rm` ProxyFactory.kt. In MessageRouter, remove `findMethod`/`resolveMethod`/`methodCache` and the reflective invoke branch IF the dispatcher path fully covers @IpcMethod and the only remaining reflective need is the legacy event path. If events still need reflection in Plan A, keep a clearly-commented minimal reflective fallback and note it for Plan B. (Do NOT delete IpcSerializer in Plan A — events still use it.)

- [ ] **Step 3: Build + suite**

Run: `./gradlew :falcon-core:testDebugUnitTest` then `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete dynamic ProxyFactory + reflective @IpcMethod dispatch"
```

---

## Phase 3 (cont.) — Instrumented verification

### Task 10: Two-process request/response instrumented test

**Files:**
- Create: `falcon-benchmark/src/androidTest/java/com/falcon/benchmark/FalconReqRespInstrumentedTest.kt`

- [ ] **Step 1: Write the instrumented test**

Using the benchmark app's existing two-process setup (BenchmarkHostService runs in a `:remote` process), register a service in the remote process via Falcon, then from the main process `getService` a proxy and call an `@IpcMethod` with a primitive and a Parcelable arg; assert the round-tripped result. Use `androidx.test.ext.junit.runners.AndroidJUnit4` and `ApplicationProvider`. (Mirror the existing benchmark host wiring; the test asserts a value returned from the remote process.)

```kotlin
@RunWith(AndroidJUnit4::class)
class FalconReqRespInstrumentedTest {
    @Test fun echo_round_trips_across_processes() {
        // init Falcon with generated(...) registry; bind; getService; call; assert
        // (concrete wiring mirrors BenchmarkHostService / BenchmarkRunner)
    }
}
```
Fill the body to match the benchmark's actual service interface and host bootstrap (read `BenchmarkHostService.kt`/`BenchmarkRunner.kt` first).

- [ ] **Step 2: Run on a device/emulator**

Run: `./gradlew :falcon-benchmark:connectedDebugAndroidTest`
Expected: PASS (requires a connected device/emulator). If none is available in the environment, report that the test is written and must be run on CI/device; do NOT mark complete without a green run.

- [ ] **Step 3: Commit**

```bash
git add falcon-benchmark/src/androidTest/java/com/falcon/benchmark/FalconReqRespInstrumentedTest.kt
git commit -m "test: two-process @IpcMethod round-trip instrumented test"
```

### Task 11: Update benchmark to new API + docs + final build

**Files:**
- Modify: benchmark bootstrap (pass `generated(...)`), `CLAUDE.md`

- [ ] **Step 1: Update benchmark Falcon init to pass the generated registry**

Wherever the benchmark calls `Falcon.init`, add `generated(<Module>FalconGeneratedRegistry)`. Read the benchmark's init site first.

- [ ] **Step 2: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Update CLAUDE.md**

Document: `@IpcMethod` calls now dispatch via KSP-generated `Xxx_Dispatcher`/`Xxx_Proxy` with Bundle payloads + stable methodId (no reflection, no `IpcSerializer` for request/response). Consumers must pass the generated registry via `generated(...)` in `Falcon.init`. Note events/streams/callbacks still use the legacy path until Plan B.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs+benchmark: wire generated registry; document Binder-native @IpcMethod path"
```

---

## Self-Review Notes

- **Spec coverage (phases 1–3 + req/resp test):** methodId→Task 1; Bundle envelope→Task 2; BundleCodec/IpcDispatcher→Task 3; Dispatcher gen→Task 4; Proxy gen→Task 5; Registry gen→Task 6; runtime delegate + Bundle responses→Task 7; register/getService factories + generated() DSL→Task 8; delete ProxyFactory/reflective @IpcMethod→Task 9; instrumented test→Task 10; benchmark+docs→Task 11. Events/streams/callbacks + IpcSerializer deletion = Plan B (explicitly deferred).
- **Type consistency:** `MethodId.signatureHash(name, paramQNs)` shared by Task 1/4/5. `IpcDispatcher.dispatch(methodId, args): Bundle` defined Task 3, generated Task 4, called Task 7. `FalconGeneratedRegistry.{dispatcherFactories,proxyFactories}` defined Task 3, generated Task 6, consumed Task 8. `BundleCodec` defined Task 3, used by generators Task 4/5. `argsBundle`/`methodId` on IpcEnvelope from Task 2 used in Task 5/7.
- **Green-at-each-commit:** parallel-change — old `args: ByteArray`/IpcSerializer/StubGenerator remain through Plan A (events depend on them); only dynamic ProxyFactory + reflective @IpcMethod dispatch are deleted (Task 9).
- **Instrumented test** requires a device; Task 10 must not be marked done without a green connected run.
- **Risk:** KSP generator tasks are verified by compiling `falcon-benchmark` (real annotated services) — the generators' output must compile there. If the benchmark lacks a Parcelable-arg method, Task 10/5 should add a small test service interface in the benchmark to exercise the Parcelable path.
