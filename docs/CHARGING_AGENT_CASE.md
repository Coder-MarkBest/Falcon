# 复杂场景实测：带约束的补能决策与故障换站

后续动态注册改造已将本案例的车辆工具迁到提供方目录；当前调用入口和复验说明见 [DYNAMIC_TOOL_REGISTRATION.md](DYNAMIC_TOOL_REGISTRATION.md)。以下保留首次充电案例验收记录。

本次将座舱 Agent 扩展到“先判断是否需要充电，再比较候选，处理站点失效，最后导航并播放”的条件任务。保留原有 Agent → MCP Gateway → Falcon Dynamic RPC → 业务应用链路，新增三个只读工具，复用已有导航、音乐和写后核实能力。

**真实部分**：DeepSeek HTTP 请求、多轮 tool_calls、MCP HTTP、跨 APK Binder、服务端持久化状态。**模拟部分**：电量、里程、价格、站点、导航及媒体执行器；没有真实地图、充电桩、付款或车辆控制接入。模型决定调用分支，应用没有为这些句子配置固定步骤。

[查看 Archify 执行流程图](diagrams/charging-agent.html) · [明暗主题截图](diagrams/charging-agent.visual-check.html) · [图表校验回执](diagrams/charging-agent.handoff.json) · [实际发送给模型的完整工具定义](deepseek-tool-definitions.json)

## 本次跑通的三个分支

2026-11-01，Automotive API 34 模拟器，真实 `deepseek-flash`，`thinking.type=disabled`，`tool_choice=auto`。三次使用同一份 system 和完整 **13 个工具**，没有按用例裁剪目录或强制选择函数。下表记录首次验收，模型再次执行时轮次和同批调用顺序可能不同。

| 场景 | 条件与行为 | 模型轮次 / MCP 次数 | 实际新增业务写入 |
| --- | --- | --- | --- |
| 低电量，首选站失效 | 24km 续航，回家 15km，保留 10km；功率≥120kW、绕路≤10min、排队≤10min；最便宜站离线后换站，再播放轻松音乐 | 9 / 12，约10.38s | `navigation_start` 到公园站、`media_play` 各一次 |
| 电量足够直接回家 | 120km 续航；15+10≤120，无需找站 | 5 / 5，约6.25s | 只调用一次 `navigation_start` 到家 |
| 严格条件找不到站 | 24km 续航；功率≥200kW、绕路≤2min、排队≤5min；搜索为空 | 4 / 4，约4.94s | 无；不降低条件，原有导航、音乐、空调不变 |

三次空调目标均保持27℃、版本6。第二次没有新增播放，第三次没有新增导航/播放；这些用例不表示停止之前正在运行的会话，当前没有停止工具。时间从首次模型请求到最终结果，不包含应用启动。

随后使用仓库中的验收脚本再次执行三个用例，全部通过：低电量分支10轮/12调用、直接回家5轮/5调用、无合规站4轮/4调用。第二次模型将音乐搜索放在导航之后，但最终效果和约束一致，说明验证不依赖固定调用顺序。

验收不仅检查自然语言答复，还比较了每轮 `role=tool` 与实际结果、同一套工具和提示词、写入参数与服务端去重记录、会话与最终状态。`STATION_OFFLINE` 由独立服务端真实返回，再经 Falcon/MCP 回到模型，不是客户端伪造的模型回复。

## 主案例的实际输入与逐轮调用

> 我要回家，电量不多。先判断能否保留10公里续航余量直接回家；如果不够，就找回家沿途功率至少120千瓦、绕路不超过10分钟、排队不超过10分钟的充电站，优先单价最低。首选离线就换其他符合条件的站。导航过去并放轻松音乐，空调别动，不要预约或付款。

模拟候选如下。搜索价格升序，实时检查时江畔站返回离线，这模拟“搜索快照与实时状态不一致”。

| 站点引用 | 功率 | 绕路 | 排队快照 | 单价（元/kWh） | 到站距离 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| `demo:charge:river` | 180kW | 4min | 0min | 0.8 | 8km | 搜索合规，实时离线 |
| `demo:charge:park` | 150kW | 7min | 5min | 1.2 | 12km | 搜索合规，实时可用 |
| `demo:charge:square` | 90kW | 3min | 0min | 0.6 | 5km | 功率不合规，不出现在搜索结果里 |

下表是实际观测的调用摘要。`homeRoute`、`parkRoute` 为阅读时替换的路线 UUID 别名；模型使用的是此前工具真实返回的 UUID，没有硬编码这些别名。

| DeepSeek 轮次 | 自主选择的工具与关键输入 | MCP 返回与下一步 |
| --- | --- | --- |
| 1 | `energy_get_state({})`；`profile_resolve_place({"label":"home"})` | 18%电量、估算24km、预留10km；`destinationRef=demo_driver:home` |
| 2 | `navigation_plan_route({"destinationRef":"demo_driver:home"})` | `homeRoute`，15000m，路线有效5min。24−15=9<10，未对这条路线启动导航 |
| 3 | `charging_search({"destinationRef":"demo_driver:home","maxDetourMinutes":10,"minPowerKw":120,"maxQueueMinutes":10})`；`media_search({"mood":"relaxing","limit":3})` | 返回江畔、公园两个站；返回可播放且无需购买的 `demo:relaxing` |
| 4 | `charging_check_station({"stationRef":"demo:charge:river"})` | `isError=true`，`_meta["falcon/errorCode"]="STATION_OFFLINE"` |
| 5 | `charging_check_station({"stationRef":"demo:charge:park"})` | `available=true`，实时排队5min，引用 `demo:charge:park`，检查有效60s |
| 6 | `navigation_plan_route({"destinationRef":"demo:charge:park"})` | `parkRoute`，12000m。12+10≤24，满足预留要求 |
| 7 | `navigation_start({"routeId":parkRoute})`；`media_play({"contentRef":"demo:relaxing"})` | 两个 `succeeded` 操作回执，以及不同的导航/播放会话ID |
| 8 | `navigation_get_state({})`；`media_get_state({})` | 导航 `active`、目标公园站、路线及会话吻合；音乐 `playing`、内容及会话吻合 |
| 9 | 无 tool_calls，输出最终答复 | 说明直接回家余量不足、首选离线、已改去公园站并播放；空调不变，未预约或付款 |

最终模型回复的核心内容为：“单价最低的江畔快充实时复核为离线，已跳过；改选公园快充……导航已确认生效……轻松歌单已开始播放。”全部写操作均有服务端状态佐证。

同一轮出现多个调用时，当前宿主按返回顺序逐个执行，不宣称并行执行。没有“每一轮必定调用某个工具”的业务状态机；下一轮取决于用户约束、远端工具说明以及上一轮结果。

另外两条实际调用路径：

- 续航充足：第1轮 energy + profile → 第2轮规划回家 → 第3轮启动导航 → 第4轮读取导航状态 → 第5轮回答。未调用充电站、音乐或空调工具。
- 搜索为空：第1轮 energy + profile → 第2轮规划回家 → 第3轮严格条件 charging_search，返回 `items=[]` → 第4轮回答。未检查虚构站点、未放宽条件、未新增任何写动作。

## 一次工具调用如何穿过各层

1. `HttpMcpToolClient` 初始化 MCP 会话并读取 `tools/list`。`ToolCallingAgent` 使用服务器返回的 description、inputSchema、outputSchema、annotations 构造 DeepSeek `tools[].function`；完整13工具定义已从模拟器实际请求中提取到仓库文档。
2. DeepSeek 返回 `assistant.tool_calls`，每个调用含唯一 `id`、函数名和 JSON 参数。宿主校验参数与本轮可用引用，生成 `workflowId/actionId`，不让模型填写调用者身份。
3. `VehicleToolPolicy` 检查已搜索候选、站点复核有效期、排队约束、续航余量、路线有效期，以及同一任务同一写功能最多提交一次；通过后调用 MCP `tools/call`。
4. Gateway 验证 bearer、scope、Schema，`FalconToolClient` 将 JSON 转为 Bundle，按工具 route 调用指定 Cross Server 的 `Falcon.callTo`。Falcon 在连接预算内等待连接与路由就绪，之后才发 Binder 请求。
5. Cross Server 的 `DemoVehicleRoutes` 将 Dynamic RPC 请求交给 `DemoVehicle`，在互斥锁内检查服务端状态，执行模拟动作，持久化操作回执、会话及幂等记录。充电站复核记录绑定当前 workflow，规划及启动充电站导航时再次检查。
6. 结果经 Binder → MCP 返回。错误通过 `isError` 和机器错误码保留；宿主将完整结果追加为对应 `tool_call_id` 的 `role=tool` 消息，再调用 DeepSeek。离线后选另一站是这一轮模型决策。
7. 写后必须读取实际状态。宿主确认目标、路线、会话或版本匹配后才清除待核实状态；模型不能仅凭“请求已受理”宣布任务完成。

## 新增工具与代码位置

| 工具 | 必填输入 | 关键输出 | scope / route |
| --- | --- | --- | --- |
| `energy_get_state` | `{}` | `socPercent, estimatedRangeKm, reserveKm, connector` | `energy.read` / `energy/state/get` |
| `charging_search` | `destinationRef, maxDetourMinutes(0..30), minPowerKw(0..350), maxQueueMinutes(0..60)` | 最多5个按价格升序的候选，含引用、名称、功率、排队、绕路、距离、单价和接口类型 | `charging.read` / `charging/stations/search` |
| `charging_check_station` | `stationRef` | `stationRef, destinationRef, available, queueMinutes, checkedAt, validUntil` | `charging.read` / `charging/stations/check` |

均为只读能力，不带预约/支付副作用；规划路线和实时复核可保存临时引用元数据。字段严格校验，拒绝额外字段。无需新建 Gradle 模块：

- `falcon-capability/Tools.kt`：13工具契约；导航支持已复核站点引用，导航状态增加可选目的地引用。
- `falcon-mcp-adapters/DemoVehicle.kt`：能源快照、站点过滤、离线故障、60s复核、路线目的地与workflow绑定。
- `falcon-agent-runtime/VehicleToolPolicy.kt`：候选来源、复核时效、排队变化、到站续航、路线里程执行前检查。
- `falcon-agent-runtime/ToolCallingAgent.kt`：补充能力边界与选择原则，继续使用原有通用多轮执行循环。
- Gateway 与 Cross Server 自动遍历目录提供路由和权限；无需为每个场景新增编排接口。
- `scripts/verify_charging_agent.py`：真实模型模拟器验收，检查工具结果回传和服务端持久化效果，finally恢复测试配置。

## 复验

```bash
./gradlew :falcon-capability:test :falcon-agent-runtime:test \
  :falcon-mcp-adapters:testDebugUnitTest :falcon-mcp-gateway:testDebugUnitTest \
  :falcon-agent-demo:assembleDebug :falcon-mcp-gateway:assembleDebug \
  :falcon-cross-server:assembleDebug :falcon-agent-demo:lintDebug :falcon-mcp-gateway:lintDebug
```

结果：Capability 2、Runtime 21、Adapters 16、Gateway 7，共46项通过；三个APK构建及两个应用Lint通过。新增6项测试覆盖缺少/过期复核、伪造引用、跨任务引用、实时排队变化、续航余量不足、无合规候选、离线换站及写入幂等。

先安装上述三个debug APK，并在Agent Demo中配置私有DeepSeek Key。当前Automotive模拟器使用user 10；脚本不会读取Key，通过界面走正常模型调用路径：

```bash
export ADB="$ANDROID_HOME/platform-tools/adb"
ANDROID_SERIAL=emulator-5554 ANDROID_USER=10 python3 scripts/verify_charging_agent.py
```

脚本会重启三个Demo应用，临时修改且最终恢复 `energy`、`firstStationOffline`、`homeSet` 三个模拟配置；保留实际导航/播放效果及动作记录。仅输出验收摘要，不把私有输入、完整执行日志或Key保存进仓库。模型选择、网络时延均有随机性，断言业务效果和约束，不固定调用轮数。异常中断/强制杀死脚本可能来不及运行finally，此时手动恢复fixture再复验。

默认 Demo 即低电量24km、预留10km、首选站离线；无需运行脚本也可直接在Agent输入主案例体验。高电量用例通过服务端模拟 `energy.estimatedRangeKm=120` 切换，模型及MCP目录保持不变。

## 边界与下一步落地场景

本次验证的是三条具体条件分支，不是任意自然语言都能可靠执行的保证。候选功率/绕路/价格仍是静态模拟值，实时检查目前模拟可用性、排队及过期；没有电耗模型、真实道路、排队预测或充电完成事件。10km是Demo设备给出的固定预留值，尚未实现任意用户预留值的独立策略编译。用户约束到工具参数的转换由模型完成，宿主校验的是提交的参数及返回值，不能声称已用确定性程序验证所有自然语言要求。

当前宿主充电策略使用Demo的站点引用约定；正式接其他供应商应改为显式类型/来源映射。服务端对充电站导航强制复核及续航检查；回家路线的余量检查在宿主已读取energy时生效，尚不是面向全部调用者的全局车辆策略。

后续值得落地的复杂场景如下，**这些尚未实现或验证**：

| 场景 | 用户表达举例 | 还需接入的能力与关键问题 |
| --- | --- | --- |
| 带到达时间的接人行程 | “六点前接到孩子，途中补电，不能晚到。” | 日历/授权地点、实时交通、充电时长、可行性计算；无解时展示取舍，不能擅改时间约束 |
| 多人座舱偏好协调 | “孩子睡着了，后排别吹风，我有点冷。” | 分区空调、乘员识别、座椅/音量；按席位权限控制，有冲突时先给方案 |
| 场馆停车与步行衔接 | “去看演出，找有空位且步行十分钟内的停车场。” | POI、车位实时状态、步行路线、价格；查询与付费预约拆开，支付单独确认 |
| 充电期间的时间安排 | “还要充二十分钟，找个来回来得及的咖啡店。” | 充电进度事件、到店/返回时间、营业状态；持续任务要有中断和提醒机制 |
| 行程中的降级恢复 | “原定站排队突然太长，保留余量换下一站。” | 事件订阅、任务恢复、取消/替换当前导航；明确旧任务与新任务的幂等边界 |

这些场景可继续复用本次的“查状态 → 带约束搜索 → 实时复核 → 写操作 → 状态确认”能力组合；具体顺序和分支仍由模型结合工具结果选择。
