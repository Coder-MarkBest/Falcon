# P0 — Security Model Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the same-signature-only check with a trusted-signature allowlist and switch permission identity from process name to package name, so third-party callers can be admitted while staying fail-closed by default.

**Architecture:** Core trust decision is a pure function `SignatureGuard.isTrusted(callerHashes, trusted)` (JVM-unit-tested). `verify` builds the trusted set as `{selfSignature} ∪ config.trustedSignatures` and checks every caller package's signature against it. Caller identity for permission rules becomes the package name resolved from `Binder.getCallingUid()`.

**Tech Stack:** Kotlin, Android (Binder/PackageManager), JUnit/Robolectric/Mockito.

**Spec:** `docs/superpowers/specs/2026-06-17-p0-security-model.md`

**Build/test:** `./gradlew :falcon-core:compileDebugKotlin`, `./gradlew :falcon-core:testDebugUnitTest`, full `./gradlew build`. (JDK 17 preconfigured.)

---

## Task 1: `isTrusted` pure function + unit tests

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt`
- Test (create): `falcon-core/src/test/java/com/falcon/ipc/security/SignatureTrustTest.kt`

- [ ] **Step 1: Write the failing test (pure JVM, no Robolectric)**

Create `SignatureTrustTest.kt`:
```kotlin
package com.falcon.ipc.security

import org.junit.Assert.*
import org.junit.Test

class SignatureTrustTest {
    @Test fun `self signature is trusted`() {
        assertTrue(SignatureGuard.isTrusted(setOf("selfhash"), setOf("selfhash")))
    }
    @Test fun `whitelisted third-party signature is trusted`() {
        assertTrue(SignatureGuard.isTrusted(setOf("thirdhash"), setOf("selfhash", "thirdhash")))
    }
    @Test fun `unknown signature is rejected`() {
        assertFalse(SignatureGuard.isTrusted(setOf("evilhash"), setOf("selfhash")))
    }
    @Test fun `empty caller signatures rejected`() {
        assertFalse(SignatureGuard.isTrusted(emptySet(), setOf("selfhash")))
    }
    @Test fun `mixed caller packages - one untrusted rejects all`() {
        assertFalse(SignatureGuard.isTrusted(setOf("selfhash", "evilhash"), setOf("selfhash")))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.security.SignatureTrustTest"`
Expected: FAIL (unresolved reference `isTrusted`)

- [ ] **Step 3: Add the pure function to SignatureGuard**

Add a companion object to `SignatureGuard` (keep the rest of the class as-is for now):
```kotlin
companion object {
    /** True iff every caller package signature is in the trusted set (and there is at least one). */
    fun isTrusted(callerSignatureHashes: Set<String>, trusted: Set<String>): Boolean =
        callerSignatureHashes.isNotEmpty() && trusted.containsAll(callerSignatureHashes)
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.security.SignatureTrustTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt falcon-core/src/test/java/com/falcon/ipc/security/SignatureTrustTest.kt
git commit -m "feat: add SignatureGuard.isTrusted pure trust-decision function"
```

---

## Task 2: Add `trustedSignatures` to SecurityConfig

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt`

- [ ] **Step 1: Add the field**

In `SecurityConfig`, add a `trustedSignatures` field (SHA-256 hex of trusted signing certs):
```kotlin
data class SecurityConfig(
    var signatureVerification: Boolean = true,
    var accessRules: Map<String, AccessRule> = emptyMap(),
    var rateLimitPerSecond: Int = 1000,
    var maxConcurrentCalls: Int = 50,
    var trustedSignatures: Set<String> = emptySet()
)
```

- [ ] **Step 2: Build**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/FalconConfig.kt
git commit -m "feat: add SecurityConfig.trustedSignatures allowlist"
```

---

## Task 3: Rewrite SignatureGuard.verify to use the trusted set

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt`
- Test: `falcon-core/src/test/java/com/falcon/ipc/security/SignatureGuardTest.kt`

- [ ] **Step 1: Update the Robolectric tests to the new semantics (write first)**

Replace the body of `SignatureGuardTest` (keep imports; add `java.security.MessageDigest`). The old `verify returns false for different UID` relied on the UID hard-reject which is being removed — replace with signature-based cases:
```kotlin
private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(Signature(s.toByteArray()).toByteArray())
        .joinToString("") { "%02x".format(it) }

@Test
fun `verify returns true for own signature`() {
    val uid = Process.myUid()
    whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.falcon.test"))
    val pkgInfo = PackageInfo().apply { signatures = arrayOf(Signature("self-cert".toByteArray())) }
    whenever(packageManager.getPackageInfo("com.falcon.test", PackageManager.GET_SIGNATURES))
        .thenReturn(pkgInfo)
    val guard = SignatureGuard()
    guard.init(context)  // no trusted sigs -> only self trusted
    assertTrue(guard.verify(context, uid))
}

@Test
fun `verify returns false for untrusted third-party signature`() {
    val uid = 99999
    whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.evil"))
    whenever(packageManager.getPackageInfo("com.evil", PackageManager.GET_SIGNATURES))
        .thenReturn(PackageInfo().apply { signatures = arrayOf(Signature("evil-cert".toByteArray())) })
    // self
    whenever(packageManager.getPackagesForUid(Process.myUid())).thenReturn(arrayOf("com.falcon.test"))
    whenever(packageManager.getPackageInfo("com.falcon.test", PackageManager.GET_SIGNATURES))
        .thenReturn(PackageInfo().apply { signatures = arrayOf(Signature("self-cert".toByteArray())) })
    val guard = SignatureGuard()
    guard.init(context)
    assertFalse(guard.verify(context, uid))
}

@Test
fun `verify returns true for whitelisted third-party signature`() {
    val uid = 88888
    whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.partner"))
    whenever(packageManager.getPackageInfo("com.partner", PackageManager.GET_SIGNATURES))
        .thenReturn(PackageInfo().apply { signatures = arrayOf(Signature("partner-cert".toByteArray())) })
    whenever(packageManager.getPackageInfo("com.falcon.test", PackageManager.GET_SIGNATURES))
        .thenReturn(PackageInfo().apply { signatures = arrayOf(Signature("self-cert".toByteArray())) })
    val guard = SignatureGuard()
    guard.init(context, setOf(sha256("partner-cert")))  // pin partner cert
    assertTrue(guard.verify(context, uid))
}
```
Keep the `setup()` block (context/packageManager mocks, `context.packageName` = "com.falcon.test"). Note `init` now takes an optional `trustedSignatures` param (added in Step 2).

- [ ] **Step 2: Run to verify the tests fail**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.security.SignatureGuardTest"`
Expected: FAIL (`init` has no 2-arg form; whitelist case rejected by old self-only logic)

- [ ] **Step 3: Rewrite SignatureGuard verify/init**

Add field `private var trustedSignatures: Set<String> = emptySet()`. Change `init` and `computeVerification`:
```kotlin
fun init(context: Context, trustedSignatures: Set<String> = emptySet()) {
    selfUid = Process.myUid()
    selfSignatureHash = computeSignatureHash(context, context.packageName)
    this.trustedSignatures = trustedSignatures
    FalconLogger.d("Security", "SignatureGuard initialized, UID=$selfUid, trusted=${trustedSignatures.size}")
}

private fun computeVerification(context: Context, callingUid: Int): Boolean {
    val callerPkgs = context.packageManager.getPackagesForUid(callingUid) ?: return false
    if (callerPkgs.isEmpty()) return false
    val trusted = trustedSignatures + selfSignatureHash
    val callerHashes = mutableSetOf<String>()
    for (pkg in callerPkgs) {
        val hash = try {
            computeSignatureHash(context, pkg)
        } catch (e: Exception) {
            FalconLogger.e("Security", "Failed to read signature for $pkg", e)
            return false
        }
        callerHashes.add(hash)
    }
    return isTrusted(callerHashes, trusted)
}
```
Keep `verify` (cache wrapper) and `computeSignatureHash` unchanged. Remove the `callingUid != selfUid` early-return entirely (this is the bug fix). `isTrusted` is the companion function from Task 1.

- [ ] **Step 4: Update FalconManager to pass trusted signatures**

In `FalconManager.kt`, change the SignatureGuard init line from `SignatureGuard().apply { init(context) }` to:
```kotlin
internal val signatureGuard = SignatureGuard().apply { init(context, config.security.trustedSignatures) }
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :falcon-core:testDebugUnitTest --tests "com.falcon.ipc.security.SignatureGuardTest"`
Expected: PASS. Then full suite `./gradlew :falcon-core:testDebugUnitTest` — PASS.

- [ ] **Step 6: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/security/SignatureGuard.kt falcon-core/src/main/java/com/falcon/ipc/core/FalconManager.kt falcon-core/src/test/java/com/falcon/ipc/security/SignatureGuardTest.kt
git commit -m "fix: SignatureGuard trusted-signature allowlist (admit whitelisted third parties, fail-closed default)"
```

---

## Task 4: Caller identity → package name (UID-based)

**Files:**
- Modify: `falcon-core/src/main/java/com/falcon/ipc/util/CallerResolver.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt`
- Modify: `falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt`
- Modify: `falcon-core/src/test/java/com/falcon/ipc/core/MessageRouterTest.kt` (param rename only)

- [ ] **Step 1: Rewrite CallerResolver to resolve UID → package name**

```kotlin
package com.falcon.ipc.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Resolves a Binder calling UID to a package name, cached. */
class CallerResolver(private val context: Context) {
    private val cache = ConcurrentHashMap<Int, String>()

    fun resolve(uid: Int): String {
        cache[uid]?.let { return it }
        val name = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
        cache[uid] = name
        return name
    }
}
```
(Removes the ActivityManager/PID import; now uses PackageManager.)

- [ ] **Step 2: IpcHostService — resolve package from UID**

In `hostBinder.invoke`, change the caller-identity line from `val callerProcess = callerResolver.resolve(callingPid)` to:
```kotlin
val callerPackage = callerResolver.resolve(callingUid)
```
and update the dispatch call to `messageRouter.handleLocal(request, callerPackage, callingPid)`. (`callingUid`/`callingPid` are still both captured at method entry.)

- [ ] **Step 3: MessageRouter — rename the identity parameter**

In `MessageRouter.handleLocal`, rename the second parameter `callerProcess` to `callerPackage` and update its uses (the two `permissionChecker.check(..., callerPackage)` calls and the `SecurityException` message). This is a pure rename — no logic change.

- [ ] **Step 4: Update MessageRouterTest call sites (rename only)**

The existing tests pass a string like `"proc"` / `"intruder"` as the second arg — these still compile (positional). No change needed unless a test references a parameter name. Run the suite to confirm. If any test used named arg `callerProcess = ...`, rename to `callerPackage = ...`.

- [ ] **Step 5: Build + full suite**

Run: `./gradlew :falcon-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL
Run: `./gradlew :falcon-core:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (PermissionChecker logic unchanged; identity source changed only in production path).

- [ ] **Step 6: Commit**

```bash
git add falcon-core/src/main/java/com/falcon/ipc/util/CallerResolver.kt falcon-core/src/main/java/com/falcon/ipc/core/IpcHostService.kt falcon-core/src/main/java/com/falcon/ipc/core/MessageRouter.kt falcon-core/src/test/java/com/falcon/ipc/core/MessageRouterTest.kt
git commit -m "fix: permission identity uses caller package (from Binder UID), not process name"
```

---

## Task 5: Final build + docs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Full multi-module build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (lint + all modules).

- [ ] **Step 2: Update CLAUDE.md security model**

Replace the signature-verification bullet (which currently has the temporary NOTE) with the final model:
- Signature verification: trusted-signature allowlist (`SecurityConfig.trustedSignatures`, SHA-256 of signing certs) ∪ self signature; fail-closed when unset (only same-signature callers). Verified on bind/invoke via `Binder.getCallingUid()`.
- Permission control: `@IpcPermission` / DSL access rules keyed by **caller package name** (resolved from UID).
Keep the rate-limiting bullet.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document trusted-signature allowlist + package-based permission identity"
```

---

## Self-Review Notes

- **Spec coverage:** isTrusted pure fn → Task 1; trustedSignatures config → Task 2; SignatureGuard rewrite + FalconManager init + fail-closed default → Task 3; CallerResolver UID→package + IpcHostService + MessageRouter rename → Task 4; docs → Task 5. All spec sections covered.
- **Signature consistency:** `SignatureGuard.init(context, trustedSignatures: Set<String> = emptySet())` defined in Task 3, called in Task 3 Step 4 (FalconManager). `isTrusted(Set<String>, Set<String>)` defined Task 1, used Task 3. `CallerResolver.resolve(uid)` Task 4. `handleLocal(envelope, callerPackage, callerPid)` Task 4.
- **Default safety:** Task 3 builds `trusted = trustedSignatures + selfSignatureHash`; empty config → self-only (fail-closed), matching the spec.
- **Android-bound parts** (CallerResolver PackageManager path, SignatureGuard PM plumbing) covered by Robolectric tests in Task 3 + review; the pure trust decision is JVM-unit-tested in Task 1.
