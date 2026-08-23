# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Falcon IPC Framework

Android 车载系统跨进程通信框架。Binder 为统一传输层，上层同时提供 KSP Typed RPC 和无需共享源码的 Dynamic RPC。

## Build Commands

```bash
# Build all modules
./gradlew build

# Build falcon-core library only
./gradlew :falcon-core:assembleRelease

# Run unit tests
./gradlew :falcon-core:test

# Run a single test class
./gradlew :falcon-core:test --tests "com.falcon.ipc.security.RateLimiterTest"

# Run a single test method
./gradlew :falcon-core:test --tests "com.falcon.ipc.security.RateLimiterTest.allows calls within limit"

# Build benchmark APK
./gradlew :falcon-benchmark:assembleDebug

# Build cross-app demo APKs (two independently-signed APKs)
./gradlew :falcon-cross-server:assembleDebug
./gradlew :falcon-cross-client:assembleDebug
```

## Architecture

Seven Gradle modules:

```
falcon-annotations (pure Kotlin, no Android)
       ↑
falcon-core (Android library — runtime framework)
       ↑
falcon-ksp (KSP processor — generates Stub/Proxy from annotated interfaces)
       ↑
falcon-benchmark    (Android app — compares Falcon vs AIDL vs Messenger vs ContentProvider vs Broadcast)
falcon-cross-server (Headless server APK — all IPC patterns, independently signed)
falcon-cross-client (Client APK with UI — discovers cross-server via cross-app IPC)
falcon-gradle-plugin (AGP plugin — auto-injects <queries> for peerPackages)
```

Integration/usage guide for third-party consumers: `docs/USAGE.md`. The runnable
cross-app example is `falcon-cross-server` + `falcon-cross-client` (two independently-signed
APKs with mutual certificate trust, simulating a multi-vendor automotive system).
The benchmark module's `FalconGeneratedRoundTripTest` provides JVM round-trip coverage
over the real generated Proxy/Dispatcher code.

### Core design: Binder transport
- All calls go over Binder (`IIpcHost.invoke(IpcEnvelope)`); payloads are kept under the ~1MB Binder transaction limit (no large-payload use case in scope)
- The SharedMemory hybrid path was removed (see `docs/superpowers/specs/2026-06-17-overengineering-audit.md`) — it was unused dead weight given no high-frequency large payloads

### Dispatch: Binder-native, with two API modes
Typed RPC (`@IpcMethod`, `@IpcEvent`/`@IpcStream`, `@IpcCallback`) is handled by KSP-generated classes with typed `Bundle` payloads and stable method ids:
- **`Xxx_Dispatcher`** (server): `dispatch(methodId, Bundle): Bundle` for request/response; `eventFlow(methodId): Flow<Bundle>?` for events/streams; `invokeCallback(methodId, Bundle, reply)` for callbacks. No reflection.
- **`Xxx_Proxy`** (client): packs args into a typed `Bundle`, sends over Binder with the `methodId`. Events use `EventProxy.typedRemoteFlow` (subscribe + decode); callbacks use `transport.invokeCallback`.
- Server-side event Flows are collected lazily and ref-counted by `EventCollector` (collect on first subscriber, cancel on last).
- `methodId` stability: renaming a method is a breaking API change; overloads are safe (each signature hashes distinctly).
- Supported wire types: primitives, String, ByteArray, Parcelable, enum (data classes must be Parcelable). Unsupported types fail at KSP compile time.
- Consumers must supply the generated registry at init time:
  ```kotlin
  Falcon.init(context) {
      generated(<Module>FalconGeneratedRegistry)   // e.g. BenchmarkFalconGeneratedRegistry
  }
  ```
- Removed legacy: `IpcSerializer`, dynamic `ProxyFactory`, `MessageRouter` reflective dispatch, `EventBus`, `StubGenerator`/`_Stub`, and the `IpcEnvelope` byte-array `args` field — the wire format is Bundle-only.

Dynamic RPC uses the same Binder, discovery, permission, rate-limit, interceptor and monitor path, but addresses versioned routes/topics by string. `DynamicPayload` only permits platform wire types, so independently built APKs do not share Kotlin interfaces or model classes. Peer/version negotiation is cached and invalidated on disconnect.

> **Verification gap:** end-to-end generated dispatch (request/response, events, callbacks) is covered by JVM round-trip tests through the real generated code (`falcon-benchmark` `FalconGeneratedRoundTripTest`). True cross-process (two-process Binder) verification still requires a device/emulator and is not run in CI-less environments.

### Benchmark (`falcon-benchmark`)
`BenchmarkActivity` compares five cross-process mechanisms in one run, printing avg/p50/p99 per payload size:
- **Raw AIDL**, **Messenger** (both via `BenchmarkHostService` in `:benchmark_remote`), **ContentProvider** (`BenchmarkProvider`), **Falcon** (binds `IpcHostService` directly + calls through the KSP-generated proxy over `BinderTransport` — steady-state call path, discovery excluded, same as AIDL), and **Broadcast** (real round-trip via AMS — *not* request/reply, included with a caveat).
- The Falcon service is registered in the `:benchmark_remote` process by `BenchmarkApp` (per-process `Falcon.init { generated(...) }` + `register`).
- Results are device-dependent; only meaningful within a single run on the same device. The APK must be run on a device/emulator — numbers are not produced by the build.

### Key packages in falcon-core
- `com.falcon.ipc` — Falcon entry point, FalconConfig DSL
- `com.falcon.ipc.core` — FalconManager, ServiceRegistry, PeerManager, MessageRouter, IpcHostService, IpcRegistryProvider
- `com.falcon.ipc.transport` — BinderTransport, IpcTransport interface
- `com.falcon.ipc.protocol` — IpcEnvelope (Parcelable message), IpcResult (sealed class), ErrorCode
- `com.falcon.ipc.security` — SignatureGuard (mandatory signature check), PermissionChecker (allow/deny lists), RateLimiter
- `com.falcon.ipc.monitor` — MonitorFacade (stats OFF by default), IpcInterceptor, IpcCallStats, MonitorLevel
- `com.falcon.ipc.service` — IpcService marker, IpcReply callback
- `com.falcon.ipc.dynamic` — source-decoupled request/response payloads and capabilities

### Service discovery
- IpcRegistryProvider keeps an ephemeral in-memory registry in its designated host process
- PeerManager uses ContentObserver and ContentProvider.call("getHost") to receive the Binder directly, then linkToDeath() for connections
- Exponential backoff reconnection (500ms → 30s max)

### Security model
- Signature verification: trusted-signature allowlist — caller admitted iff every package in its UID is signed by a cert in `SecurityConfig.trustedSignatures` (SHA-256 hex) ∪ the app's own signature. Fail-closed when unset (only same-signature callers). Verified on bind/invoke via `Binder.getCallingUid()`.
- Permission control: DSL access rules (`SecurityConfig.accessRules`), keyed by **caller process name** (with package name as fallback; both resolved from the Binder UID/PID)
- Rate limiting: per-PID sliding window, enforced in MessageRouter on every call

## Annotations (falcon-annotations)

| Annotation | Purpose | Method signature |
|---|---|---|
| `@IpcMethod` | Request-response | `suspend fun method(): T` |
| `@IpcCallback` | Async with callback | `fun method(args, IpcReply<T>)` |
| `@IpcEvent` | Pub-sub events | `fun method(): Flow<T>` |
| `@IpcStream` | Large data stream | `fun method(): Flow<ByteArray>` |

Access control is configured via the `security { accessRules = ... }` DSL, not an annotation.

## Conventions

- Kotlin-first, coroutines over Handler/Thread
- Typed RPC complex data implements Parcelable; Dynamic RPC uses platform-only DynamicPayload fields
- Logs via FalconLogger, TAG format: `Falcon:[Module]`
- Monitor/stats default OFF (MonitorLevel.NONE) — zero overhead in production
- Production release checklist: `docs/PRODUCTION.md`
- AIDL files in `src/main/aidl/`, enabled via `buildFeatures { aidl = true }`

## Design docs

- Spec: `docs/superpowers/specs/2026-06-16-falcon-ipc-design.md`
- Implementation plan: `docs/superpowers/plans/2026-06-16-falcon-implementation.md`
