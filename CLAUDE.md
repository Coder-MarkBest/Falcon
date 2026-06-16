# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Falcon IPC Framework

Android 车载系统跨进程通信(IPC)框架，基于 Binder + SharedMemory 混合架构，KSP 编译期代码生成。

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

### Core design: Binder + SharedMemory hybrid transport
- **< 64KB** data → Binder direct transfer (kernel-level, lowest latency)
- **≥ 64KB** data → SharedMemory zero-copy (the `SharedMemory` is Parcelable and travels inside `IpcEnvelope`; the kernel dups the FD to the receiver — no token/registry indirection)
- Transport is selected by payload size via `TransportSelector.shouldUseSharedMemory`, applied on both request (ProxyFactory) and response (IpcHostService) paths

### Key packages in falcon-core
- `com.falcon.ipc` — Falcon entry point, FalconConfig DSL
- `com.falcon.ipc.core` — FalconManager, ServiceRegistry, PeerManager, MessageRouter, IpcHostService, IpcRegistryProvider
- `com.falcon.ipc.transport` — BinderTransport, SharedMemoryTransport, IpcTransport interface
- `com.falcon.ipc.protocol` — IpcEnvelope (Parcelable message), IpcResult (sealed class), ErrorCode
- `com.falcon.ipc.security` — SignatureGuard (mandatory signature check), PermissionChecker (allow/deny lists), RateLimiter
- `com.falcon.ipc.monitor` — MonitorFacade (stats OFF by default), IpcInterceptor, IpcCallStats, MonitorLevel
- `com.falcon.ipc.service` — IpcService marker, IpcReply callback

### Service discovery
- IpcRegistryProvider (ContentProvider, multiprocess=true) stores service registry in SQLite
- PeerManager uses ContentObserver to detect new services and bindService() + linkToDeath() for connections
- Exponential backoff reconnection (500ms → 30s max)

### Security model
- Signature verification: mandatory, cannot be disabled
- Permission control: @IpcPermission annotation + DSL access rules
- SharedMemory: secured by kernel FD passing (dup'd per Binder transaction) + the mandatory signature check on bind/invoke
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
