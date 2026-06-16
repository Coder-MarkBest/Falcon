# Falcon IPC 过度设计审计 + 精简方案

- 日期: 2026-06-17
- 性质: 设计审计与精简建议(非实现)
- 已确认前提:
  1. **无高频大 payload**(绝大多数是控制信号/状态/配置类小数据,远小于 1MB Binder 上限)
  2. **有第三方/不同签名 App 接入**(并非只有自己同签名进程)
  3. **同版本部署**(不需要跨版本字段级演进)

---

## 0. 一句话结论

以 Android 平台原生能力(AIDL/Binder)为基准,本框架**整体过度设计**:它把所有调用塞进一个通用方法 `IIpcHost.invoke(IpcEnvelope)`,在其上**手工重做** AIDL 已免费提供的序列化与方法分发,并叠加了一批与真实威胁模型不匹配的"韧性/安全/兼容"组件。同时,安全层存在一个**方向性错误**(见 §1),必须修而非删。

精简后预计可**删除 / 大幅简化约 40–50% 的非必要代码**,正确性与性能反而提升。

---

## 1. 🔴 首要问题:安全模型与"第三方接入"自相矛盾(必须改,不是删)

`SignatureGuard.verify`:
```
if (callingUid != selfUid) return false
// 且要求调用方所有包签名 == 自身签名
```
这意味着**只有同 UID / 同签名的调用方能通过**。但既然确认了"有第三方接入",当前模型会把第三方 App **直接拒之门外** —— `onBind` 和每次 `invoke` 都会拒绝。CLAUDE.md 还宣称"签名校验强制、不可禁用",与第三方接入需求直接冲突。

**结论**:安全层不是"过度",是"方向错"。改造方向:
- `SignatureGuard` 从"只认自身签名"→ **可信签名白名单(pin 已知合作方证书指纹)**,或改用标准 Android 机制:自定义 `signature|privileged` 权限 + `Binder.getCallingUid()` 校验。
- 调用方身份用 **UID/包名**(平台可信)而非进程名做权限判定(进程名可伪造、且我们刚发现解析成本高)。
- 在此前提下,`PermissionChecker`(按调用方 allow/deny)与 `RateLimiter`(防滥用)**保留且有意义**。

---

## 2. 根因:在 AIDL 之上重新发明 AIDL

| AIDL 原生免费提供 | 本框架的手工替代(更差) |
|---|---|
| 每方法自动稳定 transaction code | `envelope.method` 字符串 + 服务端反射 `findMethod`(重载歧义) |
| 编译期类型安全 marshalling(事务内格式正确) | `IpcSerializer.marshall()`(官方警告跨版本不稳定)+ JSON 兜底(已改 fail-fast) |
| 编译期 Stub/Proxy,零反射 | 运行时 `java.lang.reflect.Proxy` + `Method.invoke` |
| `getCallingUid/Pid` 原生身份 | 自造 `CallerResolver`(PID→进程名,有缓存/复用隐患) |

**KSP 已经生成了 `_Stub`/`_Proxy` 却没接运行时** —— 即"卖点零反射"根本没兑现。

---

## 3. 组件级审计:删 / 简 / 留

| 组件 | 处置 | 依据(基于已确认前提) |
|---|---|---|
| **SharedMemoryTransport + TransportSelector + IpcEnvelope 的 largePayload/sharedMemory 字段 + 4 处路由接线** | **删除** | 无大 payload;Binder ≤1MB 足够。删除可同时简化 MessageRouter/ProxyFactory/IpcHostService/BinderTransport/IpcEnvelope/PeerManager 六个文件,去掉所有 FD 生命周期与 SDK 守卫复杂度 |
| **CircuitBreaker(+ Test + 接线)** | **删除** | 熔断是给不可靠网络/微服务防级联雪崩的;同设备 Binder 无此故障剖面。防滥用应由限流负责 |
| **OtaCompatManager / ServiceVersion(+ 2 套 Test)** | **删除** | 同版本部署,版本协商是死重量 |
| **IpcThreadPool 的优先级队列(SAFETY/NAV/MEDIA)** | **简化** | 投机特性,无证据需要分级调度。保留一个普通后台 executor 给 off-main-thread 查询即可 |
| **服务发现:IpcRegistryProvider(ContentProvider+SQLite)+ ContentObserver + 指数退避** | **重审/简化** | ① 有第三方接入时,允许任意 App 向多进程 ContentProvider 写注册是**安全面**;② 若进程拓扑是已知静态集合,直接 bindService 到已知组件更简单。建议改为受控的静态/白名单发现 |
| **`__check_service__` 魔法字符串探测** | **删除/替换** | 用一个正式方法(或 `getServiceInfo` 已有)替代;省掉限流/鉴权特判 |
| **BatchRequest / EventBus / DiagnosticsManager** | **按使用情况删** | 各自增加表面积;若无真实调用方则删。benchmark 不算 |
| **SignatureGuard** | **重设计**(见 §1) | 自身签名→可信白名单 |
| **RateLimiter** | **保留** | 有第三方接入,防滥用成立 |
| **PermissionChecker** | **保留**(身份改用 UID/包名) | 同上 |
| **Monitor(默认关)/ TimeoutController** | **保留** | 成本低、价值实在 |
| **IpcSerializer / MessageRouter 反射 / 自造 methodId 计划** | **被 §4 取代** | 见下 |

---

## 4. 两条收敛路线(替代"加固协议")

**路线 X —— 拥抱原生 AIDL(推荐,最大化精简)**
- KSP 为每个 `@IpcService` 生成真正的 `.aidl`(或直接生成 Binder `Stub`/`Proxy`)。
- 删除 `IpcEnvelope` 通用信封、`IpcSerializer`、`MessageRouter` 反射分发、`ProxyFactory` 动态代理、自造 methodId/版本头。
- 方法分发、类型化序列化、零反射、调用方 UID —— **全部由平台提供**。
- 权限/限流作为生成 Stub 的统一前置切面(在每个 transaction 入口调用)。
- 跨进程数据类型实现 Parcelable(或 KSP 为 `@Serializable` data class/enum 生成 Parcelable)。
- 框架收敛为:**注解 + KSP + 发现/连接/生命周期 + 安全切面**这层薄封装。
- 收益:代码量大幅下降,正确性/性能更好,卖点"编译期零反射"真正兑现。
- 成本:中。主要在 KSP 生成器与运行时接线;但删除的远多于新增。

**路线 Y —— 保留通用信封,只做减法**
- 不动架构,按 §3 删除 SharedMemory/熔断/OTA/优先级队列等,重设计 SignatureGuard,简化发现。
- 序列化/分发的手搓问题**仍在**(反射、marshall、重载歧义),只是规模变小。
- 成本:小。但治标不治本。

---

## 5. 建议执行顺序(若选路线 X)

1. **P0 安全方向修正**:SignatureGuard → 可信签名白名单;权限身份改用 UID/包名。(独立、可先落地)
2. **P1 删负债**:移除 SharedMemory 全链路、CircuitBreaker、OtaCompat/ServiceVersion、优先级队列、`__check_service__`。每项独立、低风险、各自一个提交。
3. **P2 协议收敛**:KSP 生成 per-service AIDL/Binder Stub;运行时改走生成 Stub;删除 IpcEnvelope/IpcSerializer/MessageRouter 反射/ProxyFactory 动态代理。(最大块,需充分测试)
4. **P3 发现简化**:静态/白名单发现替代 ContentProvider 注册表(若拓扑允许)。

P1 可立即带来收益且风险极低;P2 是核心收敛,建议单独成 spec 与计划。

---

## 6. 不要动的部分(范围纪律)

- Monitor/IpcCallStats/Interceptor(默认关,低成本)
- TimeoutController(Binder 可能挂起,超时有价值)
- 模块划分(annotations/core/ksp/benchmark 依赖链清晰)
- 注解 DSL 本身(`@IpcMethod/@IpcEvent/...` 设计合理)

---

## 7. 风险与权衡

- 路线 X 是较大重构,需要 benchmark 与(理想情况)双进程插桩测试护航,确保行为等价。
- 删除安全/韧性组件前,务必确认无生产调用方依赖(grep + 评审)。
- SignatureGuard 重设计涉及真实安全边界,改动需安全评审。
- "同版本""无大 payload""有第三方"三前提一旦变化(尤其后续要传相机帧或要跨版本 OTA),结论需重新评估。
