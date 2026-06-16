# Falcon IPC Phase 2 Design Spec

**Date**: 2026-06-16
**Status**: Approved
**Scope**: 19 features across 5 phases

---

## Phase 1: End-to-End Core (Features 4, 5)

### F4: KSP 序列化/反序列化

**目标**: Stub/Proxy 生成代码能真正序列化方法参数、反序列化返回值。

**方案**:
- 使用 Kotlin Serialization (kotlinx.serialization) 作为序列化框架
- KSP 生成器为每个方法参数生成 `encodeToByteArray()` 调用
- 返回类型生成 `decodeFromByteArray()` 调用
- 支持类型: 基本类型、String、ByteArray、Parcelable、List、Map

**序列化协议**:
```
[4 bytes: argCount]
[arg1 type: 1 byte][arg1 length: 4 bytes][arg1 data]
[arg2 type: 1 byte][arg2 length: 4 bytes][arg2 data]
...
```

类型编码: 0=Int, 1=Long, 2=Float, 3=Double, 4=Boolean, 5=String, 6=ByteArray, 7=Parcelable

### F5: Pub-Sub 事件完整链路

**目标**: `@IpcEvent` 方法返回 Flow，本地事件自动推送到远端订阅者。

**方案**:
- 本地服务通过 `EventBus` emit 事件
- EventBus 序列化事件数据，通过 `IIpcEventCallback.onEvent()` 推送给所有远端订阅者
- 远端 Proxy 的 `@IpcEvent` 方法返回 `callbackFlow {}` 接收回调
- 订阅生命周期跟随 Flow collect 的 coroutine scope

---

## Phase 2: Developer Experience (Features 1, 2, 3, 6)

### F1: Flow 扩展操作符

- `throttle(periodMs)` — 节流，单位时间内只发最新值
- `withConnectionState(falcon)` — 绑定连接状态，断开时发 `IpcEvent.Disconnected`
- `retryOnReconnect(falcon)` — 断连重连后自动重新订阅

### F2: `by ipcService()` 属性委托

```kotlin
inline fun <reified T : IpcService> ipcService(fallback: T? = null): ReadOnlyProperty<Any, T>
```
- 首次访问时调用 `falcon.getService<T>()`
- fallback 非 null 时服务不可用返回 fallback
- 缓存代理实例

### F3: `ipcScope` 生命周期绑定

```kotlin
fun LifecycleOwner.ipcScope(block: suspend CoroutineScope.() -> Unit)
```
- 绑定到 `lifecycleScope`，Activity/Fragment 销毁自动 cancel
- 内部自动处理 `IpcResult.ServiceUnavailable`

### F6: `callSafe` 安全调用

```kotlin
suspend inline fun <reified S : IpcService, T> FalconManager.callSafe(
    crossinline block: suspend (S) -> T
): IpcResult<T>
```
- 捕获超时 → `IpcResult.Timeout`
- 捕获服务不存在 → `IpcResult.ServiceUnavailable`
- 捕获其他异常 → `IpcResult.Failure`

---

## Phase 3: Production Reliability (Features 7, 8, 9)

### F7: 超时控制 + 取消

- `TimeoutConfig.callMs` 应用到每次 IPC 调用
- 使用 `withTimeoutOrNull` 包装远端调用
- 超时后自动取消远端执行（通过 requestId 匹配取消）

### F8: 熔断器

```kotlin
class CircuitBreaker(
    val failureThreshold: Int = 5,
    val openDurationMs: Long = 30_000,
    val halfOpenMaxCalls: Int = 1
)
```
- 状态: CLOSED (正常) → OPEN (熔断) → HALF_OPEN (探测) → CLOSED
- 按服务+进程维度独立熔断
- OPEN 状态直接返回 `IpcResult.ServiceUnavailable`

### F9: 线程池管理

```kotlin
transport {
    binderPoolSize = 4         // Binder 线程池
    ioPoolSize = 8             // IO 调度线程池
    priorityQueue = true       // 安全消息优先
}
```
- `Dispatchers.IO` 替换为自定义 `ExecutorService`
- 优先级: SAFETY > NAVIGATION > MEDIA > DIAGNOSTIC

---

## Phase 4: Performance (Features 10, 11)

### F10: 请求批处理

```kotlin
val results = falcon.batch {
    call<INavService> { getLocation() }
    call<ISensorService> { getSpeed() }
}
// 合并为单次 Binder 事务
```

- `BatchRequest` 包含多个 `IpcEnvelope`
- 单次 `IIpcHost.invoke()` 携带数组
- 远端并行执行后批量返回

### F11: 服务版本管理

```kotlin
interface INavService : IpcService {
    @IpcMethod(version = 1)
    suspend fun getLocation(): Location

    @IpcMethod(version = 2)
    suspend fun getLocationV2(): LocationV2
}
```
- KSP 生成代码包含版本信息
- Proxy 调用时携带客户端版本
- Stub 根据版本分发到对应方法

---

## Phase 5: Automotive-Specific + Quality (Features 12-19)

### F12: 车辆状态感知
- `VehicleState` enum: OFF, ACC, RUNNING, LOW_POWER
- 非关键服务在 OFF/LOW_POWER 时暂停
- 状态变化通过 Flow 通知

### F13: 多屏支持
- `DisplayTarget` enum: MAIN, CLUSTER, HUD, REAR
- 按屏幕分组服务路由
- 屏幕间共享事件总线

### F14: OTA 兼容层
- 服务注册时携带版本号
- Proxy 发现版本不匹配时自动降级
- 日志告警 + 监控上报

### F15: 诊断模式
- `falcon.enableDiagnostics()` 开启全量日志
- 每次调用 dump 到 `/data/data/<pkg>/falcon_diag/`
- 支持远程配置下发

### F16: 热更新接口 (简化版)
- 服务接口版本化注册
- 新版本服务注册后旧版本 Proxy 平滑迁移

### F17-19: 测试体系
- Instrumented 跨进程集成测试
- 压力测试 (1000 QPS, 100 次崩溃重连)
- LeakCanary 集成 + SharedMemory 泄漏检测
