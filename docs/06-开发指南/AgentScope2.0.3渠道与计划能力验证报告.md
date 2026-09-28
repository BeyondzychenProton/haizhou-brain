# AgentScope Java 2.0.3 渠道与计划能力验证报告

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-28 |
| 验证对象 | AgentScope Java 2.0.3（`agentscope-core`、`agentscope-harness`，本地 Maven 仓库实得 jar） |
| 对应设计 | [AgentScope 服务示例与实时交互改造方案](../04-渠道与执行/AgentScope服务示例与实时交互改造方案.md) 的 §6 P0 与 §7 决策项 |
| 验证方法 | ① 反汇编 jar 公开签名与常量；② 可运行契约探针 `AgentScopeGatewayContractTest`（10 用例，BUILD SUCCESS） |
| 结论 | 官方示例的**渠道、会话键、忙时、计划、团队、交付**抽象在 Java 2.0.3 已具备，P0 不需要先自研；但有 3 条接入红线必须显式配置 |
| 边界 | 本报告证明"框架具备该能力"，不证明"平台已完成接入"；端到端链路未验证 |

## 一、验证方法

两条互相独立的证据链，避免只看符号就下结论：

1. **符号核对**：对 2.0.3 的 jar 反汇编公开签名与常量，确认抽象存在、形态如何、默认值是什么。
2. **可运行契约探针**：`haizhuo-brain-runtime-agentscope/src/test/java/com/haizhuo/brain/runtime/agentscope/gateway/AgentScopeGatewayContractTest.java`，10 个用例全部通过，只依赖确定性行为，不调用模型、不访问网络。因此可随 `mvn test` 长期回归。

探针中有 2 个用例是在**实测推翻初始假设后**改成记录真实行为的，这两条正是最有价值的结论（见第四节）。

## 二、能力核对表

| P0 待核对项 | 框架对应物（2.0.3 实测存在） | 判定 |
| --- | --- | --- |
| 渠道无关入站/出站边界 | `gateway.channel.Channel`：`dispatch(InboundMessage)` / `dispatchStream` / `deliver(OutboundAddress, List<Msg>)` / `init·start·stop` | 直接实现接口即可，**薄适配** |
| 渠道注册与生命周期 | `ChannelManager`：`register / unregister / getChannel / channelIds / initAll / startAll / stopAll / deliver` | 直接复用 |
| 渠道装配入口 | `GatewayBootstrap.builder()` → `gateway() / channelManager() / agents() / mainAgentId() / gatewayBridge() / chatUiChannel()` | 直接复用 |
| 出站投递（outbox） | `Channel.deliver(...)`；内置 `ChatUiChannel` 以 `pollOutbound()` + `outboundQueueSize()` 暴露队列 | 复用；平台 outbox 与渠道队列对接 |
| 路由与 Agent 归属 | `ChannelRouter.resolveRoute(ChannelConfig, InboundMessage)` → `RouteResult{agentId, context, matchedBy, outboundAddress}`；`ChannelBinding.forPeer/forGuild/forTeam/forAccount/forChannel` | 直接复用；绑定表由平台配置 |
| 可信身份注入 | `ChannelRuntimeContextResolver.resolve(ChannelRuntimeContextRequest)`；`InboundMessage.withRuntimeContext(RuntimeContext)`；`RuntimeContext.builder().userId(...).sessionId(...)` | 平台在此处注入可信身份，**渠道自报 senderId 不可直接当平台用户** |
| 渠道会话键映射 | `SessionIdUtils.deterministicHash(String...)` = `join("/")` 后取 SHA-256 前 6 字节 hex（12 位小写）；`HarnessGateway#generateSessionId` 拼 `gw-` 前缀 | 平台**可复算**用于映射，但不可指定（见红线 3） |
| 会话粒度隔离 | `DmScope`：`MAIN / PER_PEER / PER_CHANNEL_PEER / PER_ACCOUNT_CHANNEL_PEER`；`ChannelConfig.builder(...).dmScope(...)` | 必须显式配置（见红线 1） |
| 忙时策略 | `SessionTurnGate.acquire(sessionId)` → `TurnLease`；`LocalSessionTurnGate`；`StoreBackedPeriodicGate`；`TurnBusyException.getGateKey()`；网关捕获 busy 后记录 "Skipping turn/stream … gate busy" | 接口可替换（见红线 2） |
| 渠道会话 ↔ 平台会话绑定 | `HarnessGateway.registerExternalSession(sessionId, ...)`、`isSessionRunning(sessionId)`、`deliverToSession(sessionId, msgs)`、`runWakeup(sessionId)` | 直接复用，这是 Run 归属的落点 |
| 计划能力 | `workspace.plan.PlanModeManager`：`isPlanActive / enter / exit / planFilePath / writePlan`；`tool.PlanModeTools`；`middleware.PlanModeMiddleware`；core 侧 `state.PlanModeContextState`、`state.TaskContextState`、`state.Task`、`tool.builtin.TodoTools` | 存在，但**计划落在 workspace 文件**，不是事件流（见第五节） |
| 子 Agent / 团队 | `SubagentRegistry`（`register / find / revoke / revokeByParentSession`）、`HarnessGateway.exposeSubagent / revokeSubagent`、`SubagentGatewayBridge`、`subagent.SubagentFactory`、`team.LocalTeamClient`、`middleware.SubagentsMiddleware / TeamsMiddleware / DynamicSubagentsMiddleware`、`tool.AgentSpawnTool / TeamTool / WaitAsyncResultsTool` | 直接复用；平台收敛角色与最小工具集 |
| 父子事件协议 | `subagent.protocol.RemoteEventType`：`RUN_STARTED / RUN_FINISHED / RUN_ERROR / TEXT_DELTA / THINKING_DELTA / TOOL_CALL_START / TOOL_CALL_END / TOOL_RESULT / REQUIRE_CONFIRM / STATUS / AGENT_EVENT` | 覆盖进度、工具与**审批**，可直接映射到平台事件 |
| 事件持久化与回放 | `bus.MessageBus`（`queuePush/Drain`、`logAppend/Read/Trim`、`publish/subscribe`）+ 会话事件日志 `sessionPublishEvent / sessionReadEvents / sessionSubscribeEvents / sessionTrimEvents`，键前缀 `agentscope:session:events:`，回放上限常量 `SESSION_REPLAY_MAX_LEN = 1000` | 存在，但与平台自研投影重叠（见第五节） |
| 交付物边界 | `artifact.ArtifactDeliveryRequest / Result / Target` | 存在；渠道侧交付语义按 P3 再定 |
| 多实例 | `agent.DistributedStore`、`coordination.StoreBackedPeriodicGate`、`gateway.StoreBackedSubagentRegistry`；默认 `bus.WorkspaceMessageBus(AbstractFilesystem, String)` | 存储后端可换；默认总线基于文件系统，**不跨进程** |

## 三、关键契约速查（接入时直接照这个写）

```java
// 1. 装配
ChannelManager channels = new ChannelManager();
channels.register(myChannel);                       // myChannel implements Channel
HarnessGateway gateway = HarnessGateway.create(channels);
gateway.bindMainAgent(mainHarnessAgent);
gateway.setSessionTurnGate(myTurnGate);             // 忙时策略可替换
gateway.setRuntimeContextResolver(myResolver);      // 可信身份注入点
channels.startAll();

// 2. 入站（渠道侧）
InboundMessage inbound = InboundMessage.dmFor(channelId, accountId, senderId, messages);
RouteResult route = new ChannelRouter(mainAgentId).resolveRoute(config, inbound);
gateway.runStream(route.context(), inbound.messages(), route.outboundAddress());

// 3. 出站（渠道侧实现 deliver，或让平台 outbox 拉取）
void deliver(OutboundAddress address, List<Msg> messages) { /* 写 outbox 或直接投递 */ }

// 4. 会话键复算（平台做映射用）
String frameworkSessionId = "gw-" + SessionIdUtils.deterministicHash(channelId, senderId);
```

## 四、三条接入红线（实测得出，不是推测）

### 红线 1：默认 `DmScope` 是 `MAIN`，同渠道所有对端共用一个会话

`DmScope.defaultScope()` 返回 `DmScope.MAIN`。探针 `defaultChannelScopeSharesOneSessionAcrossPeers` 记录：`ChannelConfig.of(channelId, agentId)` 下，`user-1` 与 `user-2` 的 `MsgContext.canonicalKey()` **完全相同**（实测输出形如 `chatui|x:agentId=haizhuo-main`）。

后果：直接上线会串号——两个用户看到同一段对话历史。
处置：接入任何面向多用户的渠道时，**必须**显式 `ChannelConfig.builder(id).dmScope(DmScope.PER_PEER)`（或 `PER_CHANNEL_PEER` / `PER_ACCOUNT_CHANNEL_PEER`）。探针 `perPeerScopeSeparatesPeersAndKeepsSamePeerStable` 验证显式设置后不同对端隔离、同对端稳定。

### 红线 2：`LocalSessionTurnGate` 是阻塞排队，不是快速失败

`SessionTurnGate.acquire` 声明 `throws TurnBusyException`，但**默认实现 `LocalSessionTurnGate` 在第二个并发轮次上阻塞等待**（探针 `localTurnGateQueuesConcurrentTurnInsteadOfRejecting`：并发 acquire 在 200ms 内不返回，释放租约后立即获得）。`TurnBusyException` 留给共享/非本地实现去抛。

后果：如果直接沿用默认实现，"当前会话正在运行"这个用户可见状态永远不会出现，请求会静默排队；而 `HarnessGateway` 只会对抛出的 `TurnBusyException` 记 "gate busy" 并跳过本轮。
处置：平台若要与现有 Run 忙时策略（409 / 排队提示）对齐，**必须自己实现 `SessionTurnGate`**（例如基于平台 Run 状态快速失败并抛 `TurnBusyException(gateKey)`），或明确接受框架的排队语义。

### 红线 3：渠道会话键由框架派生，平台可复算但不可指定

`HarnessGateway#generateSessionId` = `"gw-" + SessionIdUtils.deterministicHash(key)`，hash 为 `join("/")` 后 SHA-256 取前 6 字节 hex（12 位）。探针 `frameworkSessionIdIsDerivedFromStableHashOfChannelKeys` 验证其稳定性与区分度。

后果：平台不能指定渠道会话键；但可以**复算**它，从而建立 `平台 Session ↔ gw- 会话` 的映射。若需要平台主导绑定，则用 `HarnessGateway.registerExternalSession(...)` 显式注册，而不是去猜键值。

## 五、对后续阶段的影响

| 阶段 | 原计划 | 验证后的修正 |
| --- | --- | --- |
| **P2 `plan.snapshot`** | 推计划快照 | 计划能力存在，但 `PlanModeManager` 的产物是 **workspace 文件**（`planFilePath` / `writePlan`），不是事件流。要得到 `plan.snapshot` 必须从两条路径之一派生：监听计划文件变化，或翻译 `PlanModeTools`/`TodoTools` 的工具调用事件。**建议选后者**，可与现有事件投影同事务，避免文件监听的不确定性。 |
| **P2 会话游标** | 自研 `sessionCursor` 投影（已交付 V14） | 框架 `MessageBus` 自带会话事件日志（曾考虑改用它）。**结论：维持平台自研表为唯一事实源**，理由有三：① 框架日志是"回放窗口"语义（上限 1000），不承担审计；② 平台投影与 Run 事件同事务写入，一致性更强；③ 引两套事实源必然产生重复消息与不一致。框架日志只在 P5 需要跨实例通知时作为通道候选。 |
| **P3 首个外部渠道** | 需先验证框架是否支持 | `Channel` + `ChannelManager` + `GatewayBootstrap` + 出站 `deliver`/`pollOutbound` 齐备，**直接实现接口即可，无需自研网关**。剩余工作集中在平台侧：渠道绑定表、入站去重、身份绑定、Delivery outbox。 |
| **P4 受控团队** | 需自建父子关联与最小工具集 | `exposeSubagent` + `SubagentRegistry`（含 `revokeByParentSession`）+ `RemoteEventType`（含 `REQUIRE_CONFIRM`/`STATUS`）已覆盖父子关联、独立状态与审批。平台只需收敛可委派角色、最小工具集与审计维度。 |
| **P5 多实例** | 需引入共享通知 | 默认 `WorkspaceMessageBus(AbstractFilesystem, ...)` 基于文件系统，**不跨进程**；`DistributedStore` / `StoreBackedSubagentRegistry` / `StoreBackedPeriodicGate` 说明框架留了持久化后端位。P5 的具体动作是：实现 `MessageBus` 接口对接共享存储（Redis/JDBC），并把 `SessionTurnGate`、`SubagentRegistry` 换成 store-backed 实现。 |

## 六、已验证 / 未验证

**已验证（可回归）**：渠道会话键派生规则、身份注入路径、路由与会话粒度、忙时门语义、出站地址序列化往返、渠道注册与投递链路、子 Agent 协议事件覆盖、关键抽象在 classpath 上的存在性与网关方法面。

**未验证（需要运行环境或凭据）**：

- 渠道 → Agent → 出站 的真实端到端链路（需要模型凭据）。
- 取消与审批在真实运行中的完整闭环（仅确认了协议与工具的存在）。
- 事件持久化与连接恢复的运行时行为（`MessageBus` 会话日志仅做符号级核对）。
- `GatewayBootstrap` 的完整装配（需要 `HarnessAgent` 与 workspace）。

## 七、仍需人工决策

| 决策项 | 影响 | 建议默认 |
| --- | --- | --- |
| 渠道会话粒度选哪个 `DmScope` | 直接决定用户隔离与串号风险 | `PER_PEER`（私聊场景），群聊另议 |
| 忙时策略：排队 or 快速失败 | 决定 `SessionTurnGate` 是否需自研 | 快速失败并复用现有 409 语义 |
| `plan.snapshot` 派生方式 | 决定 P2 剩余项的实现路径 | 从工具调用事件派生 |
| 首个外部渠道提供方 | 决定 P3 是否可启动 | 待定，需业务指定 |
| 是否允许为验证引入最小接入骨架 | 决定 P0 是否继续推进到端到端 | 允许（否则 P3 无前置） |

## 八、复现方式

```bash
JAVA_HOME=C:/Users/shanH/.jdks/corretto-18.0.2 \
  <maven-3.9.16>/bin/mvn -f pom.xml \
  -pl haizhuo-brain-runtime-agentscope -am -Denforcer.skip=true \
  -Dtest=AgentScopeGatewayContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

注意：`-DfailIfNoSpecifiedTests=false` 在 surefire 3.5.x 上**不生效**，必须用 `-Dsurefire.failIfNoSpecifiedTests=false`，否则上游无匹配测试的模块会直接让构建失败。

## 变更记录

| 日期 | 进展 |
| --- | --- |
| 2026-09-28 | 完成 P0 框架能力验证：符号核对 + 10 用例契约探针；确认渠道/计划/团队/交付抽象齐备，给出 3 条接入红线与对 P2–P5 的修正结论 |
