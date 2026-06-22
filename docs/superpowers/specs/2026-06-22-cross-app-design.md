# Cross-App Discovery & Security Design

> **Context:** This is the third phase of Falcon hardening. Phase 1 (2026-06-22) closed ANR/backpressure/watchdog/method-matching gaps within a single APK. This design opens Falcon to **multi-APK cross-App** — the target automotive ecosystem where apps are independently signed and deployed by different teams sharing a common API library.

**Goal:** App A can call `Falcon.getService<INavService>().getRoute()` and reach the implementation living in App B, with signature-and-permission security, with no code change beyond a one-line `peerPackages()` declaration.

**Key premise (confirmed):** All apps share a common interface module (`falcon-api`) maintained by the platform team, so methodId is always consistent.

---

## 1. Discovery

### 1.1 Current limitation

`FalconManager.kt:50-51` scopes discovery to the app's own package:

```kotlin
private val registryUri = Uri.parse(
    "content://${context.packageName}.falcon.registry/services"
)
```

`IpcRegistryProvider` is `exported="false"` → other apps' ContentResolver calls are rejected by the system.

### 1.2 Design: `peerPackages` DSL

The client declares which app packages it needs to communicate with. The framework discovers across multiple `IpcRegistryProvider` authorities and matches services transparently.

```kotlin
Falcon.init(context) {
    generated(DemoFalconGeneratedRegistry)
    peerPackages("com.oem.nav", "com.oem.media", "com.oem.hvac")
}
```

**Framework behavior for each peer package:**

1. Construct authority: `content://com.oem.nav.falcon.registry/services`
2. Poll each authority periodically (ContentObserver-driven, same `PeerManager` refresh model)
3. `call("getHost")` → obtain `IIpcHost` Binder directly (`Bundle.putBinder`)
4. `linkToDeath` for death detection; exponential backoff reconnection (existing `PeerManager`)

**Service matching (transparent):**

`Falcon.getService<INavService>()` probes ALL connected peers with `__check_service__`. The caller never needs to know which package hosts the service — framework maps service key → peer transparently.

**`<queries>` guard:**

On startup, framework checks each peer package against the manifest `<queries>` and throws a clear error with the exact XML fragment to paste:

```
Falcon: peer "com.oem.nav" not declared in <queries>. Add:
<queries>
    <package android:name="com.oem.nav" />
</queries>
```

A Gradle plugin that auto-generates `<queries>` from `peerPackages` is deferred (YAGNI for now).

### 1.3 Changes

| File | Change |
|------|--------|
| `FalconConfig.kt` | Add `peerPackages: Set<String>` + DSL `peerPackages(vararg)` |
| `FalconManager.kt` | Pass `peerPackages` to `PeerManager`; startup `<queries>` check |
| `PeerManager.kt` | `registryUri` → `registryUris: List<Uri>`; multi-authority discovery loops |
| AndroidManifest `IpcRegistryProvider` | `exported="true"` |
| Consumer manifest | Must add `<queries><package>` entries per peer |

---

## 2. Security Model

### 2.1 Signature trust: `trustedSignatures` (no code change needed)

The server-side app declares which external certificates it trusts. `SignatureGuard` already supports this — it just wasn't exercised in single-app mode.

```kotlin
Falcon.init(context) {
    security {
        trustedSignatures = setOf(
            "a1b2c3d4e5f6...",   // com.oem.hvac cert SHA-256
            "f6e5d4c3b2a1..."    // com.oem.media cert SHA-256
        )
    }
}
```

**Runtime path (each IPC call):**

```
caller UID → PackageManager.getPackagesForUid() → get signing cert → SHA-256
  → (callerHash in trustedSignatures ∪ selfSignatureHash)?
    yes → proceed to rate-limit + permission check
    no  → IpcEnvelope.error(ErrorCode.UNAUTHORIZED, "Signature mismatch")
```

No code changes to `SignatureGuard` — its existing `computeVerification` method already handles this.

### 2.2 Permission control: `accessRules` by caller package (no code change needed)

Existing `AccessRule` uses caller process-name (from ActivityManager) with package-name fallback. Cross-App this is crucial — rules keyed by the caller's package name:

```kotlin
security {
    accessRules = mapOf(
        "com.oem.api.INavService" to AccessRule(
            allowList = setOf("com.oem.cluster", "com.oem.hud")
        )
    )
}
```

No code changes — `PermissionChecker.check(serviceKey, callerPackage, callerProcess)` already reads `allowList`/`denyList` against both process name and package name.

### 2.3 `IpcRegistryProvider` hardening (NEW — 3 guards)

With `exported="true"`, the provider's content-resolver endpoints are reachable from other apps. Three protections:

| Endpoint | Guard | Rationale |
|----------|-------|-----------|
| `call("getHost")` | `enforceSignature()` | Only trusted-signature callers get the Binder. Already implemented. |
| `insert` | `Binder.getCallingUid() == Process.myUid()` | Only the local process can register services. External apps cannot inject fake registrations. |
| `query` | `enforceSignature()` | Only trusted peers can read the registry list. Already implemented. |

### 2.4 Security chain (two gates)

```
External caller
  │
  ├─→ Gate 1: IpcRegistryProvider.call("getHost")
  │     └ enforceSignature() → reject untrusted apps at the door
  │     └ return IIpcHost Binder
  │
  └─→ Gate 2: IpcHostService.invoke()  (every IPC call)
        ├ SignatureGuard.verify()     ← per-call, real-time
        ├ RateLimiter.check()         ← per-PID sliding window (already cross-PID)
        └ PermissionChecker.check()   ← per-caller-package accessRules
```

### 2.5 Changes

| File | Change |
|------|--------|
| `IpcRegistryProvider.kt` | `insert` adds same-UID guard before `enforceSignature`; `call`/`query` retain existing `enforceSignature` |
| No other files | `SignatureGuard` / `PermissionChecker` / `RateLimiter` / `AccessRule` — untouched |

---

## 3. Version & Compatibility

### 3.1 Shared API library strategy

All interfaces live in a platform-maintained `falcon-api` module consumed by every app as a compile-time dependency (`compileOnly` or a shared AAR/jar). This guarantees:

- Method signatures are identical across all apps at build time
- `methodId` (FNV-1a hash of name + param types) is consistent
- KSP generates Proxy in the client and Dispatcher in the server from the same interface source

### 3.2 Evolution rules (convention, not enforcement)

| Rule | Rationale |
|------|-----------|
| **Never mutate an existing method's signature or return type** | Add a new method instead. Old callers get `METHOD_NOT_FOUND` (1002, implemented in Phase 1) for changed/removed methods — a clean typed error, not silent corruption. |
| **New methods are backward-compatible** | Old callers don't know about them, old servers don't serve them — `METHOD_NOT_FOUND` handles both directions. |
| **Parcelable field order must not change** | This is the one case lightweight method matching cannot detect (same methodId, different wire layout). Rely on the convention above + platform-level Parcelable versioning (e.g. Gradle lint rule). |

No interface-version hashing, no `@IpcContract` annotations, no discovery-time negotiation — the Phase 1 per-method `METHOD_NOT_FOUND` + the shared library convention is the right level for this ecosystem.

---

## 4. Manifest Requirements (Server App)

```xml
<!-- IpcRegistryProvider: exported for cross-app discovery -->
<provider
    android:name="com.falcon.ipc.core.IpcRegistryProvider"
    android:authorities="${applicationId}.falcon.registry"
    android:exported="true"
    android:process=":server" />

<!-- IpcHostService: the Binder host (same process as provider) -->
<service
    android:name="com.falcon.ipc.core.IpcHostService"
    android:exported="false"
    android:process=":server">
    <intent-filter>
        <action android:name="com.falcon.ipc.HOST_SERVICE" />
    </intent-filter>
</service>
```

**Client App:**
```xml
<queries>
    <package android:name="com.oem.nav" />
    <package android:name="com.oem.media" />
</queries>
```

---

## 5. Self-Review

- **Placeholders:** None. All behaviors are specified with code locations, DSL shapes, and error messages.
- **Internal consistency:** Discovery (Section 1) → Security (Section 2) → Version (Section 3) form a coherent flow. `peerPackages` feeds multi-authority `PeerManager`, `trustedSignatures` gates Provider + HostService, shared API library ensures methodId consistency.
- **Scope:** Focused on one subsystem (cross-App discovery + security). The `<queries>` auto-generation Gradle plugin is explicitly deferred. The `falcon-api` module structure is a convention recommendation, not a framework code change — it stays out of scope.
- **Ambiguity:** `peerPackages` vs existing single-package mode — when `peerPackages` is empty, behavior is unchanged (single-package, `exported=false`). `exported` attribute is a consumer manifest concern, not framework code responsibility.

**Deferred (not in this spec):**
- Gradle plugin for auto-generating `<queries>` from `peerPackages`
- The `falcon-api` shared module itself (platform-team concern, not framework code)
- Per-method permission granularity (current `AccessRule` is per-service, which is sufficient)
- Dynamic service discovery (polling PackageManager to auto-discover Falcon hosts without `peerPackages`)
