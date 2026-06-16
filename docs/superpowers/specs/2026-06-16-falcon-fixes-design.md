# Falcon IPC 修复设计方案

- 日期: 2026-06-16
- 范围: 修复代码审计发现的 11 类问题(安全正确性 / 性能 / 健壮性)
- 状态: 已批准,待实现

## 背景

对 Falcon IPC 框架的审计发现:若干文档宣传的核心特性虽已实现但**未接入运行路径**(死代码),且存在影响安全与正确性的 bug。本设计逐项给出修复方案,优先级 P0(安全正确性)> P1(性能)> P2(健壮性)。

测试策略:纯 JVM 可测的逻辑走 TDD 补单测;依赖 Android 框架(Binder / PackageManager / SharedMemory FD)的部分不写 JVM 单测,靠代码审查 + 现有集成测试结构 + benchmark APK 验证。

---

## 第一部分:安全正确性(P0)

### Fix 1 — 调用方身份(权限校验失效)

**问题**:`IpcHostService.invoke` 用 `ProcessUtils.getCurrentProcessName(this)`(宿主自身进程名)作为 `callerProcess` 传给权限校验。`PermissionChecker` 的 allow/deny 列表永远在拿宿主进程名匹配,真实调用方从未参与判断 —— 访问控制实质失效。

**方案**:
- 新增 `CallerResolver(context)`:`fun resolve(pid: Int): String`,用 `ActivityManager.runningAppProcesses` 查找进程名,`ConcurrentHashMap<Int, String>` 缓存结果。
- `IIpcHost.Stub.invoke` 内用 `Binder.getCallingPid()` 取真实调用方 PID(必须在 Binder 事务栈帧内取),经 `CallerResolver` 解析为进程名。
- `MessageRouter.handleLocal` 签名扩展为 `handleLocal(envelope, callerProcess, callerPid)`;`callerPid` 传给限流。

### Fix 2 — 限流接入(滑动窗口)

**问题**:`RateLimiter` 被 `MessageRouter` 构造接收但从未调用(全工程无 `tryAcquire` 调用点)。`resetCounters()` 也无人调用,即使接上,累计计数永不清零,跑满 `maxCallsPerSecond` 后将永久拒绝。

**方案**:
- `RateLimiter` 改为滑动窗口:`ConcurrentHashMap<Int, ArrayDeque<Long>>` 记录每个 PID 的调用时间戳;`tryAcquire` 时剔除 1 秒前的时间戳再判断阈值。并发数仍用 `AtomicInteger`(`concurrentCalls`)。删除 `resetCounters`。
- 在 `MessageRouter.handleLocal` 开头调用 `rateLimiter.tryAcquire(callerPid)`,超限抛 `IllegalStateException` 映射到新增 `ErrorCode.RATE_LIMITED`;`finally` 中 `rateLimiter.release(callerPid)`。

### Fix 3 — SharedMemory 零拷贝真正接入路由(架构改动)

**问题**:招牌特性"≥64KB → SharedMemory 零拷贝"未接入任何路由。`SharedMemoryTransport.allocate/write/read/verifyToken` 在生产代码从未被调用。当前实现把 `SharedMemory` 存在**本进程** `ConcurrentHashMap<memoryId>`,接收端按 memoryId 查表 —— 跨进程时对端没有该表,设计上无法跨进程工作。

**关键设计**:Android `SharedMemory` 本身是 `Parcelable`;放入 Binder 事务时内核会 `dup()` FD 传到对端。真正的零拷贝必须让 **FD 随 envelope 一起过 Binder**,而非靠 memoryId 查本地表。FD 内联后,原 HMAC token / registry 防重放模型失去意义(无共享注册表可攻击,两进程随机 HMAC key 无法互验),故移除。安全边界由内核 FD 传递 + 已有签名校验保证。

**方案**:
- `IpcEnvelope` 新增字段 `sharedMemory: SharedMemory?`(Parcelable 随事务传 FD)及 `largePayload: Boolean` 标志;大数据时 `args` 置 null。更新 `writeToParcel` / 构造器 / `equals` / `hashCode`。
- `SharedMemoryTransport` 重构为无状态工具:
  - `writeToShared(data: ByteArray): SharedMemory` — create + mapReadWrite + put + 设为只读保护。
  - `readFromShared(shm: SharedMemory): ByteArray` — mapReadOnly + 读取,读后由调用方 `close()`。
  - 删除 `allocations` map、HMAC token、`allocate/verifyToken/generateToken/computeHmac` 等。
- 发送侧(`ProxyFactory.executeIpcCall` 请求、`IpcHostService` 响应):`payload.size >= config.transport.sharedMemoryThreshold` 时写入 SharedMemory 装入 envelope、`args=null`;否则走原 `args`。
- 接收侧(`MessageRouter.handleLocal` 解析请求、`ProxyFactory` 解析响应):若 `envelope.sharedMemory != null` 则 `readFromShared` 取回字节,用完 `close()`。
- 可测性隔离:将"选哪条传输"抽为纯函数 `TransportSelector.shouldUseSharedMemory(size: Int, threshold: Int): Boolean`,单测覆盖阈值边界;FD 读写在 JVM 不可测,靠审查 + benchmark 验证。

### Fix 4 — 序列化 fail-fast(静默丢数据)

**问题**:`IpcSerializer` 兜底分支 `json.encodeToString(arg.toString())` 序列化的是 toString 文本且标记为 STRING,非 Parcelable 自定义类型在对端静默变成字符串。`ByteArray` 分支 `writeInt(size)` 后又 `writeByteArray`(本身已写长度),长度写两遍。

**方案**:
- 兜底分支改为抛 `IllegalArgumentException("Unsupported type ...; implement Parcelable")`。
- `ByteArray` 序列化去掉多余的 `writeInt(arg.size)`,统一用 `writeByteArray` / `createByteArray` 往返(同步调整反序列化)。

---

## 第二部分:性能(P1)

### Fix 5 — 反射方法缓存

**问题**:`MessageRouter.findMethod` 每次请求都扫描 `clazz.methods` + `interfaces.flatMap{methods}`,`method.invoke` 走反射,无缓存。对高频 IPC 是主要开销。

**方案**:`MessageRouter` 新增 `ConcurrentHashMap<String, Method>`,key = `"${className}#${method}/${argCount}"`,缓存 `findMethod` 结果;`isAccessible=true` 只在放入缓存时设一次。

### Fix 6 — 签名校验缓存

**问题**:`SignatureGuard.verify` 在每个 `invoke()` 上调用 `getPackagesForUid` + `getPackageInfo` + SHA-256。

**方案**:`SignatureGuard` 新增 `ConcurrentHashMap<Int, Boolean>` 按 UID 缓存校验结果,命中直接返回。

### Fix 7 — ContentResolver 查询移出主线程

**问题**:`PeerManager` 用主线程 Handler,`refreshPeers` 在主线程对 SQLite 做 `query`,有 ANR / 掉帧风险。

**方案**:`refreshPeers` 的查询放到 `IpcThreadPool` 执行,`bindService` 等回主线程(经主线程 Handler post)。

### Fix 8 — IpcThreadPool 接入 + 生命周期

**问题**:`IpcThreadPool` 除自身定义外无任何引用;`FalconManager.shutdown()` 不关闭任何 executor。

**方案**:`FalconManager` 持有 `IpcThreadPool` 实例,传给 `PeerManager`(Fix 7 复用);`stop()` / `shutdown()` 中调用 `threadPool.shutdown()`。

---

## 第三部分:健壮性(P2)

### Fix 9 — 重连去重

**问题**:`onServiceDisconnected` 与 `DeathRecipient` 可能对同一进程同时触发 `scheduleReconnect`,产生多条并行重连 + 重复 `bindService`。退避翻倍时序脆弱。

**方案**:`PeerManager` 新增 `ConcurrentHashMap.newKeySet<String>()` 作为 `reconnecting` 标志;`scheduleReconnect` 用 `add` 去重(已在重连中则跳过),连接成功后 `remove`。退避翻倍移到调度时计算。

### Fix 10 — getService 乐观代理

**问题**:`FalconManager.getService` 第 3 步即使无 peer 拥有该服务,也对首个 peer 盲创建代理返回,调用时才失败。

**方案**:删除第 3 步;仅当 `__check_service__` 确认服务存在时才返回代理,否则返回 null。`__check_service__` 在 `handleLocal` 中作为真实分支:返回目标服务是否已注册。

### Fix 11 — 杂项

- `getService` 循环、`PeerManager.stop` 等空 `catch` 改为至少 `FalconLogger.w` 记录。
- 去掉 `IpcHostService` 中重复的 `SignatureGuard.init`,复用 `FalconManager` 持有的实例(经 `Falcon.getInstance()` 暴露)。

---

## 测试计划(纯 JVM,TDD)

- `RateLimiterTest`:滑动窗口超限/恢复、并发上限、release 计数。
- `TransportSelectorTest`:阈值边界(< / = / > sharedMemoryThreshold)。
- `IpcSerializerTest`:不支持类型 fail-fast 抛异常、`ByteArray` 往返一致、各基础类型/List/Map 往返。
- `MessageRouterTest`:方法缓存命中(同一 key 第二次不再扫描)、限流拒绝路径。
- 重连去重:将去重判断抽为可测的纯逻辑后单测。

不写 JVM 单测(框架依赖):`CallerResolver`(ActivityManager)、`SignatureGuard` PackageManager 路径、SharedMemory FD 读写、Binder 调用方 PID —— 靠代码审查 + 现有集成测试结构 + benchmark APK。

## 实现顺序

P0(Fix 1→2→3→4)→ P1(Fix 5→6→7→8)→ P2(Fix 9→10→11)。Fix 3 改动面最大(AIDL/envelope/transport/router/host/proxy),单独成阶段并配 benchmark 验证。
