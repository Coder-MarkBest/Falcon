<div align="center">

# 🦅 Falcon IPC

**Type-safe, zero-reflection cross-process IPC for Android — powered by Binder + KSP compile-time codegen.**

[![CI](https://github.com/Coder-MarkBest/Falcon/actions/workflows/ci.yml/badge.svg)](https://github.com/Coder-MarkBest/Falcon/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Coder-MarkBest/Falcon.svg)](https://jitpack.io/#Coder-MarkBest/Falcon)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://developer.android.com/about/versions)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/Coder-MarkBest/Falcon.svg)](LICENSE)

**English** · [简体中文](README.zh-CN.md)

</div>

---

Falcon turns a plain Kotlin interface into a fully-typed cross-process API. You write the
interface and the implementation; KSP generates the marshalling Proxy and Dispatcher at
compile time. No `Parcel.marshall()`, no dynamic proxies, no reflection on the hot path —
just typed `Bundle`s over Binder with a stable, hash-based method id.

Built for Android automotive / multi-app systems where independently-signed APKs from
different vendors must talk to each other safely.

```kotlin
interface INavService : IpcService {
    @IpcMethod  suspend fun route(from: LatLng, to: LatLng): Route   // request / response
    @IpcEvent   fun locationUpdates(): Flow<Location>                // pub / sub
    @IpcStream  fun tiles(): Flow<ByteArray>                         // chunked stream
    @IpcCallback fun search(q: String, reply: IpcReply<List<Poi>>)   // async callback
}

// caller — just call the interface; the Binder hop is invisible
val nav = Falcon.getInstance().getServiceSuspending<INavService>()
val route = nav?.route(here, there)
```

## Why Falcon

| | Raw AIDL | Messenger | Falcon |
|---|:---:|:---:|:---:|
| Define service in pure Kotlin | ❌ `.aidl` files | ❌ manual `Handler` | ✅ annotated interface |
| Request / response | ✅ | ⚠️ manual | ✅ `suspend fun` |
| Pub/sub events & streams | ❌ DIY | ❌ DIY | ✅ `Flow<T>` |
| Async callbacks | ⚠️ manual | ❌ | ✅ `IpcReply<T>` |
| Coroutines-native | ❌ | ❌ | ✅ |
| Reflection on call path | — | — | ✅ **none** |
| Signature allow-list + per-call auth | DIY | DIY | ✅ built-in |
| Rate limiting / permissions / interceptors | DIY | DIY | ✅ built-in |
| Cross-app discovery & auto-reconnect | DIY | DIY | ✅ built-in |
| Schema-compatibility check between APKs | ❌ | ❌ | ✅ compile-hash |

## Highlights

- **Compile-time safety** — change a method signature and the build breaks, not production. The wire `methodId` is an FNV-1a hash of the signature, so proxy and dispatcher can never disagree.
- **Zero reflection** — KSP generates a `when(methodId)` dispatcher and a typed `Bundle` packer. Nothing is resolved by name at runtime.
- **Every IPC shape** — request/response, pub/sub events, chunked streams, and async callbacks, all coroutine/`Flow`-native.
- **Secure by default** — mandatory signature verification (trusted-cert allow-list, fail-closed), per-process permission rules, and a per-PID sliding-window rate limiter.
- **Resilient** — `ContentObserver`-driven discovery, `linkToDeath` recovery, exponential-backoff reconnection, and a foreground-service keep-alive so a headless server survives Android 14's cached-app freezer.
- **Multi-vendor ready** — two independently-signed APKs with mutual certificate trust and a wire-schema hash that refuses to bind mismatched builds.

## Install

Falcon is distributed via [JitPack](https://jitpack.io/#Coder-MarkBest/Falcon).

**1. Add the JitPack repository** (`settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

**2. Apply KSP and add the dependencies** (`build.gradle.kts` of the consuming module):

```kotlin
plugins {
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" // match your Kotlin version
}

dependencies {
    implementation("com.github.Coder-MarkBest.Falcon:falcon-core:1.0.0")
    ksp("com.github.Coder-MarkBest.Falcon:falcon-ksp:1.0.0")
    // falcon-annotations comes transitively via falcon-core
}
```

> The KSP plugin version must track your project's Kotlin version (`<kotlin>-<ksp>`).

## Quickstart

A complete request/response service in four steps. (Full integration — manifest entries,
cross-app signing, events/streams/callbacks — is in **[docs/USAGE.md](docs/USAGE.md)**.)

**1 · Define the contract** — a shared module both processes compile:

```kotlin
interface IGreeter : IpcService {
    @IpcMethod suspend fun greet(name: String): String
}
```

**2 · Implement it** (server side):

```kotlin
class GreeterImpl : IGreeter {
    override suspend fun greet(name: String) = "Hello, $name!"
}
```

**3 · Initialize Falcon once per process** (`Application.onCreate`):

```kotlin
Falcon.init(this) {
    generated(AppFalconGeneratedRegistry)   // KSP-generated, named after your interface package
}.register(IGreeter::class, GreeterImpl())  // server only
```

**4 · Call it from the other process / app**:

```kotlin
lifecycleScope.launch {
    val greeter = Falcon.getInstance().getServiceSuspending<IGreeter>()
    println(greeter?.greet("Falcon"))   // → "Hello, Falcon!"
}
```

Register the provider (and, for servers, the host service) in your manifest — see
[docs/USAGE.md](docs/USAGE.md) for the exact entries.

## IPC patterns

| Annotation | Shape | Signature |
|---|---|---|
| `@IpcMethod` | Request / response | `suspend fun m(args): T` |
| `@IpcEvent` | Pub / sub | `fun m(): Flow<T>` |
| `@IpcStream` | Chunked stream | `fun m(): Flow<ByteArray>` |
| `@IpcCallback` | Async callback | `fun m(args, reply: IpcReply<T>)` |

**Wire types:** primitives, `String`, `ByteArray`, `Parcelable`, enums, and `List`/`Map` of
those. `data class` payloads must implement `Parcelable` (hand-written, or via the
`kotlin-parcelize` `@Parcelize` plugin). Unsupported types fail at **KSP compile time**, not at
runtime.

## Architecture

```
 Caller process                                   Server process
 ───────────────                                  ───────────────
 Xxx_Proxy (generated)                            Xxx_Dispatcher (generated)
   pack args → typed Bundle                         when(methodId) → impl call
   send with stable methodId                        no reflection
        │                                                 ▲
        ▼                                                 │
 BinderTransport ──Binder──▶ IpcHostService ──▶ MessageRouter
                                                  signature → permission → rate-limit → interceptors
                              IpcRegistryProvider (ContentProvider) ── direct-binder discovery
```

Five-module dependency chain:

```
falcon-annotations   pure-Kotlin annotations (no Android)
      ↑
falcon-core          runtime: transport, security, discovery, monitor
      ↑
falcon-ksp           KSP processor → Proxy / Dispatcher / Registry
      ↑
falcon-benchmark · falcon-cross-server · falcon-cross-client   apps & demos
```

## Security model

- **Signature verification** *(fail-closed)* — a caller is admitted only if every package in its
  UID is signed by a certificate in `trustedSignatures` (SHA-256) ∪ the app's own signature.
  Unset ⇒ same-signature callers only. Enforced on every bind and every call via
  `Binder.getCallingUid()`.
- **Permission control** — DSL access rules keyed by caller **process name** (package as fallback).
- **Rate limiting** — per-PID sliding window, enforced in the router on every call.

```kotlin
Falcon.init(this) {
    generated(AppFalconGeneratedRegistry)
    peerPackages("com.vendor.other")          // cross-app peers to discover
    security {
        signatureVerification = true
        trustedSignatures = setOf("69d0…31e4") // peer cert SHA-256 (lowercase, no colons)
        rateLimitPerSecond = 200
        maxConcurrentCalls = 32
    }
    transport { binderPoolSize = 4; maxBinderPayloadSize = 256 * 1024 }
    timeout   { connectMs = 3_000; callMs = 5_000 }
}
```

## Performance

Steady-state cross-**app** round-trip (two independently-signed APKs, real Binder boundary +
per-call signature check), measured on an Android 14 emulator with the built-in benchmark
(cross-client button 17). Numbers are device-dependent and only comparable within one run:

| Call | avg | p50 | p99 |
|---|---:|---:|---:|
| `ping(String)` | 0.80 ms | 0.28 ms | 5.4 ms |
| `add(Int, Int)` | 1.99 ms | 1.57 ms | 7.3 ms |
| `echoBytes(4 KB)` | 1.91 ms | 1.51 ms | 7.6 ms |
| `getVehicleData()` *(Parcelable)* | 2.09 ms | 1.44 ms | 5.9 ms |

**Concurrency** — the built-in stress test (button 18) issues 8 workers × 500 = **4 000
concurrent cross-app calls**, every response validated: **0 errors, ~4 800 calls/s**.

The `falcon-benchmark` module additionally compares Falcon against raw AIDL, Messenger,
ContentProvider, and Broadcast in one run (intra-app). Run any benchmark on a device/emulator —
the build does not produce numbers.

## Demos & docs

- **Cross-app demo** — `falcon-cross-server` + `falcon-cross-client`: two independently-signed
  APKs with mutual certificate trust, exercising all 16 IPC patterns, a latency benchmark, and
  a concurrent stress test. Simulates a multi-vendor automotive system.
- **Integration guide** — [docs/USAGE.md](docs/USAGE.md).
- **Design spec** — [docs/superpowers/specs](docs/superpowers/specs).

## Build & test

```bash
./gradlew build                              # everything
./gradlew :falcon-core:testDebugUnitTest     # core unit tests
./gradlew :falcon-cross-server:testDebugUnitTest   # JVM round-trip over generated code
./gradlew :falcon-cross-server:assembleDebug :falcon-cross-client:assembleDebug
```

The cross-app demo keystores are committed (demo-only, password `falcondemo`) so the demo
builds and runs out of the box; their certificate fingerprints already match the trusted-signature
allow-lists in the demo apps.

## Roadmap

- [x] Zero-reflection KSP dispatch (request/response, events, streams, callbacks)
- [x] Cross-app discovery, mutual signature trust, schema-compat check
- [x] Foreground-service keep-alive for headless servers (Android 14 freezer-safe)
- [x] CI + JitPack distribution
- [ ] Publish to Maven Central
- [ ] Optional large-payload (SharedMemory) transport behind a flag

## Contributing

Issues and PRs welcome. Please run `./gradlew build` and the unit-test suites before opening a PR;
new wire behavior should come with a round-trip test in `falcon-cross-server` (see
`CrossServiceRoundTripTest`).

## License

[MIT](LICENSE) © Falcon IPC contributors
