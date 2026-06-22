<div align="center">

# 🦅 Falcon IPC

**面向 Android 的类型安全、零反射跨进程通信框架 —— 基于 Binder + KSP 编译期代码生成。**

[![CI](https://github.com/Coder-MarkBest/Falcon/actions/workflows/ci.yml/badge.svg)](https://github.com/Coder-MarkBest/Falcon/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/Coder-MarkBest/Falcon.svg)](https://jitpack.io/#Coder-MarkBest/Falcon)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://developer.android.com/about/versions)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/Coder-MarkBest/Falcon.svg)](LICENSE)

[English](README.md) · **简体中文**

</div>

---

Falcon 把一个普通的 Kotlin 接口变成完全类型化的跨进程 API。你只写接口和实现，KSP 在编译期
生成负责打包/分发的 Proxy 与 Dispatcher。调用热路径上没有 `Parcel.marshall()`、没有动态代理、
没有反射 —— 只有走 Binder 的类型化 `Bundle` 和一个稳定的、基于哈希的方法 id。

专为 **Android 车载 / 多 App 系统** 设计：来自不同供应商、独立签名的 APK 之间需要安全地互相通信。

```kotlin
interface INavService : IpcService {
    @IpcMethod  suspend fun route(from: LatLng, to: LatLng): Route   // 请求 / 响应
    @IpcEvent   fun locationUpdates(): Flow<Location>                // 发布 / 订阅
    @IpcStream  fun tiles(): Flow<ByteArray>                         // 分块流
    @IpcCallback fun search(q: String, reply: IpcReply<List<Poi>>)   // 异步回调
}

// 调用方 —— 直接调接口，Binder 跨进程对你透明
val nav = Falcon.getInstance().getServiceSuspending<INavService>()
val route = nav?.route(here, there)
```

## 为什么用 Falcon

| | 原生 AIDL | Messenger | Falcon |
|---|:---:|:---:|:---:|
| 用纯 Kotlin 定义服务 | ❌ 写 `.aidl` | ❌ 手写 `Handler` | ✅ 注解接口 |
| 请求 / 响应 | ✅ | ⚠️ 手动 | ✅ `suspend fun` |
| 事件与流（发布/订阅） | ❌ 自己造 | ❌ 自己造 | ✅ `Flow<T>` |
| 异步回调 | ⚠️ 手动 | ❌ | ✅ `IpcReply<T>` |
| 协程原生 | ❌ | ❌ | ✅ |
| 调用路径上的反射 | — | — | ✅ **完全没有** |
| 签名白名单 + 逐调用鉴权 | 自己造 | 自己造 | ✅ 内置 |
| 限流 / 权限 / 拦截器 | 自己造 | 自己造 | ✅ 内置 |
| 跨 App 发现与自动重连 | 自己造 | 自己造 | ✅ 内置 |
| APK 间 Schema 兼容性校验 | ❌ | ❌ | ✅ 编译期哈希 |

## 核心特性

- **编译期安全** —— 改了方法签名，编译就会失败，而不是上线后才崩。线上的 `methodId` 是方法
  签名的 FNV-1a 哈希，Proxy 和 Dispatcher 永远不会对不上。
- **零反射** —— KSP 生成 `when(methodId)` 分发器和类型化 `Bundle` 打包器，运行期不靠名字解析任何东西。
- **覆盖全部 IPC 形态** —— 请求/响应、发布订阅事件、分块流、异步回调，全部协程 / `Flow` 原生。
- **默认安全** —— 强制签名校验（可信证书白名单，失败即拒）、按进程的权限规则、按 PID 的滑动窗口限流。
- **健壮** —— `ContentObserver` 驱动发现、`linkToDeath` 死亡恢复、指数退避重连，以及前台服务保活，
  让无界面的 headless server 在 Android 14 的 cached-app 冻结器下也能存活。
- **多供应商就绪** —— 两个独立签名的 APK 互相信任证书，并用 wire-schema 哈希拒绝绑定不匹配的构建。

## 安装

Falcon 通过 [JitPack](https://jitpack.io/#Coder-MarkBest/Falcon) 分发。

**1. 添加 JitPack 仓库**（`settings.gradle.kts`）：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

**2. 应用 KSP 并添加依赖**（使用方模块的 `build.gradle.kts`）：

```kotlin
plugins {
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" // 与你的 Kotlin 版本匹配
}

dependencies {
    implementation("com.github.Coder-MarkBest.Falcon:falcon-core:1.0.0")
    ksp("com.github.Coder-MarkBest.Falcon:falcon-ksp:1.0.0")
    // falcon-annotations 通过 falcon-core 传递依赖自动带入
}
```

> KSP 插件版本必须跟随项目的 Kotlin 版本（`<kotlin>-<ksp>`）。

## 快速开始

四步实现一个请求/响应服务。（完整集成 —— manifest 声明、跨 App 签名、事件/流/回调 ——
见 **[docs/USAGE.md](docs/USAGE.md)**。）

**1 · 定义契约** —— 一个两端都编译的共享模块：

```kotlin
interface IGreeter : IpcService {
    @IpcMethod suspend fun greet(name: String): String
}
```

**2 · 实现它**（服务端）：

```kotlin
class GreeterImpl : IGreeter {
    override suspend fun greet(name: String) = "Hello, $name!"
}
```

**3 · 每个进程初始化一次 Falcon**（`Application.onCreate`）：

```kotlin
Falcon.init(this) {
    generated(AppFalconGeneratedRegistry)   // KSP 生成，名字取自接口所在包
}.register(IGreeter::class, GreeterImpl())  // 仅服务端需要
```

**4 · 在另一个进程 / App 调用**：

```kotlin
lifecycleScope.launch {
    val greeter = Falcon.getInstance().getServiceSuspending<IGreeter>()
    println(greeter?.greet("Falcon"))   // → "Hello, Falcon!"
}
```

在 manifest 中注册 Provider（服务端还需注册 host service）—— 准确的声明见
[docs/USAGE.md](docs/USAGE.md)。

## IPC 模式

| 注解 | 形态 | 签名 |
|---|---|---|
| `@IpcMethod` | 请求 / 响应 | `suspend fun m(args): T` |
| `@IpcEvent` | 发布 / 订阅 | `fun m(): Flow<T>` |
| `@IpcStream` | 分块流 | `fun m(): Flow<ByteArray>` |
| `@IpcCallback` | 异步回调 | `fun m(args, reply: IpcReply<T>)` |

**支持的 wire 类型**：基本类型、`String`、`ByteArray`、`Parcelable`、枚举，以及它们的
`List` / `Map`。`data class` 载荷必须实现 `Parcelable`（手写，或用 `kotlin-parcelize` 的
`@Parcelize`）。不支持的类型在 **KSP 编译期** 报错，而非运行期。

## 架构

```
 调用方进程                                        服务端进程
 ───────────────                                  ───────────────
 Xxx_Proxy（生成）                                Xxx_Dispatcher（生成）
   打包参数 → 类型化 Bundle                          when(methodId) → 调用 impl
   带稳定 methodId 发送                              无反射
        │                                                 ▲
        ▼                                                 │
 BinderTransport ──Binder──▶ IpcHostService ──▶ MessageRouter
                                                  签名 → 权限 → 限流 → 拦截器链
                              IpcRegistryProvider（ContentProvider）── binder 直传发现
```

五模块依赖链：

```
falcon-annotations   纯 Kotlin 注解（无 Android 依赖）
      ↑
falcon-core          运行时：传输、安全、发现、监控
      ↑
falcon-ksp           KSP 处理器 → Proxy / Dispatcher / Registry
      ↑
falcon-benchmark · falcon-cross-server · falcon-cross-client   App 与示例
```

## 安全模型

- **签名校验** *(失败即拒)* —— 仅当调用方 UID 下每个包都由 `trustedSignatures`（SHA-256）∪
  本应用自身签名 中的证书签名时，才放行。未设置 ⇒ 仅同签名调用方可访问。每次 bind 和每次调用都
  通过 `Binder.getCallingUid()` 校验。
- **权限控制** —— DSL 访问规则，按调用方 **进程名** 索引（包名作为兜底）。
- **限流** —— 按 PID 的滑动窗口，在 router 中对每次调用强制执行。

```kotlin
Falcon.init(this) {
    generated(AppFalconGeneratedRegistry)
    peerPackages("com.vendor.other")          // 跨 App 对端（待发现）
    security {
        signatureVerification = true
        trustedSignatures = setOf("69d0…31e4") // 对端证书 SHA-256（小写、无冒号）
        rateLimitPerSecond = 200
        maxConcurrentCalls = 32
    }
    transport { binderPoolSize = 4; maxBinderPayloadSize = 256 * 1024 }
    timeout   { connectMs = 3_000; callMs = 5_000 }
}
```

## 性能

稳态跨 **App** 往返（两个独立签名 APK，真实 Binder 边界 + 逐调用签名校验），在 Android 14
模拟器上用内置 benchmark（cross-client 按钮 17）测得。数值与设备相关，仅在同一次运行内可比：

| 调用 | 平均 | p50 | p99 |
|---|---:|---:|---:|
| `ping(String)` | 0.80 ms | 0.28 ms | 5.4 ms |
| `add(Int, Int)` | 1.99 ms | 1.57 ms | 7.3 ms |
| `echoBytes(4 KB)` | 1.91 ms | 1.51 ms | 7.6 ms |
| `getVehicleData()` *(Parcelable)* | 2.09 ms | 1.44 ms | 5.9 ms |

**并发** —— 内置压力测试（按钮 18）发起 8 worker × 500 = **4000 个并发跨 App 调用**，
每个响应都校验正确性：**0 错误，约 4800 次/秒**。

`falcon-benchmark` 模块还会在一次运行中把 Falcon 与原生 AIDL、Messenger、ContentProvider、
Broadcast 横向对比（同 App 内）。任何 benchmark 都需在真机/模拟器运行 —— 构建本身不产出数值。

## 示例与文档

- **跨 App 示例** —— `falcon-cross-server` + `falcon-cross-client`：两个独立签名、互相信任证书的
  APK，演示全部 16 种 IPC 模式、延迟 benchmark 和并发压力测试，模拟多供应商车载系统。
- **集成指南** —— [docs/USAGE.md](docs/USAGE.md)。
- **设计文档** —— [docs/superpowers/specs](docs/superpowers/specs)。

## 构建与测试

```bash
./gradlew build                              # 全部
./gradlew :falcon-core:testDebugUnitTest     # core 单元测试
./gradlew :falcon-cross-server:testDebugUnitTest   # 走生成代码的 JVM 往返测试
./gradlew :falcon-cross-server:assembleDebug :falcon-cross-client:assembleDebug
```

跨 App 示例的 keystore 已入库（仅供 demo，密码 `falcondemo`），因此示例开箱即可构建运行；
其证书指纹已与示例 App 里的可信签名白名单一致。

## 路线图

- [x] 零反射 KSP 分发（请求/响应、事件、流、回调）
- [x] 跨 App 发现、互相签名信任、Schema 兼容校验
- [x] headless server 前台服务保活（Android 14 冻结器安全）
- [x] CI + JitPack 分发
- [ ] 发布到 Maven Central
- [ ] 可选的大载荷（SharedMemory）传输（flag 开关）

## 贡献

欢迎 Issue 和 PR。提 PR 前请先跑 `./gradlew build` 和单元测试套件；新的 wire 行为应在
`falcon-cross-server` 配一个往返测试（参考 `CrossServiceRoundTripTest`）。

## 许可证

[MIT](LICENSE) © Falcon IPC contributors
