# P2-B — Events / Streams / Callbacks Binder-native + Legacy Removal Design

- 日期: 2026-06-18
- 性质: 收尾 Binder-native 收敛 —— @IpcEvent/@IpcStream/@IpcCallback 走生成代码 + 类型化 Bundle,并删除 legacy(IpcSerializer、反射回退、IpcEnvelope.args 字节字段)。
- 前提(已确认): 同版本;有第三方接入;一次性做完事件/流/回调;事件收集懒加载 + 引用计数。

## 背景

P2-A 已把 `@IpcMethod`(请求-响应)收敛为生成的 `Xxx_Proxy`/`Xxx_Dispatcher` + 类型化 Bundle + 稳定 methodId。`@IpcEvent/@IpcStream`(返回 `Flow<T>`)与 `@IpcCallback`(`fun m(args, reply: IpcReply<T>)`)仍走 legacy:`ProxyGenerator` 对 Flow 方法 punt,事件负载是 `IpcSerializer` 编码的 ByteArray,`MessageRouter` 保留反射回退。本设计接通它们并删除 legacy。

现有基础设施(复用):
- `EventBus`:本地 SharedFlow + 远程 `IIpcEventCallback` 订阅者;`emit/addRemoteSubscriber/removeRemoteSubscriber`。
- `EventProxy.remoteEventFlow`:callbackFlow 包装订阅。
- `IIpcEventCallback.onEvent(IpcEnvelope)` + `getEventKey()`。
- `IpcReply<T>`:`onResult/onError`。
- `IpcHostService.subscribe/unsubscribe`(委托 EventBus)。

## A. 订阅/回调链路 plumbing

- `IpcTransport` 扩展:
  - `fun subscribe(eventKey: String, callback: IIpcEventCallback)`
  - `fun unsubscribe(eventKey: String, callback: IIpcEventCallback)`
  - `fun invokeCallback(envelope: IpcEnvelope, reply: IIpcEventCallback)`
  默认实现可抛 `UnsupportedOperationException`(非 Binder 传输);`BinderTransport` 经 `host` 实现。
- **AIDL 变更**:`IIpcHost.aidl` 增 `void invokeCallback(in IpcEnvelope request, IIpcEventCallback reply);`(`subscribe`/`unsubscribe` 已存在)。
- 事件元素值与回调结果值统一放 `IpcEnvelope.argsBundle` 的 `"r"` 键(类型化,沿用 P2-A);`IIpcEventCallback` 不改。
- eventKey 约定:`"${serviceKey}#${methodId}"`(methodId 由 `MethodIds.of` 计算,proxy 与 server 一致)。

## B. IpcDispatcher 扩展为三能力

```kotlin
interface IpcDispatcher {
    fun dispatch(methodId: Int, args: Bundle): Bundle                       // @IpcMethod(已有)
    fun eventFlow(methodId: Int): kotlinx.coroutines.flow.Flow<Bundle>? = null   // @IpcEvent/@IpcStream
    fun invokeCallback(methodId: Int, args: Bundle, reply: (Bundle) -> Unit) {}  // @IpcCallback
}
```
默认实现保证向后兼容(现有仅含 @IpcMethod 的生成 dispatcher 不需改)。生成的 Dispatcher 覆写所需能力:
- `eventFlow`:`when(methodId){ <evtId> -> impl.evt().map { v -> Bundle().also { <TypeCodec.put(elemType,"it","r","v")> } }; ... else -> null }`(元素类型经 TypeCodec 编码)。
- `invokeCallback`:`when(methodId){ <cbId> -> { val a0 = <decode>; impl.m(a0, object: IpcReply<T>{ override fun onResult(d){ reply(Bundle().also{ <TypeCodec.put>(it,"r",d) }) } }) }; else -> super.invokeCallback(...) }`。

methodId 计算:与 @IpcMethod 相同,`MethodIds.of(m)`。对 @IpcCallback,参数列表用于哈希时应**排除** `IpcReply` 参数(server 与 proxy 都按"业务参数"算 id,需一致);在 `MethodIds` 增 `ofExcludingReply(m)` 或在生成两端统一过滤 `IpcReply` 参数。统一规则:计算 id 时跳过类型为 `com.falcon.ipc.service.IpcReply` 的参数。

## C. 服务端事件生命周期(懒加载 + 引用计数)

- `EventBus` 改造:
  - 负载 `ByteArray` → `Bundle`(`emit(eventKey, bundle)`;`getLocalFlow` 返回 `Flow<Bundle>`)。
  - 新增按 eventKey 的订阅计数与采集 Job:`subscriberCount: ConcurrentHashMap<String, Int>`、`collectJobs: ConcurrentHashMap<String, Job>`、一个 `CoroutineScope(SupervisorJob()+Dispatchers.Default)`。
  - `onFirstSubscribe(eventKey, flowProvider: () -> Flow<Bundle>?)`:计数 0→1 时启动 `scope.launch { flowProvider()?.collect { emit(eventKey, it) } }` 存入 collectJobs。
  - `onLastUnsubscribe(eventKey)`:计数→0 时 `collectJobs.remove(eventKey)?.cancel()`。
  - 计数增减用同步块保证原子(避免订阅/退订竞态)。
- `IpcHostService.subscribe(eventKey, cb)`:`addRemoteSubscriber`;解析 `serviceKey#methodId`;`eventBus.onFirstSubscribe(eventKey){ serviceRegistry.getDispatcher(serviceKey)?.eventFlow(methodId) }`。
- `IpcHostService.unsubscribe`:`removeRemoteSubscriber`;`eventBus.onLastUnsubscribe(eventKey)`。
- `EventBus.emit` 扇出到远程订阅者:`cb.onEvent(IpcEnvelope(serviceKey=eventKey, method="__event__", argsBundle=bundle))`;dead callback 移除时同步递减计数。
- `shutdown`:cancel scope。
- EventBus 需要 ServiceRegistry/dispatcher 访问 —— 由 IpcHostService 在 subscribe 时通过 flowProvider 注入(EventBus 不直接依赖 registry,保持解耦)。

## D. @IpcCallback 路径

- Proxy:`override fun m(<args>, reply: IpcReply<T>) { val b = Bundle(); <put args>; val stub = object: IIpcEventCallback.Stub(){ override fun onEvent(e){ val t = <TypeCodec.get(T, "e.argsBundle!!", "r")>; reply.onResult(t) }; override fun getEventKey() = "" }; transport.invokeCallback(IpcEnvelope(serviceKey=serviceKey, methodId=<id>, argsBundle=b), stub) }`。
- 服务端 `IpcHostService.invokeCallback(request, reply)`:`val d = registry.getDispatcher(request.serviceKey) ?: error; d.invokeCallback(request.methodId, request.argsBundle ?: Bundle()){ b -> reply.onEvent(IpcEnvelope(requestId=request.requestId, argsBundle=b)) }`。错误经 `reply` 或忽略(onError 可后续扩展;本轮仅 onResult)。

## E. Proxy 生成补全(不再 punt 任何注解)

`ProxyGenerator` 为每类方法生成:
- `@IpcMethod`:同 P2-A(不变)。
- `@IpcEvent/@IpcStream`:`override fun e(): Flow<T> = com.falcon.ipc.core.EventProxy.typedRemoteFlow(eventKey, transport) { b -> <TypeCodec.get(elemType,"b","r")> }`。给 `EventProxy` 加 `fun <T> typedRemoteFlow(eventKey, transport, decode: (Bundle) -> T): Flow<T>`,基于现有 callbackFlow,onEvent 里 `decode(envelope.argsBundle ?: Bundle())`。
- `@IpcCallback`:如 D。
- **修终审 Minor**:对混合注解接口,每类方法都正确生成(不再对 `TypeCodec.put` 返回 null 的方法静默 `return@forEach`)。

## F. 删除 legacy(contract,expand→migrate→contract)

按序(全切 Bundle 后再删字段):
1. 事件/回调走通并测试绿后:
2. 删 `MessageRouter` 反射回退(findMethod/resolveMethod/methodCache + reflective invoke 分支);`handleLocal` 只保留 dispatcher 路径(+ 限流/权限/`__check_service__`)。
3. 删 `IpcSerializer`(+ 其测试);确认无引用(`grep -rn IpcSerializer falcon-core/src`)。EventBus/BatchRequest/IpcHostService 等改为 Bundle。
4. 删 `IpcEnvelope.args: ByteArray?` 字段及其 Parcel 读写(全切 `argsBundle`);更新 `response`/`error` 工厂与所有构造点。
5. `ServiceRegistry`:移除 impl 存储或仅保留本进程直返短路(getService local)。

## G. 类型支持

- 事件元素 T、回调结果 T:同 TypeCodec(基础/String/ByteArray/Parcelable/enum)。`@IpcStream` 多为 `Flow<ByteArray>`,支持。
- **修终审 Minor**:`TypeCodec` 用 `getAllSuperTypes()` 识别间接 Parcelable。

## H. 测试

- 纯 JVM/Robolectric(benchmark + core):
  - 生成 Dispatcher `eventFlow` 经 fake transport 多发往返(收集→Bundle→解码);`invokeCallback` 一发往返。
  - `EventBus` 引用计数:首订阅启动采集、末退订取消(用可控 flow + 断言 Job 取消)。
  - 删 legacy 后全量回归。
- 跨进程插桩(需设备/CI,本环境无设备):一个 @IpcEvent 流 + 一个 @IpcCallback 端到端;写好并标注"未在设备验证"。

## I. 分期(单 plan 多阶段)

1. IpcTransport/IIpcHost 扩展(`invokeCallback`)+ `EventProxy.typedRemoteFlow` + BinderTransport 实现。
2. IpcDispatcher 三能力接口 + DispatcherGenerator 生成 `eventFlow`/`invokeCallback`(+ methodId 排除 IpcReply 参数 + getAllSuperTypes)。
3. ProxyGenerator 补全 Flow/callback(+ 混合注解修复)。
4. 运行时:EventBus Bundle + 引用计数;IpcHostService.subscribe/unsubscribe/invokeCallback 接 dispatcher。
5. 删 legacy:反射回退 → IpcSerializer → IpcEnvelope.args 字段(逐步,保持每提交可编译可测)。
6. 测试 + 文档(CLAUDE.md:全 Binder-native,无 IpcSerializer/反射)。

## J. 风险

- 引用计数 + 协程作用域并发正确性(订阅/退订/死回调三方竞态)—— 用同步块 + 测试覆盖。
- 删 `IpcEnvelope.args` 是协议变更,牵涉所有构造点;expand→migrate→contract 保持绿。
- @IpcCallback 的 methodId 必须两端一致地排除 IpcReply 参数,否则 dispatch 错位 —— 用共享 `MethodIds` 规则。
- 跨进程那段无设备无法验证,如实标注。
