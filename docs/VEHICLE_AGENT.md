# Falcon 车载 Agent：第一版 MCP 实现

本实现使用第一版调用链：**Agent 编排原子 MCP 工具 → 车端 Gateway → Falcon Dynamic RPC → 业务应用**。
模型支持离线 Mock 和真实 DeepSeek 两种模式。DeepSeek Key通过应用配置界面保存到应用私有存储，不进入源码或APK。开启DeepSeek时输入文本会发送到官方模型服务。
导航、空调和媒体均为模拟业务状态，不会操作真实车辆或播放真实音频。

## 模块与调用链

```text
falcon-agent-demo APK
  DeepSeekHttpTransport.chat + ToolCallingAgent：模型自主多轮工具调用
  MockIntentModel + HomeJourneyAgent：仅离线对照路径
        │ HttpMcpToolClient（回环 HTTP）
        ▼
falcon-mcp-gateway APK
  McpDispatcher：会话、目录、参数、scope、座位校验
  McpHttpServer：Bearer 认证、拒绝浏览器 Origin、有界请求与执行线程
        │ FalconToolClient / callTo(明确包名与进程)
        ▼
falcon-cross-server APK 的 :server 进程
  DemoVehicleRoutes → DemoVehicle
  模拟家庭目的地、导航、空调、媒体及持久化操作记录
```

| 模块 | 职责 |
| --- | --- |
| `falcon-capability` | 纯 Kotlin 的 10 个工具定义、输入/输出 JSON Schema、校验、错误与客户端接口 |
| `falcon-agent-runtime` | DeepSeek 多轮 tool_calls、远端 Schema 校验、执行策略、MCP HTTP 客户端；保留离线 Mock |
| `falcon-mcp-adapters` | JSON ↔ 平台 Bundle 编码、Falcon 定向调用、模拟业务及 route 注册 |
| `falcon-mcp-gateway` | 独立 Android 网关 APK、认证、MCP 服务及同签名凭据交换 |
| `falcon-agent-demo` | 文本输入、执行与中断、进度和结果展示、最近150条任务事件 |

`falcon-core` 新增 `DynamicTarget`、`callTo()`、`dynamicProviderCapabilities()`。
原有 `call()` 签名保持不变。定向调用不会退回同名本地服务或其他供应商，连接缓存校验连接实例。
`callTo()`在`timeout.connectMs`预算内等待目标连接与兼容路由就绪；未就绪时每100ms探测、每500ms重新发现，期间不下发业务调用。
Gateway配置就绪预算5秒，Binder调用看门狗8秒，MCP客户端HTTP读取超时20秒。就绪预算是协程截止时间，已进入的阻塞Binder探测仍受其看门狗约束。
就绪超时返回`PROVIDER_NOT_READY`，明确本次没有下发操作；发出业务调用后的连接错误仍按未知结果处理，不自动重发。
注册表的信任配置初始化、缓存失效及签名校验串行执行，防止冷启动时旧配置的拒绝缓存污染新配置。
Cross Server 改为依赖仓库内的 core/KSP，以便测试当前代码；原有 cross-client 未改动。

## 构建与运行

```bash
./gradlew :falcon-agent-demo:assembleDebug :falcon-mcp-gateway:assembleDebug :falcon-cross-server:assembleDebug
adb install -r falcon-cross-server/build/outputs/apk/debug/falcon-cross-server-debug.apk
adb install -r falcon-mcp-gateway/build/outputs/apk/debug/falcon-mcp-gateway-debug.apk
adb install -r falcon-agent-demo/build/outputs/apk/debug/falcon-agent-demo-debug.apk
adb shell am start -n com.falcon.cross.server/.ServerActivity
adb shell am start -n com.falcon.mcp.gateway/.GatewayActivity
adb shell am start -n com.falcon.agent.demo/.AgentActivity
```

在 Agent Demo 中点击“执行场景”，默认输入：

> 我要回家，有点冷，放点轻松的音乐

Debug APK支持通过Intent仅预填测试文本（不会自动执行、不改变模型选择；Release忽略该参数）：

```bash
adb shell am start -S -n com.falcon.agent.demo/.AgentActivity \
  --es test_utterance '只放音乐，不导航'
```

然后确认DeepSeek开关已开启，点击“执行场景”。模拟器如使用非默认用户，给命令添加对应的`--user`参数。

首次运行的模拟初始温度为22℃，成功后显示：

> 已开始导航回家；主驾空调目标温度已调到23.0℃；正在播放轻松音乐

业务状态保存在 Server 私有 SharedPreferences。再次运行会从现有设定温度继续调整，最高30℃。
“中断任务”会停止后续编排；已经下发的动作可能仍完成，不声称取消即撤销。
关闭/重建 Activity 会取消当前编排，不自动重放写操作。任务事件用于诊断，尚不提供跨进程死亡的自动任务恢复。

## MCP 工具与契约

完整且可执行的 Schema 位于 `falcon-capability/.../Tools.kt`，通过 `tools/list` 返回。
真实模型请求中的完整 `tools` 数组见 [deepseek-tool-definitions.json](deepseek-tool-definitions.json)，从模拟器实际请求提取，仅包含公开工具契约。
所有输入均拒绝未知字段，数值不能用字符串代替，引用长度最多256字符，搜索最多5项。

| 工具 | 输入 | 结果 | Falcon route / scope |
| --- | --- | --- | --- |
| `profile_resolve_place` | `label=home` | `destinationRef, displayName` | `profile/place/resolve` / `profile.read` |
| `navigation_plan_route` | `destinationRef` | `routeId, durationSeconds, distanceMeters, expiresAt` | `navigation/route/plan` / `navigation.read` |
| `navigation_start` | `routeId` | 操作凭据、导航会话 | `navigation/session/start` / `navigation.write` |
| `navigation_get_state` | `{}` | 状态、当前路线与会话 | `navigation/session/get` / `navigation.read` |
| `climate_get_state` | `zone` | 目标温度、范围、步长、版本、观测时间 | `climate/state/get` / `climate.read` |
| `climate_set_temperature` | `zone, targetTemperatureC, expectedStateVersion` | 操作凭据、新目标温度与版本 | `climate/temperature/set` / `climate.write` |
| `media_search` | `mood=relaxing, limit` | `items[]`：引用、标题、可播放/付费标志 | `media/content/search` / `media.read` |
| `media_play` | `contentRef` | 操作凭据、播放会话 | `media/playback/play` / `media.write` |
| `media_get_state` | `{}` | 当前播放状态、内容及会话 | `media/playback/get` / `media.read` |
| `operation_get` | `operationRef` | 操作状态 | `agent/operation/get` / `operation.read` |

操作凭据含 `operationRef, status`。当前模拟服务同步完成，返回 `succeeded`。
模型可在收到 `accepted` 后调用 `operation_get`，并必须读取实际状态后才允许结束。
本版 `operation_get` 是业务工具，没有声明 MCP Tasks 扩展。

写工具调用示例（Schema 为 MCP 标准字段；`_meta` 是本项目业务约定）：

```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "tools/call",
  "params": {
    "name": "climate_set_temperature",
    "arguments": {
      "zone": "driver",
      "targetTemperatureC": 23,
      "expectedStateVersion": 1
    },
    "_meta": {
      "falcon/workflowId": "a55aba41-eda9-4d15-b605-65a89c8153e7",
      "falcon/actionId": "climate_set"
    }
  }
}
```

`workflowId/actionId` 必须是1–100字符的字母、数字、下划线或连字符。同一动作重试必须使用同一对ID。
`operation_get` 查询应沿用所属 workflowId；不能访问其他工作流的操作。
网关将身份固定为其认证主体，不接受模型传入的用户、提供方、权限或确认标记。
工具业务失败返回 `isError=true`，机器错误码位于 `_meta["falcon/errorCode"]`；协议错误使用 JSON-RPC error。

## Agent 自主工具调用

真实 DeepSeek 路径不再生成三个意图布尔值，也不调用固定三分支编排器：

```text
MCP initialize → tools/list（完整目录，可分页）
  → description + inputSchema 转成 DeepSeek tools[].function
  → DeepSeek(messages, tools, tool_choice=auto)
  → assistant.tool_calls：模型选择工具名、参数及调用顺序
  → 本地目录白名单 / Schema / 执行前置条件校验
  → MCP tools/call → Gateway → Falcon → 业务进程
  → 追加 assistant(tool_calls) + tool(tool_call_id, content=完整MCP结果)
  → 再请求 DeepSeek，直至 finish_reason=stop 且已提交动作经过状态核实
```

`ToolCallingAgent` 不依赖本地 `VehicleTools` 来构建目录。`HttpMcpToolClient.descriptors()` 返回服务器原始工具描述；
`inputSchema` 原样成为 `parameters`，`description` 附加远端 `outputSchema` 和 `annotations`，使模型也看到结果格式和副作用提示。
工具调用的 `id` 通过 `tool_call_id` 与结果匹配；网关使用的 `workflowId/actionId` 由宿主生成，不由模型决定。
同一轮内按照模型给出的顺序执行；下一轮才能利用本轮返回的真实引用，宿主不预填路线或内容参数。

`RemoteSchema` 校验本项目使用的 JSON Schema 子集：对象、必填字段、额外字段、数组、字符串、枚举、数值范围。
遇到不支持的约束会拒绝执行，不默默忽略。未知工具、参数错误、策略错误以 `isError=true` 反馈模型；业务错误原样回传。

`VehicleToolPolicy` 只做前置条件与结果核实，不安排执行步骤：

- 目的地、路线及可播放内容引用须来自本次读取；路线不能过期，不购买内容。
- 空调必须先读授权座位状态，使用原版本；本演示最多升温1℃并符合范围和步长。目标值由模型生成。
- 每种写操作每任务最多实际提交一次；重复的相同参数复用结果，未知结果不重发，版本冲突不覆盖手动调整。
- 写入后由模型主动读取状态；宿主核对温度/版本、路线/导航会话、内容/播放会话。模型过早结束时要求继续查询。
- 总计最多12轮、30次工具调用，每批最多8次，总协程预算120秒。阻塞HTTP另有连接/读取超时；取消时已发动作可能完成。

离线 Mock 仍使用 `HomeJourneyAgent`，仅用于测试、演示和回归对照。真实模型失败不会自动切换成 Mock。
目前未实现自动跨进程恢复、ASR/TTS、付费授权或完整驾驶安全策略。自然语言回答由模型生成；宿主核实已提交写操作，
并不能形式化证明模型已满足用户全部语义要求。状态持久化在模拟业务服务中，实际车辆适配器尚未接入。

## DeepSeek 接入与诊断

```kotlin
val transport = DeepSeekHttpTransport { credentialStore.readKey() }
val agent = ToolCallingAgent(ToolCallingModel(transport::chat), mcpToolClient, trace::record)
val result = agent.run(utterance, AgentSession("demo_driver", "driver"))
```

固定官方HTTPS端点，模型 `deepseek-flash`，`thinking.type=disabled`，`tool_choice=auto`，不使用JSON意图模式。
`ToolCallingModel` 可注入脚本响应进行离线多轮测试。保留旧 `IntentModel` 接口供旧测试使用，Demo真实模型入口不再引用它。
连接超时10秒，读取超时30秒，响应最大64KiB；禁用重定向，不重试可能包含动作的整轮任务。
Key通过界面保存到私有 `files/deepseek-key`，不进入源码/APK或诊断日志。模型调用发送当前输入、工具定义及工具结果。

每次点击执行会覆盖应用私有 `files/last-execution.jsonl`，用于开发验证：

- `mcp.tools.list`：完整远端工具目录。
- `deepseek.request`：每轮实际HTTP JSON请求体，包含system、tools、完整messages，不含认证头。
- `deepseek.response`：实际模型choice，包括tool_calls、参数和finish_reason。
- `agent.tool.*`：包括本地拒绝、去重结果在内的模型工具请求与反馈。
- `mcp.tools.call.*`：实际送入网关的调用、宿主动作ID和原始结果。
- `agent.result` / `agent.error`：最终结果或失败。

这些诊断数据会包含用户文本与业务引用，仅保留在禁用备份的Demo私有存储中；不要将原始运行日志提交仓库。

## 验证

```bash
./gradlew :falcon-capability:test :falcon-agent-runtime:test \
  :falcon-mcp-adapters:testDebugUnitTest :falcon-mcp-gateway:testDebugUnitTest \
  :falcon-core:testDebugUnitTest :falcon-cross-server:testDebugUnitTest
```

本次自主工具调用改造验证：

```bash
./gradlew :falcon-agent-runtime:test :falcon-mcp-gateway:testDebugUnitTest \
  :falcon-agent-demo:assembleDebug :falcon-agent-demo:lintDebug --console=plain
```

18项Runtime测试和6项Gateway测试全部通过，Demo构建及Lint通过。
本次新增10项Runtime测试覆盖远端新增工具、完整结果回传、错误参数修正、写入前置条件、重复写去重、
提前结束时要求状态核实、响应丢失不重试、截断/重复ID批次拒绝、业务错误、调用预算、版本冲突和播放会话核实。
原有测试继续覆盖HTTP会话/认证/Origin、座位与scope、模拟业务持久化及Falcon定向调用。

2026-11-01在Automotive模拟器完成真实DeepSeek端到端复验，同一句场景产生5轮模型请求、10次MCP调用。
模型自主选择的顺序为：读取家庭目的地/空调/音乐 → 规划路线/设置温度/播放 → 启动导航/核实空调与媒体 → 核实导航 → 最终回答。
核对了后续请求中的tool结果与MCP实际返回完全一致，三个写操作的参数及结果与Cross Server持久化记录一致。
Agent、Gateway、Cross Server的`:server`为三个独立进程。DeepSeek、HTTP MCP和Falcon Binder链路真实；车辆业务仍是模拟状态。

### 不同指令及失败场景的模拟器复验（2026-11-01）

所有用例使用真实DeepSeek，同一套system提示词及完整10工具目录，未按指令裁剪工具或强制工具选择。
校验后续模型请求确实包含MCP结果，并比较业务服务持久化状态，确认未请求的功能没有被写入。

| 输入/条件 | 模型轮次 / 实际工具调用数 | 观察到的调用及结果 |
| --- | --- | --- |
| 只放音乐，不导航 | 4 / 3 | media_search → media_play → media_get_state；导航和空调未改变 |
| 空调已经够暖了，带我回家 | 5 / 4 | profile_resolve_place → navigation_plan_route → navigation_start → navigation_get_state；空调和媒体未改变 |
| 只告诉我空调现在设了多少度，不要调整，也不要播放音乐 | 2 / 1 | 仅climate_get_state；没有写操作 |
| 不要导航，不要播放音乐，空调也别动 | 1 / 0 | 直接回答，不调用工具 |
| 带我回家，顺便放点轻松的音乐；模拟家庭地址未设置 | 4 / 4 | profile_resolve_place返回HOME_NOT_SET；media_search → media_play → media_get_state成功；没有规划/启动导航 |
| 重启后Falcon连接尚未就绪，提出同一复合请求 | 3 / 4 | 目的地读取和音乐搜索均返回TRANSPORT_ERROR；模型各重试一次只读查询后报告未完成，无写操作 |

家庭地址失败通过停止模拟业务服务、仅将持久化`homeSet`设为false并重启实现，不在Agent或MCP客户端伪造返回。
测试结束恢复原`homeSet`值，保留实际动作产生的业务状态。重启后应等待业务路由注册和Gateway连接到`:server`再开始业务错误测试，
否则先遇到的是连接错误。读取同一指令的重复测试记录时必须核对新`workflowId`，避免误读上次私有诊断文件。

这些结果证明上述输入会产生不同的工具选择和调用数量；不代表已完成所有自然语言组合或车辆安全场景的验证。
当前没有停止导航、停止音乐或任意地址输入工具。“不要导航/播放”用例验证的是没有新增对应动作，原有会话保持不变。
后续修复已在远端工具说明、HOME_NOT_SET错误返回和模型system提示词中明确：当前不支持聊天输入具体地址，
家庭地点缺失时仅提示用户先在车机设置中保存家庭地址后重试。连接故障只能建议稍后重试，不能用具体地址输入替代。
这些提示不会新增任意地址搜索、地理编码或保存地址能力。

### 就绪等待与地址能力提示修复

定向调用先等待连接和兼容路由；只允许重新发现与只读能力探测，不重发已下发的业务操作。
注册表并发初始化签名缓存的竞态已修复：旧信任配置下的验证不能在新配置清空缓存后重新写入旧拒绝结果。
签名校验和可信证书白名单保持启用。

回归覆盖连接/路由延迟、等待超时、取消、响应丢失不重试、签名缓存并发更新，以及`PROVIDER_NOT_READY`经MCP和Agent传递。
本轮Core 102项、Runtime 19项、Adapters 12项、Gateway 7项测试通过；三个APK构建及Agent/Gateway Lint通过。

模拟器实测：启动三个APK后不由脚本等待Falcon连接，导航加音乐完成7次工具调用；家庭地址缺失的组合指令返回HOME_NOT_SET，
音乐继续成功，模型仅提示先在车机设置中保存家庭地址。额外短暂停止Debug业务进程后，Gateway日志先出现`Waiting for provider`，
恢复进程后出现`Provider ready`，两条日志相隔约618ms；此后模型完成导航，服务端只持久化一次导航写操作，空调温度与版本未改变。
用户主动询问“家还没保存，我可以把具体地址告诉你吗？”时，模型一轮直接回答“无法通过对话录入或保存地址，请先在车机设置中保存家庭地址”，没有工具调用或业务写入。
本轮测试已恢复模拟家庭地址设置和业务进程运行状态。Demo仍按构建运行章节启动三个APK，不要求测试脚本人为等待连接就绪。

协议参考：[Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)、
[Transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、
[Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)。

只读模型评估题见 `vehicle-agent-evaluation.xml`（默认模拟数据集）。已提供10道题及预期答案，未接真实模型执行评估。
