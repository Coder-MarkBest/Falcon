# Falcon 生产准入清单

Falcon 的 CI 能证明代码、KSP 产物、R8 和测试链路可构建，但不能代替目标车机上的系统验证。
每个车型/系统版本发布前执行以下最小门禁。

## 构建门禁

```bash
JAVA_HOME=<jdk17> ./gradlew clean build
```

该命令必须通过全部 JVM/Robolectric 测试、Android Lint、Release R8、AAR 和示例 APK 构建。
CI 的 `cross-app-instrumentation` job 另外安装两个独立 APK，在 Android 34 模拟器运行真实 Binder 测试。

## 推荐生产配置

```kotlin
Falcon.init(context) {
    peerPackages("com.vendor.navigation")
    strictThreadPolicy = true
    monitorLevel = MonitorLevel.NONE
    security {
        signatureVerification = true
        trustedSignatures = setOf("<peer certificate sha256>")
        rateLimitPerSecond = 500
        maxConcurrentCalls = 32
    }
    timeout {
        connectMs = 3_000
        callMs = 5_000
    }
    transport {
        maxBinderPayloadSize = 256 * 1024
        invokeTimeoutMs = 0
    }
}
```

- `MonitorLevel.NONE` 默认无统计开销。线上排障时动态开启 `BASIC` 或 `DETAILED`。
- `FULL` 会写 Android Trace，只建议短时间诊断。
- `strictThreadPolicy=true` 防止阻塞式服务发现误用在主线程。
- 不要关闭签名校验。跨供应商场景应显式配置证书 SHA-256。
- Dynamic RPC route/topic 应维护独立协议文档；新增字段必须可选，破坏性变更提升版本号。

## 真机验证矩阵

至少覆盖目标系统的最低、主力和最高 Android 版本，并完成：

1. 冷启动、服务端未启动、服务端延迟启动。
2. 客户端/服务端分别被 kill 后自动发现和重连。
3. 锁屏、待机唤醒、ACC 周期及系统内存压力。
4. Release + R8 构建下 Typed RPC、Dynamic RPC、event 和 callback。
5. 1/16/64/256 KB payload；超过限制必须得到明确错误。
6. 目标峰值 QPS 持续 30 分钟，观察 P50/P95/P99、CPU、Binder 线程和内存。
7. 错误签名、未授权进程、限流和订阅上限验证。
8. 新旧 APK 版本交叉安装，验证 Typed schema 拒绝和 Dynamic 版本协商。

## 可观测性

`MonitorFacade` 提供调用数、成功/失败数、平均/最大耗时和时间窗口采样；统计默认关闭。
Dynamic RPC 以 route 作为 method 维度。`DETAILED` 的每方法延迟窗口最多保留 10,000 个样本，
诊断文件队列最多保留 4,096 条待写记录，磁盘跟不上时通过
`DiagnosticsManager.getDroppedWriteCount()` 暴露丢弃量。

框架不内置厂商监控 SDK。生产项目应通过 `IpcInterceptor` 把聚合指标接入已有监控平台，
不要在拦截器中记录参数正文、位置、账号或车辆隐私数据。

## 当前边界

- Binder 单事务有效上限受系统和同时进行的事务影响；Falcon 将建议上限设为 256 KB。
- 同步 Binder 调用无法强制终止已经进入远端的业务代码；超时只保证调用方停止等待。
- event 是热流，不跨进程传播 Flow completion。
- benchmark 数值只允许比较同一设备、同一构建、同一轮测试中的结果。
- 安全关键车辆控制还需要业务层授权、幂等和状态机校验，不能只依赖 IPC 框架权限。
