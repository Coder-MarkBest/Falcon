# Falcon IPC 集成与使用指南

本文档面向**在自己的工程里集成 Falcon** 的开发者，覆盖从依赖配置到 5 种调用模式的完整用法。
配套可运行示例见 `:falcon-cross-server` + `:falcon-cross-client` 模块（两个独立签名 APK，模拟多供应商车载系统跨 App 通信）。

> 想先看整体设计与架构，读 [README.md](../README.md)。
> 想看两种服务发现机制的对比，读各自源码注释（`IpcRegistryProvider.call` / `PeerManager.connectPeer`）。

---

## 目录

1. [前置要求](#1-前置要求)
2. [Gradle 配置](#2-gradle-配置)
3. [定义服务接口](#3-定义服务接口)
4. [AndroidManifest 声明](#4-androidmanifest-声明)
5. [初始化（每个进程）](#5-初始化每个进程)
6. [五种调用模式](#6-五种调用模式)
   - [6.1 请求/响应 `@IpcMethod`](#61-请求响应-ipcmethod)
   - [6.2 类型安全错误处理 `callSafe`](#62-类型安全错误处理-callsafe)
   - [6.3 发布/订阅事件 `@IpcEvent`](#63-发布订阅事件-ipcevent)
   - [6.4 大数据流 `@IpcStream`](#64-大数据流-ipcstream)
   - [6.5 异步回调 `@IpcCallback`](#65-异步回调-ipccallback)
7. [安全配置](#7-安全配置)
8. [配置项参考](#8-配置项参考)
9. [类型支持与限制](#9-类型支持与限制)
10. [FAQ / 排错](#10-faq--排错)

---

## 1. 前置要求

| 项 | 要求 |
|----|------|
| minSdk | ≥ 24 |
| Java | 17 |
| Kotlin | 1.9.x（与 KSP 版本对齐） |
| KSP | `1.9.22-1.0.17`（与 Kotlin 版本一致） |
| 协程 | `kotlinx-coroutines-android` |

所有跨进程数据类型必须实现 `Parcelable`。

---

## 2. Gradle 配置

### 根 `build.gradle.kts`（plugins 声明）

```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
```

### 模块 `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")          // ① KSP 代码生成
}

android {
    compileSdk = 34
    defaultConfig { minSdk = 24 }

    buildFeatures { aidl = true }           // ② IIpcHost 是 AIDL 接口

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":falcon-core")) // 或发布后的 maven 坐标
    ksp(project(":falcon-ksp"))             // ③ 注册 KSP 处理器
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

三个要点：① 应用 KSP 插件，② 打开 `aidl`，③ 用 `ksp(...)` 引入处理器（不是 `implementation`）。

> **自定义生成的 Registry 名字**：默认取接口所在包名的最后一段首字母大写，例如 `com.falcon.demo` → `DemoFalconGeneratedRegistry`。要固定名字：
> ```kotlin
> ksp { arg("falcon.moduleId", "MyApp") }   // → MyAppFalconGeneratedRegistry
> ```

---

## 3. 定义服务接口

接口必须 `: IpcService`，方法用注解标记。KSP 自动发现并生成 `Xxx_Proxy`（客户端）和 `Ixxx_Dispatcher`（服务端）。

```kotlin
interface IDemoService : IpcService {
    @IpcMethod   suspend fun ping(message: String): String
    @IpcMethod   suspend fun add(a: Int, b: Int): Int
    @IpcMethod   suspend fun getUser(id: Int): DemoUser   // Parcelable 返回
    @IpcEvent    fun clock(): Flow<Long>                  // 事件流
    @IpcStream   fun download(): Flow<ByteArray>          // 字节流
    @IpcCallback fun loadAsync(taskId: Int, reply: IpcReply<String>)
}
```

Parcelable 模型（推荐用 `@Parcelize`）：

```kotlin
@Parcelize
data class DemoUser(val id: Int, val name: String, val vip: Boolean) : Parcelable
```

完整示例见 [`ICrossService.kt`](../falcon-cross-server/src/main/java/com/falcon/cross/shared/ICrossService.kt)。

---

## 4. AndroidManifest 声明

Falcon 自带两个组件，必须声明在**服务端进程**：

```xml
<!-- Binder 主机：处理所有 IPC 调用 -->
<service
    android:name="com.falcon.ipc.core.IpcHostService"
    android:exported="false"
    android:process=":server">
    <intent-filter>
        <action android:name="com.falcon.ipc.HOST_SERVICE" />
    </intent-filter>
</service>

<!-- 服务注册/发现（ContentProvider Binder 直传）。
     authority 必须等于 "${applicationId}.falcon.registry" -->
<provider
    android:name="com.falcon.ipc.core.IpcRegistryProvider"
    android:authorities="${applicationId}.falcon.registry"
    android:exported="false"
    android:process=":server" />
```

两个组件必须在**同一进程**（这里都是 `:server`），因为 Provider 要把 HostService 的 Binder 直接交给调用方。

### 跨 App 的 `<queries>`（Android 11+）

调用方要发现别的 App 的服务，必须在 manifest 里声明 `<queries>`（否则系统不可见）。两种方式：

**手写**：
```xml
<queries>
    <package android:name="com.oem.nav" />
    <package android:name="com.oem.media" />
</queries>
```

**或用 Gradle 插件自动生成**（推荐，与运行时 `peerPackages` 单点配置）：
```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.falcon.ipc.falcon-gradle")   // Falcon Gradle 插件
}
falcon { peerPackages("com.oem.nav", "com.oem.media") }
```
插件会把这些包注入合并后的 manifest 的 `<queries>`，无需手写。注意：这里的列表要与 `Falcon.init { peerPackages(...) }` 保持一致。

> 插件通过 AGP 的 `MERGED_MANIFEST` 变换实现；核心注入逻辑（`FalconManifestInjector`）有独立单测覆盖。端到端（应用到真实 App 看合并 manifest）需先把插件发布到 mavenLocal 或用 `includeBuild` 引入。

---

## 5. 初始化（每个进程）

**每个参与 IPC 的进程都要调用 `Falcon.init`。** 服务端额外调用 `register`。

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = ProcessUtils.getCurrentProcessName(this)

        if (process.endsWith(":server")) {
            // 服务端：init + register
            val falcon = Falcon.init(this) {
                generated(DemoFalconGeneratedRegistry)   // KSP 生成的注册表
            }
            falcon.register(IDemoService::class, DemoServiceImpl())
        } else {
            // 客户端：只 init
            Falcon.init(this) {
                generated(DemoFalconGeneratedRegistry)
            }
        }
    }
}
```

> 客户端首次 `getService` 时，框架会自动拉起 `:server` 进程（`startService` → 创建 `IpcHostService`），
> `:server` 的 `DemoApp.onCreate` 完成 `register`，随后通过 ContentProvider 的 Binder 直传建立连接。
> 因此发现是**异步**的——首帧可能拿不到，需要重试（见下方 UI 示例）。

---

## 6. 五种调用模式

客户端用 `Falcon.getService<T>()` 拿到代理后，像调用本地对象一样使用。
以下片段摘自 [`CrossClientActivity.kt`](../falcon-cross-client/src/main/java/com/falcon/cross/client/CrossClientActivity.kt)。

### 6.1 请求/响应 `@IpcMethod`

```kotlin
val svc = Falcon.getInstance().getService<IDemoService>() ?: return
lifecycleScope.launch(Dispatchers.IO) {
    val reply = svc.ping("hello")        // 阻塞式 Binder 调用，在协程里执行
    val sum   = svc.add(2, 3)            // 多参数
    val user  = svc.getUser(42)          // Parcelable 返回
}
```

> `suspend` 推荐但非必需。代理内部仍是同步 Binder 调用；`suspend` 只是让你无需额外包装即可在协程中调用。
> 远端抛错时，代理抛 `IpcException(errorCode, message)`。

### 6.2 类型安全错误处理 `callSafe`

不想用 try/catch 处理 IPC 失败时，用 `callSafe`——它把所有失败收敛为 `IpcResult`：

```kotlin
when (val r = Falcon.getInstance().callSafe<IDemoService, String> { it.ping("safe") }) {
    is IpcResult.Success            -> show(r.data)
    is IpcResult.Timeout            -> show("超时")
    is IpcResult.Failure            -> show("失败[${r.code}]: ${r.message}")
    is IpcResult.ServiceUnavailable -> show("服务不可用")
}
```

非挂起场景用 `callSafeOrNull { ... }`，失败一律返回 `null`（会 WARN 日志）。

### 6.3 发布/订阅事件 `@IpcEvent`

返回 `Flow<T>`，跨进程收集。服务端的 Flow 被**惰性、引用计数**地采集——第一个订阅者触发采集，最后一个取消时停止。

```kotlin
val job = lifecycleScope.launch(Dispatchers.IO) {
    svc.clock()
        .catch { /* 处理传输错误 */ }
        .collect { tick -> println("tick = $tick") }
}
// 取消订阅：
job.cancel()
```

> 事件经线程池分发，**不保证跨事件顺序**。需要有序就在订阅侧用单线程收集。

> 背压策略可配：`Falcon.init { event { bufferCapacity = 256; onOverflow = BufferOverflow.SUSPEND } }`。
> 默认 `DROP_OLDEST`（丢最旧，适合遥测）；`SUSPEND` 提供有界内存背压。

> ⚠️ **事件/流是“热订阅”**：服务端 Flow 的**完成（onComplete）不会跨进程传播**到客户端。
> 客户端的订阅只在你**取消收集**（`job.cancel()` 或 `take(n)`）时才结束。
> 因此**不要**对跨进程流用无界的 `.toList()` / `.collect{}` 去等它“结束”——会永久挂起。
> 始终用 `take(n)`、`takeWhile{}` 或主动 `cancel` 来界定收集范围。

### 6.4 大数据流 `@IpcStream`

与 `@IpcEvent` 机制相同，但元素类型固定为 `ByteArray`，适合分块大数据。每个分块是一次独立 Binder 事务，需保证单块不超过 `maxBinderPayloadSize`。

```kotlin
var total = 0
svc.download()
    .take(5)                 // 流是热订阅，用 take/cancel 界定收集，勿无界等待完成
    .collect { chunk -> total += chunk.size }
```

### 6.5 异步回调 `@IpcCallback`

发了就返回（fire-and-forget），结果稍后经 `IpcReply<T>` 回来。适合服务端要做异步工作再回复的场景。

```kotlin
svc.loadAsync(7, object : IpcReply<String> {
    override fun onResult(data: String) { show(data) }
    override fun onError(code: Int, message: String) { show("err[$code]: $message") }
})
```

服务端实现：

```kotlin
override fun loadAsync(taskId: Int, reply: IpcReply<String>) {
    if (taskId < 0) reply.onError(1, "taskId must be >= 0")
    else            reply.onResult("result for task #$taskId")
}
```

---

## 7. 安全配置

Falcon 默认**fail-closed**：未配置可信签名时，只接受与本应用同签名的调用方。

```kotlin
Falcon.init(context) {
    security {
        // ① 签名校验（默认开）。关闭仅用于调试。
        signatureVerification = true
        // 可信签名白名单（SHA-256 hex）。空 = 仅同签名调用方。
        trustedSignatures = setOf("AB:CD:...")

        // ② 访问控制：按调用方进程名（包名兜底）配 allow/deny
        accessRules = mapOf(
            "com.example.IService" to AccessRule(
                allowList = setOf("com.trusted.client"),
                denyList = emptySet()
            )
        )

        // ③ 限流（每 PID 滑动窗口）。0 = 不限制。
        rateLimitPerSecond = 1000
        maxConcurrentCalls = 50
    }
}
```

三道防线在每次调用时依次执行：签名校验（`SignatureGuard`）→ 限流（`RateLimiter`）→ 权限（`PermissionChecker`）。

> 访问控制通过 DSL 配置，**没有** `@IpcPermission` 注解（旧注解已移除）。

---

## 8. 配置项参考

```kotlin
Falcon.init(context) {
    transport {
        binderPoolSize = 4                  // 服务端线程池核心数
        maxBinderPayloadSize = 256 * 1024   // 单次 Binder 负载上限
        invokeTimeoutMs = 0                 // 0=关（默认，热路径零开销）；>0 启用看门狗，
                                            // 对端无响应超时即返回 TRANSPORT_ERROR，不占用调用线程
    }
    reconnect {
        enabled = true
        initialDelayMs = 500                // 指数退避起始
        maxDelayMs = 30_000                 // 退避上限
        maxRetries = -1                     // -1 = 无限重试
    }
    timeout {
        connectMs = 3_000                   // 发现/连接超时
        callMs = 5_000                      // 单次调用超时（callSafe 默认值）
    }
    monitorLevel = MonitorLevel.NONE        // 监控默认关，零开销
    generated(MyAppFalconGeneratedRegistry) // 必填
}
```

---

## 9. 类型支持与限制

| 类别 | 支持的类型 |
|------|-----------|
| 基本类型 | `Int` `Long` `Float` `Double` `Boolean` |
| 字符串/字节 | `String` `ByteArray` |
| 复合 | 任意 `Parcelable`（data class 需 `@Parcelize`） |
| 枚举 | 任意 Kotlin enum |
| 集合 | `List<T>`、`Map<K, V>`，元素类型为上述任意受支持类型（可嵌套，如 `Map<String, List<DemoUser>>`） |
| 返回 | 上述类型 + `Unit`；可空（`T?`）均支持 |

- **不支持的类型在 KSP 编译期直接报错**，不会留到运行时。
- 单次 Binder 事务负载控制在 ~1MB 以内（无大负载场景）。
- `methodId` = 方法签名的 FNV-1a 哈希。**重命名方法/改参数类型是破坏性变更**，客户端与服务端必须同步更新；重载是安全的（每个签名哈希不同）。

> **方法匹配（多 App 独立升级）**：客户端调用服务端不存在的方法（增删/改签名 → methodId 不同）时，服务端返回 `METHOD_NOT_FOUND`（1002），三种调用类型一致：`@IpcMethod` 抛 `IpcException`、`@IpcCallback` 走 `onError`、`@IpcEvent`/`@IpcStream` 让 Flow 以异常结束。
> 演进约定：**不要原地改方法签名/返回类型，新增方法**——老调用方会干净地拿到 `METHOD_NOT_FOUND`。

> **Schema 兼容校验（防 Parcelable 静默错乱）**：methodId 不含返回类型,也看不到 `Parcelable` 字段布局——所以"同名同参、改了返回类型或改了 `DemoUser` 字段"这种 wire 不兼容的变更,methodId 检测不到。框架为此给每个接口生成一个 **schema 哈希**(覆盖返回类型 + 引用到的 `Parcelable` 字段布局,递归),在**发现阶段**(`__check_service__` 探测)交换一次比对:
> - client/server schema 不一致 → 发现期直接拒绝建链(`getService` 返回 null + ERROR 日志),而非运行时静默解出脏数据。
> - **零热路径开销**:schema 是编译期常量,只在发现时比一个 int,且发现结果被代理缓存——稳态调用完全不受影响。
> - 老框架(无 schema)与新框架混用时,任一方 schema 为 0 → 跳过校验,向后兼容。
>
> 诚实边界:字段布局按"声明顺序"取(对 `@Parcelize` 即 wire 顺序)。手写 `Parcelable` 且 `writeToParcel` 顺序与属性声明顺序不一致时,仅"增删/改类型字段"能被检出,"只调换 writeToParcel 顺序"这种少见情况检不出——这是尽力而为的安全网,不是证明。

---

## 10. FAQ / 排错

**Q: 客户端 `getService` 一直返回 null？**
发现是异步的。确认：① 两进程都调了 `Falcon.init` 且传了同一个 `generated(...)`；② Manifest 里 `IpcHostService` 和 `IpcRegistryProvider` 都声明且在同一进程；③ ContentProvider 的 authority 是 `${applicationId}.falcon.registry`。首次最多重试 ~10s（拉起 `:server` 进程需要时间）。

**Q: 编译报 `Unresolved reference: Xxx_Proxy`？**
KSP 增量缓存不一致。执行对应模块的 `clean` 后重编：`./gradlew :your-module:clean :your-module:assembleDebug`。

**Q: 调用抛 `IpcException [1005] Rate limit exceeded`？**
触发限流。压测时设 `rateLimitPerSecond = 0` 和 `maxConcurrentCalls = 0`（0 = 不限制）。

**Q: 绑定被拒（`onNullBinding` / `UNAUTHORIZED`）？**
签名校验失败。调用方与服务端不同签名时，需把调用方签名的 SHA-256 加入 `trustedSignatures`，或在调试期临时 `signatureVerification = false`。

**Q: 远端进程崩溃后会自动重连吗？**
会。`linkToDeath` 触发后按 `reconnect` 配置做指数退避重连，每个 peer 独立退避状态。

**Q: 事件/流收不到数据？**
确认服务端对应方法返回的是**冷流**（如 `flow { ... }`）。Falcon 在第一个订阅者到来时才开始采集；返回 `flowOf()`（空流）会立即结束。

---

## 运行示例 App

跨 App 示例由两个独立签名的 APK 组成，模拟多供应商车载系统：

```bash
# 构建两个 APK（各自使用独立 keystore 签名）
./gradlew :falcon-cross-server:assembleDebug
./gradlew :falcon-cross-client:assembleDebug

# 安装到设备
adb install -r falcon-cross-server/build/outputs/apk/debug/falcon-cross-server-debug.apk
adb install -r falcon-cross-client/build/outputs/apk/debug/falcon-cross-client-debug.apk
```

打开 CrossClient App，点击每个按钮观察跨 App IPC 的日志输出。

> 两个 APK 使用不同的 keystore 签名（`cross-server.keystore` / `cross-client.keystore`），
> 通过 `trustedSignatures` 互信——模拟真实多供应商证书白名单场景。
> 签名校验默认开启（`signatureVerification = true`）。
