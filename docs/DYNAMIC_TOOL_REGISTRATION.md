# Falcon 动态工具注册：任务间发现与执行

本轮将 MCP 工具目录从 Gateway 的 `VehicleTools.all` 移到业务应用。外部应用运行时注册工具定义和处理函数，Gateway 在每次 `tools/list` 时重新发现并拉取在线提供方目录，Agent 在新任务开始时获取这份目录。新增工具不需要修改宿主的业务工具列表或模型提示词。

[Archify 流程图](diagrams/dynamic-tools.html) · [图表浏览器截图](diagrams/dynamic-tools.visual-check.html) · [校验回执](diagrams/dynamic-tools.handoff.json) · [实际发送给模型的新工具定义](dynamic-tool-definition.json)

## 已实现的边界

- 本轮先升级通用注册、发现和调用引擎。此后验收过程中保持 Gateway/Agent APK 不变，只安装和操作独立提供方；不是声称改造前的旧 APK 已经支持动态目录。
- 动态内容包括：工具名称、说明、输入输出 Schema、注销、目录版本、处理函数绑定和提供方目标。Gateway 生产路径不再引用 `VehicleTools` 或 `FalconToolClient`。
- 应用身份、证书、允许的包名、命名空间、scope 和策略 profile 仍是车端准入配置，不由工具提供方自报授权。当前 Demo 在 Gateway 初始化时预配置两个已准入应用；不支持自动接纳任意未知 APK，新增未准入供应商仍需更新准入配置及包可见性。
- 当前实现 `read.v1`，可动态扩展只读工具；原有车辆写工具使用 `vehicle.demo.v1` 并保留宿主专用策略、服务端幂等及写后核实。任意新车控写工具还需实现和准入对应策略 profile，不能仅凭 `readOnlyHint` 获得写权限。本轮新增案例是只读工具。
- 任务中不主动推送目录变化，MCP `listChanged=false`；尚未实现 SSE、变更 topic 或目录分页。上限为每提供方32工具、聚合64工具，并有目录字节大小限制。

## 模块与调用协议

| 模块 | 职责 |
| --- | --- |
| `falcon-capability` | `PublishedTool`、契约哈希、受支持的 Schema 子集与统一校验器 |
| `falcon-mcp-provider`（新增） | Provider SDK、线程安全工具注册表、目录查询、版本绑定调用、Bundle/JSON转换；不依赖模拟车辆业务 |
| `falcon-comfort-provider`（新增独立 APK） | 运行时注册/注销空气质量查询，返回模拟读数；不依赖 Gateway 或 Agent 模块 |
| `falcon-mcp-gateway` | 发现带提供方身份的能力，校验目录、应用准入及 scope，生成任务快照，按绑定执行 |
| `falcon-agent-runtime` | 从远端目录构造模型工具；支持零工具目录；沿用通用多轮循环 |
| `falcon-cross-server` | 将原有13个车辆工具注册到 Provider SDK，保留原业务处理与状态持久化 |

支持的Schema子集为object/array/string/boolean/number/integer，以及properties、required、additionalProperties=false、enum、minimum/maximum、maxLength、maxItems和items；不支持的约束在注册/目录准入时拒绝，不会默默忽略。

SDK 在当前进程注册两个固定 Falcon route，协议版本1：

| route | 请求/响应 |
| --- | --- |
| `falcon/mcp/catalog/get` | 返回 `catalogJson` 字符串，其 JSON 包含 `revision` 和 `tools[]`；每项为 descriptor、scope、profile |
| `falcon/mcp/tools/invoke` | 请求含本地 name、revision、contractHash、arguments；身份和工作流来自 Gateway headers；返回经过输出 Schema 校验的业务结果 |

目录中的 JSON Schema 可能比普通业务数据嵌套更深，因此目录用有界 JSON 文本装进 Bundle，避免套用原有业务 JSON 编码的8层限制；调用参数和结果继续使用显式 Bundle 编码。目录最大50KB，SDK注册集合最大48KB，Gateway聚合公开描述最大48KB，MCP客户端响应上限64KB。

## 外部应用如何注册

同仓库可用 `implementation(project(":falcon-mcp-provider"))`；外部工程需将core、capability、provider模块发布到内部Maven并保留传递依赖，目前没有发布新的公共SDK版本。

应用依赖 `falcon-mcp-provider`，初始化 Falcon 并配置 Gateway 证书信任，在该业务进程声明注册 Provider。完整 Manifest 和签名示例见 `falcon-comfort-provider`；该 APK 与 Gateway 使用不同证书，Demo复用已有供应商证书。

下面的接口已在本仓库实现：

```kotlin
val provider = FalconMcpProvider(falcon, "com.falcon.mcp.gateway")

val input = Field("object", "查询区域", properties = mapOf(
    "zone" to Field("string", "授权座位", setOf("driver", "front_passenger"))
))
val output = Field("object", "查询结果", properties = mapOf(
    "co2Ppm" to Field("integer", "CO2浓度ppm")
))
val descriptor = obj(
    "name" to str("air_quality"),
    "description" to str("查询座舱CO2浓度，只读取，不调整空调。"),
    "inputSchema" to input.schema(),
    "outputSchema" to output.schema(),
    "annotations" to obj("readOnlyHint" to bool(true))
)
provider.registry.registerTool(PublishedTool(descriptor, "comfort.read", ToolProtocol.READ)) { args, context ->
    if (args.text("zone") != context.zone) throw ToolException("FORBIDDEN", "座位未授权")
    obj("co2Ppm" to num(1450)) // 替换成自己的业务实现
}

// 同一进程内热注销；不需要重启 Gateway。
provider.registry.unregisterTool("air_quality")
```

Provider SDK 校验 Gateway 的 Binder 包名与进程名，Falcon 验证签名。业务处理函数仍负责用户授权及设备业务约束。Scope、Schema等只是契约，不能证明实现没有副作用；需要信任并准入提供方。

独立 Demo 的真实注册函数还返回 `zone, pm25, advice`，UI提供“注册空气质量工具”和“注销空气质量工具”两个按钮。注册状态保存在提供方私有配置中，重启可重新注册；调用计数用于验证实际执行了提供方处理函数。

Gateway 为这个已准入应用分配 `comfort_` 命名空间，因此模型看到 `comfort_air_quality`。这个工具名与说明仅定义在独立应用，不存在于 Gateway 或 Agent 的业务工具分支中。

## 发现、更新与失效

1. 新任务调用 MCP `tools/list`。Gateway 通过 `refreshPeerDiscovery()` 对已准入包重新发现，并读取 `dynamicProviderCapabilities()`，保留包名/进程目标。
2. 仅拉取公开目录 route 的在线应用。校验 Schema、工具名、scope、profile及大小；每个提供方原子入表，失败时不沿用它的旧目录。
3. 为每个公开工具绑定提供方 `DynamicTarget`、本地工具名、目录 revision 和契约 hash；命名冲突拒绝后进入的整个提供方目录，不覆盖已有条目。
4. 当前 MCP 会话的 `tools/list` 固定一份调用绑定。当前Agent每个任务创建独立会话，不支持在同一会话并发多个任务。Agent发送真实远端 descriptor 对应的说明与参数规范，`tool_choice=auto`。
5. 调用时 Gateway 和 Provider 再验证参数及输出；Provider 检查 revision 和 hash。修订、注销、重新注册或进程重启后，旧绑定返回 `TOOL_CHANGED`，不得静默执行替换后的实现。
6. 注销后下一次任务获取空提供方目录；进程离线后 Binder死亡移除连接；force-stop 包不会被任务发现或重连流程主动唤醒。重新打开应用，下一次任务恢复发现。

注册/注销与取执行器在同一把锁内完成。已经取得执行器的在途调用可以结束；注销不承诺撤销已经开始的动作。目录只在任务边界刷新，任务中收到变更错误应说明并在下一次任务重新获取目录，不重试结果未知的写动作。

本轮还修复 Android 的可选包发现：给尚未安装的 Provider 注册 ContentObserver 会抛 SecurityException。现在缺席包不会使宿主启动崩溃，任务刷新会重试安装后的观察和发现。

## 真实 DeepSeek 模拟器验收

2026-11-02，Automotive API 34、user 10，真实 `deepseek-flash`，每轮 `tool_choice=auto`。全部阶段使用同一句用户输入和同一 system，没有按用例修改模型提示词或强制函数：

> 只查询主驾座舱空气质量，告诉我二氧化碳和PM2.5读数并给通风建议。不要导航、放音乐或执行任何调节。

| 阶段 | 模型实际收到工具数 | 模型轮次 | 实际MCP调用 | 提供方效果 |
| --- | --- | --- | --- | --- |
| 独立APK尚未安装 | 13 | 1 | 无 | 明确缺少空气质量工具，无读数编造 |
| 后安装并点击注册 | 14 | 2 | `comfort_air_quality({"zone":"driver"})` | 处理函数计数+1 |
| 点击注销，保持提供方进程在线 | 13 | 1 | 无 | 不调用替代车控，无新增业务效果 |
| 点击重新注册 | 14 | 2 | 同上 | 处理函数计数+1 |
| force-stop提供方 | 13 | 1 | 无 | 工具下架，未自动唤醒应用 |
| 重新打开并注册 | 14 | 2 | 同上 | 处理函数计数+1，自动恢复 |

实际工具结果：

```json
{
  "zone": "driver",
  "co2Ppm": 1450,
  "pm25": 12,
  "advice": "模拟CO2偏高，可考虑通风；本次未执行通风操作。"
}
```

每次成功均为：第1轮模型选择工具 → Gateway按绑定经Falcon调用独立APK → 上述结果完整作为 `role=tool` 回传 → 第2轮模型说明读数与建议。验收逐项比较模型调用、MCP参数、处理函数计数和回传结果；所有阶段原有车辆持久化状态完全不变。

整个六阶段验收中 Gateway PID保持不变，已安装APK的SHA-256前后一致：

```text
Gateway 241d51942f681b17bb179e519159321612865e5f8417a67b5648ebea503269cd
Agent   1f64f5e1707778b1b1aba7962e16be838b73b7182f4365e069d56154570da53d
```

这些值对应本机验收构建产物，不要求其他机器重建得到相同APK哈希。验证后留在模拟器中的提供方处于已注册状态，便于直接体验。

目录、身份校验、跨APK Binder、HTTP MCP和DeepSeek均为真实链路；空气质量读数和车辆执行器仍是模拟。本次没有接真实传感器或执行通风。

## 构建与复验

```bash
./gradlew :falcon-core:testDebugUnitTest :falcon-capability:test \
  :falcon-agent-runtime:test :falcon-mcp-provider:testDebugUnitTest \
  :falcon-mcp-adapters:testDebugUnitTest :falcon-mcp-gateway:testDebugUnitTest \
  :falcon-cross-server:assembleDebug :falcon-comfort-provider:assembleDebug \
  :falcon-mcp-gateway:assembleDebug :falcon-agent-demo:assembleDebug \
  :falcon-mcp-gateway:lintDebug :falcon-agent-demo:lintDebug :falcon-comfort-provider:lintDebug
```

本轮测试：Core Debug 102、Capability 2、Runtime 22、Provider 4、Adapters 16、Gateway 9，共155项通过；另补跑Cross Server的16项生成代码与路由测试，总计171项通过。新增7项覆盖注册/注销/重注册、旧版本及进程重启失效、参数和输出错误、策略与Schema拒绝、任务间目录变化、权限过滤及空目录模型请求。

先安装并启动更新后的 Cross Server、Gateway、Agent；Agent私有模型Key沿用已有配置。保持独立提供方未安装，可覆盖“后安装发现”分支：

```bash
export ADB="$ANDROID_HOME/platform-tools/adb"
ANDROID_SERIAL=emulator-5554 ANDROID_USER=10 python3 scripts/verify_dynamic_tools.py
```

脚本仅安装/更新独立提供方，通过UI注册/注销并用force-stop模拟离线，逐次运行真实模型任务，检查宿主APK哈希及Gateway PID。如果应用已安装，首次阶段改为未注册基线。脚本最后使提供方保持注册；不读取模型Key，不将私有完整会话导出到仓库。

动态提供方路径下，旧充电场景也已用真实DeepSeek复验：低电量换站9轮/12调用、直接回家5轮/5调用、严格条件无解4轮/4调用，全部通过，业务写入与预期一致。

旧充电场景验收脚本 `scripts/verify_charging_agent.py` 也支持目录中存在额外工具，检查车辆基线工具契约/提示词和真实业务效果，不按聚合工具数固定为13；额外提供方可随在线状态变化。
