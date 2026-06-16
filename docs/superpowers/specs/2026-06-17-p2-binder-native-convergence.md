# P2 — Binder-native Convergence (X3) Design

- 日期: 2026-06-17
- 性质: 核心调用链路重构(线协议 + 分发 + KSP 生成)
- 前提(已确认): 同版本部署;无高频大 payload;有第三方接入(P0 已处理信任);范围一次性覆盖请求-响应与事件/流/回调;策略 X3。

## 目标

根治审计指出的三个一级问题,且让 KSP 名副其实:
1. **线协议不稳定**:停用 `Parcel.marshall()` 跨进程;改用 Bundle(平台级跨 Binder 安全容器,与 Intent 同机制)。
2. **方法分发歧义**:每方法分配稳定 `methodId`(签名哈希),消除"按名+参数个数"的重载歧义。
3. **服务端反射**:用 KSP 生成的 `when(methodId)` 静态分发取代 `findMethod`/`Method.invoke`。

非目标:跨版本 schema 演进(同版本前提);自动支持任意 data class(见类型约束)。

## A. 线协议(IpcEnvelope)

- `args: ByteArray?` → `args: android.os.Bundle?`。
- 新增 `methodId: Int = 0`。`method: String` 保留仅作日志/诊断。
- 响应负载:`Bundle`,返回值放键 `"r"`;无返回(Unit)则空 Bundle。
- `writeToParcel`/`constructor(Parcel)`:Bundle 用 `writeBundle`/`readBundle`(注意设置正确 classloader 以反序列化 Parcelable:`bundle.classLoader = javaClass.classLoader`)。读写顺序保持对称。
- `methodId` 稳定哈希规则:`hash = fnv1a32("$methodName(" + paramTypeQualifiedNames.joinToString(",") + ")")`。在 KSP 与潜在手写处共用同一算法常量(写入生成代码,无运行时反射)。碰撞处理:同一接口内若两方法哈希相同(极罕见),KSP 报编译错误要求重命名。

## B. KSP 生成物

每个 `@IpcService` 接口生成三类工件:

### B1. `Xxx_Proxy(transport: IpcTransport, serviceKey: String) : Xxx`
- 每个 `@IpcMethod` 方法:
  - 将参数按下标键 `"0".."n"` 用类型化 `Bundle.putX` 写入 `args` Bundle。
  - 构造 `IpcEnvelope(serviceKey, method=name, methodId=<const>, args=bundle)`,`transport.invoke(envelope)`。
  - 成功:从响应 Bundle 用类型化 `getX("r")` 读返回值;`Unit` 直接返回。
  - 错误:抛 `RuntimeException("IPC error [code]: msg")`。
- `@IpcEvent/@IpcStream`(返回 `Flow<T>`):见 D。
- `@IpcCallback`:见 D。

### B2. `Xxx_Dispatcher(impl: Xxx) : IpcDispatcher`
- 新运行时接口:`interface IpcDispatcher { fun dispatch(methodId: Int, args: Bundle): Bundle }`(放 `com.falcon.ipc.runtime`)。
- `dispatch` 内 `when(methodId)`:用类型化 `getX` 从 args 读参数 → 调 `impl.method(...)` → 把返回值类型化 `putX("r", ...)` 进结果 Bundle。
- 未知 methodId → 抛 `IllegalArgumentException`(映射到 `ErrorCode.METHOD_NOT_FOUND`)。

### B3. `FalconGeneratedRegistry`(每个编译单元聚合一个)
- 对象,持两张表:
  - `dispatcherFactories: Map<String /*serviceKey*/, (Any) -> IpcDispatcher>`
  - `proxyFactories: Map<String, (IpcTransport, String) -> Any>`
- KSP 每个编译单元生成一个对象 `<ModuleId>FalconGeneratedRegistry : FalconGeneratedRegistry`,实现公共接口 `interface FalconGeneratedRegistry { val dispatcherFactories: Map<String,(Any)->IpcDispatcher>; val proxyFactories: Map<String,(IpcTransport,String)->Any> }`(放 `com.falcon.ipc.runtime`)。
- **聚合方式(显式,无反射)**:消费方在初始化时把生成的 registry 传入 DSL:`Falcon.init(context){ generated(AppFalconGeneratedRegistry) }`。`FalconConfig` 增 `internal val generatedRegistries = mutableListOf<FalconGeneratedRegistry>()` 与 `fun generated(r: FalconGeneratedRegistry)`。`FalconManager` 在 register/getService 时按 serviceKey 在所有已注册 registry 中查工厂(找不到则报错)。
- 模块标识 `<ModuleId>` 取该编译单元根包名的简化(KSP 选项 `falcon.moduleId` 可覆盖;默认用首个服务接口的包名末段),避免多模块对象重名。benchmark 模块各自生成并各自传入。

## C. 运行时接线

- `IpcDispatcher`(B2)放 `com.falcon.ipc.runtime`。
- `ServiceRegistry` 改为存 `IpcDispatcher`(不再存裸 `IpcService` impl):`register(serviceKey, dispatcher)` / `getDispatcher(serviceKey): IpcDispatcher?`。
- `FalconManager.register(cls, impl)`:经生成 registry 的 `dispatcherFactories[key](impl)` 建 dispatcher 存入 ServiceRegistry。
- `MessageRouter.handleLocal(envelope, callerPackage, callerPid)`:保留限流/权限/`__check_service__`/监控;核心改为:
  ```
  val dispatcher = registry.getDispatcher(envelope.serviceKey) ?: throw IllegalStateException("Service not found")
  return dispatcher.dispatch(envelope.methodId, envelope.args ?: Bundle())
  ```
  返回值是 Bundle;`IpcHostService` 直接放入响应 envelope。
- `getService`:经生成 registry 的 `proxyFactories[key](transport, key)` 建 proxy。
- **删除**:`IpcSerializer`、`ProxyFactory`(动态代理)、`MessageRouter.findMethod/resolveMethod/methodCache`、`IpcHostService` 里 `serializeResult` 调用(改为 dispatcher 返回 Bundle 直接装envelope)。

## D. 事件 / 流 / 回调

复用 `IIpcHost.subscribe/unsubscribe` + `IIpcEventCallback`,负载改类型化 Bundle。

- `@IpcEvent`/`@IpcStream`(`fun e(): Flow<T>`):
  - Proxy:`callbackFlow { val cb = object: IIpcEventCallback.Stub(){ onEvent(env){ trySend(decodeTyped(env.args)) } }; host.subscribe(eventKey, cb); awaitClose { host.unsubscribe(eventKey, cb) } }`。eventKey = `serviceKey#methodId`。
  - 服务端:impl 的 Flow 被运行时收集,每个元素类型化编码进 Bundle,经已订阅回调投递。生成 Dispatcher 暴露 `collectEvent(methodId, scope, emit)` 钩子;`IpcHostService` 管理订阅者并把 impl Flow 接到回调。
- `@IpcCallback`(`fun m(args, reply: IpcReply<T>)`):
  - Proxy:transact 发送 args + 一个回调 binder;服务端调用 impl,impl 通过 IpcReply 回填,经回调 binder 类型化投递结果。
- 此部分最复杂,单独成计划阶段 4,配一个事件流的插桩测试。

## E. 类型支持与约束

- 直接支持:`Int/Long/Float/Double/Boolean/String/ByteArray`、`Parcelable`、`List<T>`/`Map<K,V>`(经 Bundle 原生 put/get;List 用 `putParcelableArrayList`/`putStringArrayList` 等,按元素类型选择)。
- **data class/enum 须实现 Parcelable**(enum 可 `putString(name)` + `valueOf` 解码,KSP 对 enum 生成此编码;data class 要求 `Parcelable`/`@Parcelize`)。KSP 对不支持的参数/返回类型 **编译期报错**(fail-fast,不再运行时静默)。
- 这比"自动支持任意 data class"收敛,是 X3 控制风险的核心取舍(已确认接受)。

## F. 删除清单

- `falcon-core/.../protocol/IpcSerializer.kt`(+ 其测试,改为 codec/dispatcher 测试)
- `falcon-core/.../core/ProxyFactory.kt`
- `MessageRouter` 的反射分发(findMethod/resolveMethod/methodCache)
- `IpcEnvelope.args: ByteArray` → `Bundle`(协议变更)

## G. 测试

- 纯 JVM:
  - methodId 哈希:稳定性(固定输入固定输出)、同接口内唯一性校验逻辑。
  - 生成 Dispatcher 的 `when` 分发 + 类型化 codec 往返(Robolectric,因 Bundle 是 Android 类型)。
- 插桩 androidTest(本轮新增):
  - 双进程请求-响应端到端(含 Parcelable 参数)。
  - 一个 `@IpcEvent` Flow 端到端。
- benchmark:更新到新 API(生成 proxy/dispatcher),验证延迟无回退。

## H. 实施分期(单 spec,多阶段)

1. **协议层**:`methodId` 哈希工具(纯函数 + 测试);`IpcEnvelope.args` 改 Bundle + methodId(协议变更,先让现有调用编译过渡)。
2. **KSP 请求-响应**:生成 `Xxx_Proxy`/`Xxx_Dispatcher`/`FalconGeneratedRegistry`(仅 @IpcMethod);类型化 codec 生成 + 编译期类型校验。
3. **运行时接线**:`IpcDispatcher` 接口、`ServiceRegistry` 存 dispatcher、`MessageRouter` 委派、`getService` 用生成 proxy;**删除 IpcSerializer/ProxyFactory/反射**。
4. **事件/流/回调**:@IpcEvent/@IpcStream/@IpcCallback 生成 + 运行时投递。
5. **插桩测试 + benchmark + 文档**。

## I. 风险

- 最大改动面,触及协议/KSP/运行时/benchmark;必须插桩测试护航。
- 多模块生成物聚合(B3)需在阶段 2 细化,避免反射式发现。
- 事件/流/回调(D)是已知最复杂部分;若阶段 4 受阻,可将其拆为独立后续 spec(阶段 1-3 已可独立交付请求-响应的收敛价值)。
- Bundle 反序列化 Parcelable 需正确 classloader,否则跨进程取 Parcelable 会失败 —— 生成代码统一设置。
