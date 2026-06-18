# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Falcon IPC Framework

Android 车载系统跨进程通信(IPC)框架，基于 Binder + KSP 编译期代码生成。

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
```

## Architecture

Four Gradle modules with clear dependency chain:

```
falcon-annotations (pure Kotlin, no Android)
       ↑
falcon-core (Android library — runtime framework)
       ↑
falcon-ksp (KSP processor — generates Stub/Proxy from annotated interfaces)
       ↑
falcon-benchmark (Android app — compares Falcon vs AIDL vs Messenger vs ContentProvider)
```

### Core design: Binder transport
- All calls go over Binder (`IIpcHost.invoke(IpcEnvelope)`); payloads are kept under the ~1MB Binder transaction limit (no large-payload use case in scope)
- The SharedMemory hybrid path was removed (see `docs/superpowers/specs/2026-06-17-overengineering-audit.md`) — it was unused dead weight given no high-frequency large payloads

### Dispatch: fully Binder-native (KSP-generated, no reflection / no `Parcel.marshall()`)
ALL IPC — `@IpcMethod` (request/response), `@IpcEvent`/`@IpcStream` (Flow), and `@IpcCallback` — is handled by KSP-generated classes with typed `Bundle` payloads and a stable `methodId` (FNV-1a hash of the method signature; `IpcReply` params are excluded so proxy and dispatcher agree):
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

> **Verification gap:** end-to-end generated dispatch (request/response, events, callbacks) is covered by JVM round-trip tests through the real generated code (`falcon-benchmark` `FalconGeneratedRoundTripTest`). True cross-process (two-process Binder) verification still requires a device/emulator and is not run in CI-less environments.

### Key packages in falcon-core
- `com.falcon.ipc` — Falcon entry point, FalconConfig DSL
- `com.falcon.ipc.core` — FalconManager, ServiceRegistry, PeerManager, MessageRouter, IpcHostService, IpcRegistryProvider
- `com.falcon.ipc.transport` — BinderTransport, IpcTransport interface
- `com.falcon.ipc.protocol` — IpcEnvelope (Parcelable message), IpcResult (sealed class), ErrorCode
- `com.falcon.ipc.security` — SignatureGuard (mandatory signature check), PermissionChecker (allow/deny lists), RateLimiter
- `com.falcon.ipc.monitor` — MonitorFacade (stats OFF by default), IpcInterceptor, IpcCallStats, MonitorLevel
- `com.falcon.ipc.service` — IpcService marker, IpcReply callback

### Service discovery
- IpcRegistryProvider (ContentProvider, multiprocess=true) stores service registry in SQLite
- PeerManager uses ContentObserver to detect new services and bindService() + linkToDeath() for connections
- Exponential backoff reconnection (500ms → 30s max)

### Security model
- Signature verification: trusted-signature allowlist — caller admitted iff every package in its UID is signed by a cert in `SecurityConfig.trustedSignatures` (SHA-256 hex) ∪ the app's own signature. Fail-closed when unset (only same-signature callers). Verified on bind/invoke via `Binder.getCallingUid()`.
- Permission control: @IpcPermission annotation + DSL access rules, keyed by **caller package name** (resolved from the Binder UID)
- Rate limiting: per-PID sliding window, enforced in MessageRouter on every call

## Annotations (falcon-annotations)

| Annotation | Purpose | Method signature |
|---|---|---|
| `@IpcMethod` | Request-response | `suspend fun method(): T` |
| `@IpcCallback` | Async with callback | `fun method(args, IpcReply<T>)` |
| `@IpcEvent` | Pub-sub events | `fun method(): Flow<T>` |
| `@IpcStream` | Large data stream | `fun method(): Flow<ByteArray>` |
| `@IpcPermission` | Access control | Applied to method or class |

## Conventions

- Kotlin-first, coroutines over Handler/Thread
- All cross-process data must implement Parcelable
- Logs via FalconLogger, TAG format: `Falcon:[Module]`
- Monitor/stats default OFF (MonitorLevel.NONE) — zero overhead in production
- AIDL files in `src/main/aidl/`, enabled via `buildFeatures { aidl = true }`

## Design docs

- Spec: `docs/superpowers/specs/2026-06-16-falcon-ipc-design.md`
- Implementation plan: `docs/superpowers/plans/2026-06-16-falcon-implementation.md`
