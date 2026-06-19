# Benchmark — Real Multi-Method IPC Comparison Design

- 日期: 2026-06-19
- 性质: benchmark 模块功能补全(把 AIDL-only 自测改成 5 种跨进程方式的真实对比)
- 前提(已确认): 5 种方式全测含 Broadcast;Broadcast 走真实跨进程;Falcon 走真实两进程。

## 背景与问题(现状)

`falcon-benchmark` 的计时内核 `BenchmarkRunner`(warmup + N 次 + avg/p50/p95/p99,跨 `:benchmark_remote` 进程)是好的,但:
- `BenchmarkActivity.runBenchmarks()` **只跑了 AIDL**;`MessengerTest`/`ContentProviderTest` 写了却从未被调用。
- **没有 Falcon 测试**(框架核心卖点未对比)。
- ContentProvider 的 `<provider>` **未在 manifest 声明** → 跑了会崩。
- **没有 Broadcast**。

目标:让 benchmark 真正同表对比 **AIDL / Messenger / ContentProvider / Falcon / Broadcast** 五种跨进程方式。

> 说明:benchmark 是设备上运行的 APK(Activity),只能在真机/模拟器跑出数字。本设计仅承诺编译验证 + 代码审查;数字需设备运行。

## 测量方法(不变)

保留 `BenchmarkRunner.run(name, dataSize, iterations, warmup, block)`:warmup 预热,正式计时 `SystemClock.elapsedRealtimeNanos()` 单次往返,排序输出 avg/min/max/p50/p95/p99。所有方式都测"客户端发起一次请求并拿到响应/确认"的单次往返。

## 各方式实现

进程隔离:host 侧逻辑均在 `:benchmark_remote` 进程(manifest 声明);客户端在主进程。

### 1. Raw AIDL(基准线,已有)
保留:绑定 `BenchmarkHostService`(默认 binder = AIDL Stub),同步 `echoString`/`echoBytes`。规模:小/中/大。

### 2. Messenger(已有类,接入)
`MessengerTest` 接入 `runBenchmarks`;客户端绑定时 `intent.putExtra("transport","messenger")` 拿 messenger binder;`Message`+`replyTo`+`CountDownLatch`。规模:小/中/大(补大)。

### 3. ContentProvider(已有类,接入 + 补声明)
- manifest 新增 `<provider android:name=".BenchmarkProvider" android:authorities="${applicationId}.falcon.benchmark.provider" android:exported="false" android:process=":benchmark_remote" />`。
- 新增 `BenchmarkProvider`(继承 `ContentProvider`):`insert` 存最近值到内存,`query` 用 `MatrixCursor` 回最近值(echo 语义)。
- `ContentProviderTest` 接入;规模:小/中(中=16KB,经 CursorWindow,256KB 风险大故不测)。

### 4. Falcon(新增,重点)
测 **生成代理 → Bundle → Binder → IpcHostService → 生成 Dispatcher → impl** 的稳态单次调用热路径,与 AIDL 同为"预绑定后测调用",公平。
- **服务接口**:复用 `IBenchmarkFalconService`(`@IpcMethod echoString/echoBytes/computeSum`)。
- **远程进程(`:benchmark_remote`)**:`BenchmarkApp`(Application)在该进程 `onCreate` 中 `Falcon.init(this){ generated(BenchmarkFalconGeneratedRegistry) }` 并 `Falcon.getInstance().register(IBenchmarkFalconService::class, impl)`。这样该进程的 `IpcHostService.serviceRegistry` 持有生成 dispatcher。
- **客户端(主进程)**:`FalconTest` 直接绑定该进程的 `IpcHostService`(用 Falcon 的 host 绑定 Intent,显式指向 `:benchmark_remote`),拿到 `IIpcHost` → 包成 `BinderTransport` → `BenchmarkFalconService_Proxy(transport, key)` → 同步调 `echoString`/`echoBytes`。
- **为何绕开 PeerManager/IpcRegistryProvider 发现层**:发现是一次性开销;benchmark 测稳态单次调用热路径(与 AIDL 一致),且避开从未在设备验证过的发现时序。签名校验:同 App 同签名 → 自签名默认可信(P0 fail-closed 默认即放行自身)→ `onBind` 通过。
- 规模:小/中/大。
- 需在 benchmark manifest 确保 `IpcHostService` 在 `:benchmark_remote` 进程可绑定(若 falcon-core 库 manifest 已声明,确认其 process/exported 适配;否则在 benchmark 覆盖声明)。

### 5. Broadcast(新增,真实跨进程,标注不对等)
- **往返**:主进程 `FalconBroadcast`(动态注册 reply 接收器,action `ACTION_BENCH_REPLY`)→ 发显式广播 `ACTION_BENCH_REQUEST`(`setPackage(packageName)`,带 data + requestId)→ `:benchmark_remote` 进程 manifest 声明的 `BenchmarkRequestReceiver`(`android:process=":benchmark_remote"`)收到后回发 `ACTION_BENCH_REPLY` 广播 → 主进程接收器 `CountDownLatch.countDown()`。
- 全程过系统 AMS,确保真 IPC、真跨进程。
- 规模:小/中(广播 extras 不宜大;大数据不测)。
- **报告标注**:广播是单向 fire-and-forget,此处的"往返"是人为加的回程广播,经 AMS 分发,语义与请求-响应不对等,数字仅供参考。

## 编排(BenchmarkActivity)

`runBenchmarks()` 依次跑全部 5 种 × 各自规模,`results` 收齐后按数据规模分组打印对比表(`Method / Avg(ms) / P50(ms) / P99(ms)`,按 avg 升序)。每种方式先建立连接(bind / register receiver)再测;失败的方式捕获并在表中标注"N/A(原因)"而非整体崩溃。

## 公平性与 caveats(写进屏上报告尾部)
- 各机制线程模型不同(AIDL binder 线程池;Messenger 主线程 Handler;Falcon binder 线程池;ContentProvider binder+SQLite/MatrixCursor;Broadcast 经 AMS),**不强行归一化**(归一化反失真),如实标注。
- ContentProvider 每次 insert+query 是两跳;Broadcast 经 AMS 两段;均注明。
- 数字依设备而异;同设备同次运行内横向对比才有意义。

## 文件
- 改:`BenchmarkActivity.kt`(编排全部 5 种)、`AndroidManifest.xml`(provider + request receiver + Falcon host 进程)、`MessengerTest.kt`(补大数据)、`ContentProviderTest.kt`(补中数据 + 对接真 provider)。
- 增:`BenchmarkApp.kt`(per-process Falcon init+register)、`BenchmarkProvider.kt`、`FalconTest.kt`、`BroadcastTest.kt`、`BenchmarkRequestReceiver.kt`。
- 不动:`BenchmarkRunner.kt`、`BenchmarkResult.kt`、`AidlTest.kt`、`BenchmarkHostService.kt`(messenger/aidl host 已有)。

## 测试与验证
- 本环境:`./gradlew :falcon-benchmark:assembleDebug` 编译通过 + 代码审查。
- 设备(需你跑):安装 APK,打开 Activity,读屏上对比表。标注"未在设备验证"。

## 风险
- Falcon 两进程是首次真实部署:签名/绑定/Falcon.init 时序可能需设备调试。采用"直接绑 IpcHostService"已最大限度降低风险(避开发现层)。
- Broadcast 经 AMS,延迟波动大、且 Android 后台广播限制可能影响(同 App 显式广播一般不受限,但需设备确认)。
- 全部仅编译可验证,数字需设备。
