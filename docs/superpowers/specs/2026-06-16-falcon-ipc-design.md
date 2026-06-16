# Falcon IPC Framework Design Spec

**Date**: 2026-06-16  
**Status**: Approved  
**Target**: Android 车载系统跨进程通信框架

---

## 1. 项目概述

Falcon 是一个面向车载 Android 系统的高效跨进程通信(IPC)框架，基于 Binder + SharedMemory 混合架构，提供 Retrofit 风格的注解式 API，支持多对多对等通信拓扑。

### 核心目标

- **高效**: Binder 内核级传输，SharedMemory 零拷贝大数据
- **稳定**: 自动进程发现、死亡检测、指数退避重连
- **易用**: 注解 + 接口定义，KSP 编译期代码生成，开发者无需接触 AIDL

### 技术约束

- 语言: Kotlin
- 最低 API: 24 (Android 7.0)
- 代码生成: KSP (Kotlin Symbol Processing)

---

## 2. 架构设计

### 2.1 分层架构

```
应用层 (App Layer)
  ├── Kotlin 接口 + @IpcMethod/@IpcEvent 注解
  └── IpcManager.init() / .register() / .getService<T>()

KSP 代码生成层 (Code Generation)
  ├── 自动生成 AIDL 接口文件
  ├── 自动生成 Stub (服务端绑定)
  └── 自动生成 Proxy (客户端代理)

传输层 (Transport)
  ├── BinderTransport (< 64KB 指令数据)
  ├── SharedMemoryTransport (≥ 64KB 大数据, 零拷贝)
  └── MessageRouter (自动路由切换)

核心层 (Core)
  ├── FalconManager (主入口)
  ├── PeerManager (节点发现 + 自动重连)
  └── ServiceRegistry (本地服务注册表)
```

### 2.2 通信拓扑

多对多对等网络：每个进程既是服务端也是客户端，无单点故障。

### 2.3 本地/远程透明调用

- 框架自动判断目标服务在当前进程还是远端进程
- 同进程: 直接方法调用，零 IPC 开销
- 跨进程: 走 Binder 传输
- 调用方代码完全一致，提供方通过 `android:process` 决定运行进程

---

## 3. 通信模式

### 3.1 请求-响应 (Request-Response)

```kotlin
interface INavService : IpcService {
    @IpcMethod
    suspend fun getCurrentLocation(): Location
}
```

### 3.2 异步回调 (Async Callback)

```kotlin
interface INavService : IpcService {
    @IpcCallback
    fun searchPoi(keyword: String, callback: IpcReply<List<Location>>)
}
```

### 3.3 发布-订阅 (Pub-Sub)

```kotlin
interface INavService : IpcService {
    @IpcEvent
    fun onLocationChanged(): Flow<Location>
}
```

### 3.4 数据流推送 (Streaming)

```kotlin
interface INavService : IpcService {
    @IpcStream
    fun getMapTileStream(regionId: String): Flow<ByteArray>
}
```

---

## 4. API 设计

### 4.1 初始化 (DSL 配置)

```kotlin
val falcon = Falcon.init(context) {
    transport {
        binderPoolSize = 4
        sharedMemoryThreshold = 64 * 1024
        maxSharedMemorySize = 32 * 1024 * 1024
    }
    reconnect {
        enabled = true
        strategy = ReconnectStrategy.EXPONENTIAL_BACKOFF
        initialDelayMs = 500
        maxDelayMs = 30_000
        maxRetries = -1
    }
    timeout {
        connectMs = 3_000
        callMs = 5_000
        streamChunkMs = 10_000
    }
    monitorLevel = if (BuildConfig.DEBUG) MonitorLevel.FULL else MonitorLevel.NONE
    security { /* 见 Section 7 */ }
    addInterceptor(LoggingInterceptor())
}
```

### 4.2 服务注册

```kotlin
// 显式指定接口类型，KSP 通过接口生成跨进程代码
falcon.register<INavService>(NavServiceImpl())

// 注意: 必须指定接口类型而非实现类，因为 KSP 基于接口生成 Stub/Proxy
```

### 4.3 服务获取

```kotlin
// 方式一: reified 泛型
val nav = falcon.getService<INavService>()

// 方式二: 属性委托 (推荐)
class ClusterActivity : AppCompatActivity() {
    private val navService: INavService by ipcService()
    private val navFallback: INavService by ipcService(fallback = MockNavService())
}
```

### 4.4 错误处理

```kotlin
sealed class IpcResult<out T> {
    data class Success<T>(val data: T) : IpcResult<T>()
    data class Failure(val code: Int, val message: String, val cause: Throwable?) : IpcResult<Nothing>()
    data object Timeout : IpcResult<Nothing>()
    data object ServiceUnavailable : IpcResult<Nothing>()
}

when (val result = falcon.callSafe<INavService, Location> { it.getCurrentLocation() }) {
    is IpcResult.Success -> updateMap(result.data)
    is IpcResult.Failure -> showError(result.message)
    is IpcResult.Timeout -> showError("响应超时")
    is IpcResult.ServiceUnavailable -> showError("服务未启动")
}
```

### 4.5 Flow 扩展

```kotlin
navService.onLocationChanged()
    .throttle(500)
    .distinctUntilChanged { a, b -> a.distanceTo(b) < 5f }
    .withConnectionState(falcon)
    .collect { event ->
        when (event) {
            is IpcEvent.Data -> updateMap(event.value)
            is IpcEvent.Disconnected -> showReconnecting()
            is IpcEvent.Reconnected -> showConnected()
        }
    }
```

### 4.6 协程生命周期绑定

```kotlin
// ipcScope 绑定到 Activity/Fragment lifecycle，销毁自动 cancel
ipcScope {
    val location = navService.getCurrentLocation()
    navService.onLocationChanged().collect { updateMap(it) }
}
```

### 4.7 连接状态监听

```kotlin
falcon.onConnectionStateChanged { state ->
    when (state) {
        IpcState.CONNECTED -> showStatus("已连接")
        IpcState.DISCONNECTED -> showStatus("断开")
        IpcState.RECONNECTING -> showStatus("重连中")
    }
}
```

---

## 5. 监控与统计

### 5.1 默认关闭，按需开启

```kotlin
enum class MonitorLevel {
    NONE,       // 完全关闭 (默认)，零开销
    BASIC,      // 仅调用次数 + 成功/失败
    DETAILED,   // BASIC + 耗时统计 + P99
    FULL        // DETAILED + tracing + 拦截器
}
```

### 5.2 运行时动态开关

```kotlin
falcon.setMonitorConfig {
    enableCallStats = true
    enableTracing = true
}
```

### 5.3 拦截器链

```kotlin
falcon.addInterceptor(object : IpcInterceptor {
    override suspend fun intercept(chain: IpcChain): IpcResult {
        val start = SystemClock.elapsedRealtime()
        val result = chain.proceed(chain.request)
        val cost = SystemClock.elapsedRealtime() - start
        monitor.report("ipc_call", mapOf(
            "service" to chain.request.service,
            "method" to chain.request.method,
            "latency" to cost,
            "success" to result.isSuccess
        ))
        return result
    }
})
```

### 5.4 统计数据查询

```kotlin
data class IpcCallStats(
    val serviceName: String,
    val methodName: String,
    val callCount: Long,
    val successCount: Long,
    val failCount: Long,
    val avgLatencyMs: Float,
    val p99LatencyMs: Float,
    val lastCallTime: Long,
    val transportType: String
)

falcon.getStats()       // List<IpcCallStats>
falcon.statsFlow()      // Flow<List<IpcCallStats>>
```

### 5.5 链路追踪 (可选)

```kotlin
monitor {
    enableTracing = true
    traceBackend = IpcTraceBackend.PERFETTO
}
// traceId 自动透传跨进程调用链
```

---

## 6. 注册发现机制

### 6.1 ContentProvider 注册表

- `IpcRegistryProvider`: multiprocess=true，每个进程都有实例
- SQLite 文件级锁保证并发安全
- 存储: service_key / process_name / pkg_name / register_time / pid
- 不依赖任何特定进程存活

### 6.2 注册流程

1. 进程启动 → IpcHostService.onCreate() → 启动本地 Binder 端点
2. 调用 `falcon.register()` → 写入 ContentProvider
3. ContentObserver 通知其他进程有新服务

### 6.3 发现流程

1. 查询 ContentProvider 获取远端服务列表
2. 本地服务 → 直接引用 (无 IPC)
3. 远端服务 → bindService() 绑定目标进程 IpcHostService
4. Binder linkToDeath() 监听远端进程存活

### 6.4 自动重连

- 指数退避: 500ms → 1s → 2s → ... → max 30s
- 进程死亡后 ContentObserver 触发重新扫描
- 连接成功后退避计时器重置

### 6.5 IpcHostService

每个进程暴露一个 Binder 端点:
- 接收远端调用请求
- 路由到本地 ServiceRegistry 找到实现
- 执行方法并返回结果
- 管理事件订阅

---

## 7. 安全方案

### 7.1 签名校验 (强制开启，不可关闭)

- IpcHostService.onBind() 校验调用方 UID + 签名哈希
- ContentProvider 每次操作校验签名
- 每次 invoke() 调用二次校验 (防 bind 后 UID 变化)

### 7.2 权限控制

- 注解级: `@IpcPermission(callerProcess = [":cluster", ":hud"])`
- DSL 配置级: `accessControl { service<T> { allowProcesses(...) } }`
- 默认策略: 同签名全放行

### 7.3 SharedMemory 传输安全

- 一次性 token (HMAC 签名)
- token 绑定调用方 PID
- TTL 过期 (默认 30s)
- singleUse 用完即销毁

### 7.4 速率限制

- 每进程每秒最多 1000 次调用 (可配置)
- 每进程最多 50 并发 (可配置)
- 超限返回 ErrorCode.RATE_LIMITED

### 7.5 不做的事

- **不做防重放**: 同签名 + Binder 内核级通道下实际威胁极低，由业务层保证幂等性

---

## 8. KSP 代码生成

### 8.1 输入

开发者定义的 Kotlin 接口 + 注解:
```kotlin
interface INavService : IpcService {
    @IpcMethod suspend fun getLocation(): Location
    @IpcEvent fun onLocationChanged(): Flow<Location>
}
```

### 8.2 输出 (自动生成)

1. `INavService.aidl` - AIDL 接口文件
2. `NavService_Stub` - 服务端绑定类 (接收 Binder 调用 → 路由到 impl)
3. `NavService_Proxy` - 客户端代理类 (方法调用 → 序列化 → Binder 传输)

### 8.3 注解定义

- `@IpcMethod` - 请求-响应方法
- `@IpcCallback` - 异步回调方法
- `@IpcEvent` - 发布-订阅事件 (返回 Flow)
- `@IpcStream` - 大数据流 (SharedMemory 传输)
- `@IpcPermission` - 访问控制

---

## 9. 模块结构

```
falcon/
├── falcon-core/          # 核心框架 (运行时)
│   ├── core/             # FalconManager, PeerManager, ServiceRegistry, MessageRouter
│   ├── transport/        # BinderTransport, SharedMemoryTransport
│   ├── protocol/         # IpcMessage, IpcEnvelope, IpcResult
│   ├── service/          # IpcService, IpcServiceCallback
│   ├── security/         # SignatureGuard, PermissionChecker, RateLimiter, SecureSharedMemory
│   └── util/             # FalconLogger, ProcessUtils
├── falcon-ksp/           # KSP 注解处理器
│   ├── annotations/      # @IpcMethod, @IpcEvent, @IpcCallback, @IpcStream, @IpcPermission
│   └── processor/        # KSP processor: 生成 AIDL + Stub + Proxy
├── falcon-runtime/       # 运行时辅助 (属性委托, Flow 扩展, ipcScope)
└── falcon-demo/          # 车载场景 Demo (多进程)
    ├── MainProcess       # 聚合各模块数据
    ├── NavigationProcess # 导航服务
    ├── MediaProcess      # 媒体播放
    └── SensorProcess     # 传感器数据流
```

---

## 10. 性能指标

| 场景 | 目标 | 传输方式 |
|------|------|---------|
| 小数据指令 (< 64KB) | < 1ms 延迟 | Binder |
| 大数据传输 (≥ 64KB) | 接近零拷贝 | SharedMemory |
| 进程发现 | < 100ms | ContentProvider |
| 自动重连 | 500ms ~ 30s | 指数退避 |
| 监控开销 (NONE) | 0 | 不挂拦截器 |
| 监控开销 (FULL) | ~20μs/call | 拦截器链 |
