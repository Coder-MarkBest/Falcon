# Falcon IPC Framework

Android 车载系统跨进程通信 (IPC) 框架，基于 **Binder + KSP 编译期代码生成**，零反射，类型安全。

> 📘 **集成与使用**：见 [docs/USAGE.md](docs/USAGE.md)。可运行示例见 `falcon-demo` 模块（双进程 App，每个按钮演示一种用法）。

## 设计目标

- **编译期安全** — KSP 生成 Proxy/Dispatcher，方法签名变更在编译时发现
- **零反射** — 不用 `Parcel.marshall()`，不用动态代理，全部是类型化 `Bundle` + 稳定的 `methodId`
- **精简** — 只保留 Binder 传输路径，不引入未使用的抽象层
- **生产就绪** — 签名校验、权限控制、限流、超时、重连、诊断

## 架构

```
falcon-annotations         纯 Kotlin 注解定义 (无 Android 依赖)
       ↑
falcon-core                Android Library — 运行时框架
       ↑
falcon-ksp                 KSP 处理器 — 编译期生成 Stub/Proxy
       ↑
falcon-benchmark           Android App — 对比 Falcon vs AIDL/Messenger/ContentProvider/Broadcast
```

### 数据流

```
App 进程                                      Remote 进程
━━━━━━━━━                                    ━━━━━━━━━
Proxy (KSP 生成)                              Dispatcher (KSP 生成)
  │  pack args → typed Bundle                   │  unpack typed Bundle
  │  send with stable methodId                  │  dispatch by methodId (when switch)
  ▼                                             ▼
BinderTransport ──Binder──▶ IpcHostService ──▶ MessageRouter
                             │                    │ signature check
                             │                    │ permission check
                             │                    │ rate limit
                             │                    │ interceptor chain
                             ▼                    ▼
                           IIpcHost.Stub()      Service impl
```

## 快速开始

### 1. 定义服务接口

```kotlin
import com.falcon.ipc.annotations.IpcMethod
import com.falcon.ipc.annotations.IpcEvent
import com.falcon.ipc.annotations.IpcCallback
import com.falcon.ipc.service.IpcService
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.flow.Flow

interface INavService : IpcService {
    @IpcMethod
    suspend fun getCurrentLocation(): Location

    @IpcMethod
    fun computeRoute(from: LatLng, to: LatLng): Route

    @IpcEvent
    fun locationUpdates(): Flow<Location>

    @IpcCallback
    fun fetch(id: Int, reply: IpcReply<String>)
}
```

### 2. 实现服务

```kotlin
class NavServiceImpl : INavService {
    override suspend fun getCurrentLocation(): Location = gps.lastKnown()

    override fun computeRoute(from: LatLng, to: LatLng): Route = router.calculate(from, to)

    override fun locationUpdates(): Flow<Location> = gps.updates

    override fun fetch(id: Int, reply: IpcReply<String>) {
        reply.onResult("result-$id")
    }
}
```

### 3. 初始化（Remote 进程）

```kotlin
// Application.onCreate() in the remote process
Falcon.init(this) {
    generated(MyModuleFalconGeneratedRegistry)  // KSP 生成的注册表
    security {
        trustedSignatures = setOf("sha256:abc...")  // 可选：信任的调用者
    }
}.register(INavService::class, NavServiceImpl())
```

### 4. 调用服务（Client 进程）

```kotlin
// Main process — 自动发现并连接 remote 服务
val nav: INavService? = Falcon.getInstance().getService(INavService::class)

// Type-safe call with error handling
when (val result = falcon.callSafe<INavService, Location> { it.getCurrentLocation() }) {
    is IpcResult.Success    -> updateMap(result.data)
    is IpcResult.Failure    -> showError(result.message)
    is IpcResult.Timeout    -> showError("Timed out")
    is IpcResult.ServiceUnavailable -> retryLater()
}

// Event subscription (Flow)
nav?.locationUpdates()?.collect { location -> updateMap(location) }
```

## 注解说明

| 注解 | 方法签名 | 用途 |
|------|---------|------|
| `@IpcMethod` | `suspend fun m(): T` | 请求-响应 |
| `@IpcEvent` | `fun m(): Flow<T>` | 发布-订阅事件 |
| `@IpcStream` | `fun m(): Flow<ByteArray>` | 大数据流 |
| `@IpcCallback` | `fun m(args, IpcReply<T>)` | 异步回调 |

> 访问控制通过 `Falcon.init { security { accessRules = ... } }` 的 DSL 配置（按调用方进程/包名），见[安全模型](#安全模型)。

## 配置

```kotlin
Falcon.init(context) {
    // 传输层配置
    transport {
        binderPoolSize = 4
        maxBinderPayloadSize = 256 * 1024  // 256KB
    }

    // 重连配置
    reconnect {
        enabled = true
        initialDelayMs = 500
        maxDelayMs = 30_000
        maxRetries = -1  // -1 = 无限
    }

    // 超时配置
    timeout {
        connectMs = 3_000
        callMs = 5_000
    }

    // 安全配置
    security {
        signatureVerification = true
        trustedSignatures = setOf("sha256:abc123...")

        // 权限规则 — 支持进程级粒度
        accessRules = mapOf(
            "com.example.INavService" to AccessRule(
                allowList = setOf("com.example:cluster", "com.example:hud"),
                denyList = setOf("com.example:media")
            )
        )

        // 限流 — 设为 0 表示不限制
        rateLimitPerSecond = 0
        maxConcurrentCalls = 0
    }

    // 监控
    monitorLevel = MonitorLevel.BASIC

    // 自定义拦截器
    addInterceptor(LoggingInterceptor())

    // KSP 生成的注册表
    generated(MyModuleFalconGeneratedRegistry)
}
```

## 安全模型

### 签名校验 (SignatureGuard)

- **onBind + 每调用双重校验**：`onBind` 时做早期拒绝，`invoke()` 内做权威校验
- **信任白名单**：配置 `trustedSignatures`（SHA-256 证书哈希），只有签名匹配的调用者才能访问
- **Fail-closed**：未配置时仅允许同签名调用（同 APK 内进程间通信）
- **缓存自动失效**：监听 `ACTION_PACKAGE_REPLACED/REMOVED` 广播，应用更新后自动清除签名缓存

### 权限控制 (PermissionChecker)

- **进程级粒度**：支持区分同 UID 下的不同进程（如 `:cluster` vs `:hud`）
- **AllowList / DenyList**：DenyList 优先于 AllowList
- **默认放行**：未配置规则的 service 默认允许所有调用者

### 限流 (RateLimiter)

- **滑动窗口**：每秒调用次数限制（设为 0 则禁用）
- **并发限制**：同一 PID 同时进行的调用数上限
- **自动清理**：每 60s 清理闲置 PID 的条目

## 监控与诊断

```kotlin
// 启用诊断（包含延迟、成功率、调用追踪）
Falcon.getInstance().diagnostics.enable(dumpDirectory = cacheDir)

// 获取统计
val stats = Falcon.getInstance().monitor.getStats()
stats.forEach { s ->
    println("${s.serviceName}#${s.methodName}: " +
            "${s.callCount} calls, ${s.avgLatencyMs.roundToInt()}ms avg")
}

// 实时统计 Flow
Falcon.getInstance().monitor.statsFlow().collect { updateDashboard(it) }
```

## 模块结构

```
falcon-core/src/main/java/com/falcon/ipc/
├── Falcon.kt                  # 入口：init / getInstance
├── FalconConfig.kt            # DSL 配置
├── core/
│   ├── FalconManager.kt       # 中央管理器
│   ├── IpcHostService.kt      # Binder Service 宿主
│   ├── PeerManager.kt         # 服务发现 + 重连
│   ├── MessageRouter.kt       # 分发 + 拦截器链
│   ├── ServiceRegistry.kt     # 服务注册表
│   ├── EventCollector.kt      # Flow 订阅引用计数
│   ├── EventProxy.kt          # 事件代理 (跨进程 Flow)
│   ├── IpcRegistryProvider.kt # ContentProvider 注册中心
│   ├── DiagnosticsManager.kt  # 异步诊断记录
│   ├── IpcThreadPool.kt       # IPC 线程池
│   └── TimeoutController.kt   # 超时控制
├── transport/
│   ├── IpcTransport.kt        # 传输接口
│   └── BinderTransport.kt     # Binder 实现
├── protocol/
│   ├── IpcEnvelope.kt         # 消息信封 (Parcelable)
│   ├── IpcResult.kt           # 调用结果
│   ├── IpcException.kt        # 结构化错误
│   ├── ErrorCode.kt           # 错误码
│   └── BundleCodec.kt         # 类型化 Bundle 编解码
├── security/
│   ├── SignatureGuard.kt      # 签名校验
│   ├── PermissionChecker.kt   # 权限检查
│   └── RateLimiter.kt         # 限流
├── monitor/
│   ├── MonitorFacade.kt       # 监控门面
│   └── IpcInterceptor.kt      # 拦截器接口
├── runtime/
│   ├── IpcDispatcher.kt       # 分发器接口 (KSP 生成)
│   ├── CallSafe.kt            # 安全调用包装
│   └── FlowExtensions.kt      # Flow 扩展
└── util/
    ├── FalconLogger.kt        # 日志
    ├── CallerResolver.kt      # UID/PID → 身份解析
    └── ProcessUtils.kt        # 进程工具
```

## 编译

```bash
# 构建全部模块
./gradlew build

# 仅构建 falcon-core
./gradlew :falcon-core:assembleRelease

# 运行测试
./gradlew :falcon-core:test

# 运行单个测试
./gradlew :falcon-core:test --tests "com.falcon.ipc.security.RateLimiterTest"

# 构建 benchmark APK
./gradlew :falcon-benchmark:assembleDebug
```

### 环境要求

- JDK 17 (需要 jmods — Android Studio 内置 JBR 不兼容，使用标准 JDK)
- Android SDK 34+
- Gradle 8.10

## Benchmark

`falcon-benchmark` 在同一个 APK 内对比 5 种跨进程通信机制的延迟：

| 机制 | 实现 | 说明 |
|------|------|------|
| Raw AIDL | `IBenchmarkService.Stub` | Binder 原生接口 |
| Messenger | `Handler` + `Message.replyTo` | 基于 Handler 的消息传递 |
| ContentProvider | `insert` → `query` 往返 | 基于 Binder + SQLite |
| **Falcon** | KSP Proxy → `BinderTransport` | 本框架稳态调用路径 |
| Broadcast | AMS 实际往返 | 非请求/响应模式，仅供参考 |

在设备上运行 APK 后，结果以表格形式显示 avg/p50/p99 延迟（毫秒），按 payload 大小分组（Small/Medium/Large）。

> **注意**：benchmark 结果仅在同一设备同一次运行内可比较。不同设备的 Binder 性能差异显著。

## 类型支持

| Kotlin 类型 | Bundle 编码 | 备注 |
|------------|------------|------|
| `Int`, `Long`, `Float`, `Double`, `Boolean` | `putInt` / `putLong` ... | 原生支持 |
| `String` | `putString` | 原生支持 |
| `ByteArray` | `putByteArray` | 最大 ~256KB (Binder 事务限制) |
| `Parcelable` | `putParcelable` | 自定义数据类需实现 `Parcelable` |
| `enum` | `putString(name)` | 通过枚举名传输 |
| `Unit` (返回类型) | 空 Bundle | 无返回值 |
| `Flow<T>` | 事件订阅/取消 | `@IpcEvent` / `@IpcStream` |
| `IpcReply<T>` | 回调 Binder | `@IpcCallback` |

**不支持**：`List<T>`（除非整体实现 Parcelable）、`Map<K,V>`、`Set<T>` 等。KSP 会在编译时报错。

## methodId 稳定性

每个 `@IpcMethod` / `@IpcEvent` / `@IpcCallback` 方法根据 **方法名 + 参数类型全限定名** 计算 FNV-1a 哈希作为 `methodId`。`IpcReply` 参数不计入哈希（因为 Proxy 和 Dispatcher 看到的签名不同）。

- ✅ 重载同名方法：不同参数类型 → 不同 `methodId`
- ✅ 调换参数顺序：不同 `methodId`
- ⚠️ 重命名方法：breaking change（服务端和客户端需同时更新）
- ⚠️ 改变参数类型：breaking change（`methodId` 会变）

## License

Internal — Falcon IPC Framework
