# Harness 架构评估与缺口清单

| 项目 | 内容 |
| --- | --- |
| 评估日期 | 2026-09-28 |
| 评估对象 | haizhuo-brain 当前代码（AgentScope Java 2.0.3 宿主 + 平台治理层） |
| 评估框架 | H = 六层（E/T/C/S/L/V）+ 架构范式 P + 生产级横切关注点 |
| 证据来源 | 源码逐文件阅读、Flyway 迁移 V1–V18、`git diff` 工作区改动、GitNexus 代码图谱（5491 符号 / 15564 关系 / 461 执行流） |
| 结论性质 | 描述性评估，不改变任何代码行为；所有判断附证据位置，未证实项一律标注为未知区 |

---

## 0. 结论摘要

**当前状态是一句话可以概括的：横向骨架已相当完备，纵向价值出口尚未闭合。**

平台把「受理 → Run 排队 → 租约化执行 → 能力冻结与工具判权 → 事件持久化与会话游标 → SSE/轮询回放 → 渠道 outbox」这条主链做实并落到 V18，工程纪律明显高于同阶段项目。但存在三类结构性不足：

1. **六层中两层是空的**：C 上下文管理**完全没有**（无窗口预算、无压缩、无长期记忆、无知识库），V 评估接口**完全没有**（无评测、无坏例回流、无回归门禁）。E 层的迭代上限被硬编码为 1，且定义了上限常量却从未使用。
2. **一批能力是"形式落地"**：可观测性模块是 27 行死代码、工具调用审计表无写入方、资源级判权恒返回 true、框架 Channel/Gateway 未参与真实链路。这些在文档与探针上看起来"已接入"，实际无运行时作用。
3. **"验证了框架能力"被当成了"验证了平台接入"**：10 个契约探针中有 3 条结论（DmScope、LocalSessionTurnGate、gw- 会话键）在真实链路里没有消费者，因为 `PlatformChannel.dispatch` 从未被调用。

**最高杠杆的动作不是继续加能力，而是先补 V 层与可观测性，把 E/C 两层的"阉割"解开。**

---

## 1. 六层覆盖体检

| 层 | 现状 | 关键证据 | 判定 |
| --- | --- | --- | --- |
| **E 执行循环** | 单执行路径，委托 `HarnessAgent.streamEvents`；无 token 预算、无 run 级超时、无截止时间 | `AgentScopeRuntime.java:84-94`；全模块 `budget/timeout/deadline` 零命中 | 有缺口 |
| **T 工具注册** | schema-only 注册 + 挂起恢复外部执行 + 10 步判权流水线，是本项目最扎实的一层 | `ExternalToolSchemaAssembler.java:20-30`；`DefaultToolExecutionGatewayService.prepare:71-119` | 已落地（局部空壳） |
| **C 上下文管理** | 只有一次性桥接快照；无压缩、无摘要、无窗口预算、无长期记忆、无知识库 | `SessionBridgeMiddleware.java:19-26`；`HarnessAgentFactory.java:86-87` 显式 `disableMemoryHooks/disableMemoryTools` | **未建** |
| **S 状态存储** | 状态所有权清晰（平台 DB 唯一写入方 + 框架 AgentState），租约/fence/幂等完备，恢复有实证 | `JdbcAgentStateStoreVerificationTest.java:60-92`；`V11__run_execution_attempt.sql` | 已落地 |
| **L 生命周期钩子** | 5 个 `MiddlewareBase` 接缝 + 审批状态机 + SSE/REST/前端闭环 | `HarnessAgentFactory.java:75-79`；`JdbcToolApprovalRepository.java:67-108` | 已落地（缺防护栏） |
| **V 评估接口** | 无任何评测、独立验证、坏例回流、回归门禁代码 | 全模块 `eval/verif/validat/ground-truth` 零命中 | **未建** |

### 1.1 E 执行循环

| 发现 | 证据 | 影响 |
| --- | --- | --- |
| 迭代上限硬编码为 1 | `DefaultHarnessDefinitionBundleCompiler.java:41` `int maxIterations = MAX_ITERATIONS_FLOOR;`（=1，注释："草稿版本尚未携带迭代策略"） | 管理员无法配置；一次执行流内只有一轮推理，多步任务只能靠「挂起 → 恢复」跨 Run 推进，Token 与延迟成本成倍放大 |
| 上限常量定义了但从未使用 | `MAX_ITERATIONS_CEILING = 20` 仅在第 23 行定义，全仓无引用 | 说明原设计意图是可配置区间，实现中途截断 |
| 无 Token 预算、无 run 级超时 | 运行时模块无 `budget/deadline/timeout`；只有 worker 租约 TTL 120s（`RunWorkerProperties.leaseTtlSeconds`） | 租约续期覆盖"卡死"但不覆盖"烧钱"；单 Run 可无限轮次调用模型 |
| 取消只在推理边界生效 | `RunControlMiddleware.java:39-41`（类注释明确"工具执行途中不生效"） | 长工具调用期间用户点取消无响应，体验与预期不符 |

**未知区**：`maxIters=1` 在 AgentScope 2.0.3 的确切语义（是否允许"一次推理内多轮工具调用"）未做最小探针验证。建议补一个不调模型的确定性探针，再决定是调参还是改结构。

### 1.2 T 工具注册

做得好的部分（不应回退）：仅注册 Schema、执行权留在平台；`CapabilityViewMiddleware` 调用级收窄可见工具；执行器白名单（数据库值永不成为类加载入口）；拒绝时返回面向模型的安全文案而不泄漏规则。

缺口：

| 发现 | 证据 | 影响 |
| --- | --- | --- |
| 资源级判权是空壳 | `DefaultToolResourcePolicy.allows(...)` 恒返回 `true`，`deriveResource` 只回填 `businessAction` | 10 步流水线的第 6/7 步实际不产生约束；"资源维度授权"是目标设计而非已实现能力 |
| 逐调用审计未接线 | `recordToolInvocation` 仅存在于接口声明、JDBC 实现与测试；生产路径零调用方 | 审计表 `tool_invocation_audit`（V2）成为孤儿；出问题时无法回答"谁在什么时候用哪个参数调了什么" |
| 工具结果无内容安全校验 | 只有截断（`SdkMcpRemoteClient.java:77-83` 取 TextContent ×4，`substring(0,2000)`） | 外部返回内容直接进入模型上下文，是注入的主要入口 |
| MCP 差异视图只保留最近两条 | `JdbcMcpCatalogRepository:112` `LIMIT 2` | 更早的远端声明变化无历史视图，审计断链 |

### 1.3 C 上下文管理（最大空缺）

| 发现 | 证据 | 影响 |
| --- | --- | --- |
| 无任何压缩／摘要／裁剪／预算 | 运行时模块 `compact/summar/token/reset/truncat` 全零命中 | 长会话必然撞窗口上限，且没有降级策略与可观测的触发点 |
| 唯一的跨 Run 上下文是 8KB 截断快照 | `SessionBridgeService`：最近 20 条用户可见时间线、UTF-8 截断 8KB、`generation=1`、不引入 LLM 摘要 | 只解决"新绑定时的冷启动"，不解决"长会话的记忆连续性"；`stableBusinessFactsJson` 恒写 `"{}"` |
| 长期记忆被显式关闭 | `HarnessAgentFactory.java:86-87` `.disableMemoryHooks().disableMemoryTools()`；`HarnessTemplateKey` 把两项固化进策略哈希 | 明确的产品取舍（P0 不做），但意味着"个人助理型"体验不可能达成 |
| 知识库 / RAG 无代码无表 | `CapabilityType.KNOWLEDGE` 有枚举值，但 `DefaultHarnessDefinitionBundleCompiler` 只接受 `TOOL`/`MCP`；全仓 `embedding/vector/rag_` 零命中；workspace manifest `knowledge: []` | 企业知识入口缺失，数字员工只能靠 instructions 硬编码知识 |

### 1.4 S 状态存储

这是继 T 层之后第二扎实的一层，且有问题意识：

- 平台 DB 是唯一状态所有者，运行时"不触碰数据库"（`RunExecutionService` 类注释）——单一职责守得住。
- 认领用 `FOR UPDATE SKIP LOCKED`，落定用 `attempt_id + lease_token + fence_token + state='RUNNING'` 守卫，过期 worker 无法覆盖新 attempt。
- 事件写入与 Run 状态同事务，会话游标在 `platform_agent_session` 行锁下分配，避免游标空洞。
- 崩溃恢复链路完整：租约过期 → `LEASE_LOST` → Run 重新 `QUEUED` → 写 `RUN_REQUEUED_AFTER_LEASE_TIMEOUT`。

已知未闭合项：

| 发现 | 证据 | 影响 |
| --- | --- | --- |
| 工作区文件面与 snapshot 面未接线 | `AgentRuntimeConfiguration.java:63` 传 `filesystemSpec=null`；`V13` 注释声明 `agentscope_store`/`agentscope_snapshots` 故意未建 | 当前仅 AgentState 可恢复；附件、产物、文件态工作区无持久面 |
| 框架重建后会重复投递 ToolResultBlock | `HarnessP0IsolationTest.java:170-201` 断言 `deliveredResultBlocks==3`，注释"P1 needs a resume guard" | 未见 resume guard 实现；重复投递可能导致工具被重复执行（幂等键兜底，但不是所有执行器都有幂等保证） |
| 每会话至多一个活动 Run，但叙述是"允许排队" | V8 删除活动槽唯一索引，V10 又恢复；`RunState.active()` 含 `WAITING_TOOL/WAITING_CONFIRMATION` | `queuePosition` 恒为 1，"排队"产品语义与实现不一致 |

### 1.5 L 生命周期钩子

拦截点用真实 API 挂载（`MiddlewareBase` 的 `onAgent/onReasoning/onSystemPrompt`），顺序固定，`SecurityMiddleware` 以"无调用上下文即 `Flux.error`"做 fail-closed 第一道门——这是正确做法。

审批闭环也确实打通了：`WAITING_CONFIRMATION` → `ToolApproval(PENDING)` → REST 决策 → `APPROVED/DENIED` → Run 回到 `WAITING_TOOL` 或重新 `QUEUED`，前端有内联审批卡片，重复审批幂等返回 `decided=false`。

缺口集中在"防护栏"：

| 发现 | 证据 | 影响 |
| --- | --- | --- |
| 审批无超时 | `ToolApproval` 无过期字段，无超时扫描器 | **可把会话永久占死**：Run 停在 `WAITING_CONFIRMATION` 会占用会话唯一活动槽，用户不点就永远排队；第二天回来也无法继续 |
| `ApprovalState.CANCELLED` 定义了但无写入方 | `ApprovalState.java:5` | 取消路径缺失，与上一条同源 |
| 无 prompt injection 防御 | 全仓 `injection/越狱/jailbreak` 零命中 | 唯一的机制性防护是工具视图收窄；模型上下文里的外部内容（MCP 返回、渠道消息）无隔离 |
| 无并发／配额限制 | 全仓限流只有 `AuthenticationRateLimiter`（登录/激活）；创建 Run 无速率限制 | 单用户可无限创建 Run 消耗模型费用，无成本上限 |
| 子 Agent 能力被禁用 | `HarnessAgentFactory.java:91,95` `.disableSubagents().disableDynamicSubagents()` | 与"受控团队"目标设计之间没有桥；框架侧 `SubagentRegistry` 仅存在于契约探针 |

### 1.6 V 评估接口（完全空缺）

没有任何一层在回答"你怎么知道做对了"：

- 无评测集、无断言式验收、无 ground truth 对照。
- 无坏例（badcase）回流通道——线上失败只有 `failure_code` 落库，没有进入改进闭环。
- 无回归门禁：测试是单元/契约级，没有任何"同题多版本对比"或质量基线。
- 现有测试（13 个运行时测试类）全部确定性、不打真实模型——这是优点（可长期回归），但也意味着**没有一条测试证明"答案是对的"**。

按 `references/production-checklist.md`：终止条件必须区分"确定性校验"与"模型自评"，当前既没有确定性校验，也没有任何人对模型输出做独立验证。

### 1.7 架构范式 P

| 子维度 | 实际取值 | 评价 |
| --- | --- | --- |
| 扩展方式 | 嵌入式为主（执行模式固定）+ 插件化局部（`CapabilityExecutorRegistry` 白名单） | 执行模式无法增删替换，`MAX_ITERATIONS_CEILING` 弃用说明连"调参"路径都没走通 |
| 配置方式 | 声明式（员工草稿 → 不可变发布版本 → Run 冻结） | 这层做得好，是项目的核心资产 |
| 部署拓扑 | 单机 | 已诚实标注：`RunRealtimeEventHub` 为单实例内存扇出；持久事件可跨实例补读，瞬时增量会丢（可接受的降级） |
| 编排模式 | 中心化（平台 Run Worker 轮询） | 唯一执行路径，无第二套调度器——**这是明确优点** |

---

## 2. 横切关注点体检

| 关注点 | 现状 | 证据 |
| --- | --- | --- |
| 可观测性 | **名存实亡** | `haizhuo-brain-observability` 全模块仅 27 行；`AgentExecutionObserver` 全仓零引用；`DefaultAgentExecutionObserver` 三个方法全为空实现；无 `MeterRegistry` 使用；`micrometer-tracing-bridge-otel` 有依赖但无 exporter 配置，采样率 0.1 是空转配置 |
| 成本核算 | 缺失 | 无 token 计数、无语义缓存、无按 Run/用户的用量统计 |
| 限流／配额 | 仅认证面 | 只有 `RedisAuthenticationRateLimiter`；Run 创建与模型调用无配额 |
| 内容安全纵深 | 缺失 | 无注入防御、无输出过滤，只有长度截断 |
| 评测反馈闭环 | 缺失 | 见 §1.6 |
| 数据治理 | 部分 | 有手机号脱敏与 `ResolvedCredential.toString()` 不回显密钥；无备份/恢复/日志保留期说明 |
| 知识库版本化/灰度/回滚 | 部分替代 | 员工定义有不可变版本与发布指针（可视为灰度基础），能力有启停收窄；无知识库维度 |

**凭据风险**：`config/application-local.yml` 磁盘文件含明文真实模型 Key（该文件已被 `.gitignore` 忽略、未被 Git 跟踪，但已落在开发机磁盘上，且指向真实网关域名）。次要问题：`config/application-local.yml.example` 出现重复顶层 `haizhuo:` 键；Redis 端口在 `config/README.md`（6380）与 `haizhuo-brain-bootstrap/src/main/resources/application-local.yml`（6379）之间不一致。

---

## 3. 结构性问题（按严重度）

### P0-1 "形式复用"削弱了架构可信度

| 断言 | 证据 |
| --- | --- |
| 框架 `HarnessGateway` 从未接线 | 全仓 `HarnessGateway` 仅出现在契约测试；`runStream` 零引用 |
| 框架渠道入站面是死代码 | `PlatformChannel.dispatch`（`PlatformChannel.java:114`）无任何调用方；真实入站是 `ChannelWebhookController` → `ChannelIngressService.accept` |
| 框架出站面被刻意留空 | `ChannelConfiguration.java:58` 传 `null` 作为 outbound consumer（Javadoc 说明是"有意留空，防止绕过 outbox 守卫"——决策本身合理） |
| DmScope 因此没有运行时作用 | `AgentScopeChannelRuntime:119-124` 计算并设置了 `DmScope`，但没有任何 dispatch 会消费它；真实隔离来自 `platform_channel_conversation(binding_id, external_conversation_id) → session_id` |

**这不是"做错了"，而是"说重了"**：代码注释是诚实的，但文档口径（"用框架 ChannelManager 接管渠道运行时"、"复用框架渠道与网关边界"）会让后来者以为框架参与执行。代价是**两套路由模型并存**（框架 `ChannelConfig/ChannelBinding/ChannelRouter` vs 平台 `platform_channel_*`）且没有一致性校验，一旦将来真的启用 `dispatch`，两套语义会立刻冲突。

### P0-2 契约探针给了"已接入"的错觉

`AgentScopeGatewayContractTest` 的 10 个用例是**好方法学**（符号核对 + 可运行探针，且实测推翻了 2 个初始假设）。问题在于其中三条最有价值的结论——默认 `DmScope=MAIN` 串号风险、`LocalSessionTurnGate` 是阻塞排队、`gw-` 会话键可复算不可指定——**在真实链路里没有消费者**：没有 `dispatch`、没有 `setSessionTurnGate`、没有 `registerExternalSession`。

也就是说：**三条"接入红线"被准确地识别了，然后被绕过了**（因为链路改走平台自研路）。风险被移走了而不是被解决了——`SessionTurnGate` 仍是框架默认的阻塞门，一旦渠道链路真的接上框架网关，平台的唯一活动槽索引与框架的阻塞门会叠加出两套排队语义。

### P0-3 孤儿资产与死常量

| 资产 | 状态 |
| --- | --- |
| `agent_session` / `agent_run` / `agent_run_event`（V1） | 已被 V6 `platform_agent_*` 取代，主代码零读写 |
| `run_effective_capability_set` / `run_effective_capability_item`（V2） | 主代码从未写入或读取，能力视图改由 `platform_agent_run_spec` 承担 |
| `tool_invocation_audit`（V2） | 有写入实现，无生产调用方 |
| `MAX_ITERATIONS_CEILING`（=20） | 定义后从未使用 |
| 前端 `setUserGrant` API | 已导出，无任何页面引用——用户级能力授权有后端、无 UI |
| `ChannelRuntimeAdmin.NOOP` / `ChannelTurnPromoter.NOOP` / `ChannelReplyEnqueuer.NOOP` | 占位实现 |
| `ApprovalState.CANCELLED` | 枚举有值，无写入方 |

孤儿表在 Flyway 增量的项目里是**不可逆的长期成本**：迁移只能新增不能修改，意味着 V1/V2 的残留会永久留在 schema 里，每个新人都要重新判断"这张表还有用吗"。

### P0-4 前端存在循环依赖

GitNexus `check --cycles` 报出唯一一条环：

```
haizhuo-brain-web/src/api/auth.ts
  → src/api/httpClient.ts
  → src/router/index.ts
  → src/stores/auth.ts
  → src/api/auth.ts
```

后端分层纪律（kernel → platform → infrastructure → api）明显好于前端。这条环现在不致命，但会在模块加载顺序、测试 mock、按需拆包时持续制造麻烦，且它会掩盖后续更多环。

### P1 语义与文档的不一致

- V8 注释称"允许一个 Session 有多个排队 Run"，V10 恢复了活动槽唯一索引，实际至多 1 个 —— schema 注释与最终行为相反。
- `queuePosition` 恒为 1，但 API 与前端仍按"排队位次"呈现。
- `SessionBridgeSnapshot.stableBusinessFactsJson` 字段存在但恒写 `"{}"`。

---

## 4. 产品能力缺口清单

| 类别 | 缺口 | 影响 |
| --- | --- | --- |
| 价值出口 | **没有真实业务适配器**（会议室 Mock 已退出，无替代）；无 artifact/成果管理（框架 `ArtifactDeliveryRequest` 未接） | 平台目前只能产出"对话"，无法交付可验收的业务结果 |
| 知识 | 知识库 / RAG 无表无代码 | 数字员工无法引用企业知识 |
| 记忆 | 长期记忆显式关闭；无跨会话记忆表 | 无法形成"认识你"的助理体验 |
| 协作 | 受控子 Agent / 团队禁用；无 `subagent_manifest` 实际内容 | 复杂任务无法分工 |
| 渠道 | 无真实 IM provider；无跨实例交付与重启恢复验收 | 只有 Web 是真实可用的入口 |
| 外部身份 | 真实 MCP Server 的终端用户 Token 取得/续期未联调；非模拟模式直接抛异常（fail-closed，方向正确） | 企业工具接入的前置未清 |
| 管理面 | 用户级能力授权无 UI（`setUserGrant` 未接线）；审批无超时与取消入口 | 管理员与用户都缺必要入口 |
| 治理 | 无备份/恢复、日志保留期、数据清理策略 | 生产运维前置未清 |
| 质量 | 无评测、无坏例回流、无质量基线 | 无法回答"这版比上版好吗" |

---

## 5. 做对的地方（不要在重构中丢掉）

1. **单一状态所有者守得住**：平台 DB 是唯一事实源，运行时"不触碰数据库"，`AgentRuntime` 只有一个实现，没有第二套调度器。
2. **租约 + fence + 幂等键 + 投递状态翻转**这套组合拳达到生产级：崩溃后可回收、过期 worker 无法覆盖、结果不确定不盲目重发（`UNCERTAIN` 状态是成熟设计）。
3. **事件写入与状态同事务 + 会话游标跨 Run 单调 + 游标失效显式告知**（`cursorExpired`）——很多项目在这里留下静默丢消息的坑。
4. **MCP 治理的纵深**：发现快照 + 差异视图 + 逐工具批准 + 批准事务内校验"必须是最新快照" + 批准时用实测 Schema 覆盖上报值（防篡改）+ 调用前二次实探 + SSRF 主机白名单 + HMAC 短效 Token + 非模拟模式 fail-closed。这套组合超过多数同阶段项目。
5. **不向模型泄漏内部规则**：拒绝一律返回安全文案。
6. **凭据处理的克制**：`ResolvedCredential.toString()` 只回显引用；日志只记标识与计数。
7. **文档纪律**：严格区分「已验证 / 仅有契约 / 目标方案」，测试报告保留原始日期结论——这是项目最被低估的资产。
8. **拒绝机制性缺失时的"不假装"**：`ChannelConfiguration` 明确写出"出站只保留端口与 worker，不自动调度"，因为没 sender 时自动认领会污染状态。这类判断力比功能本身更值钱。

---

## 6. 建议的优先级

**不建议**在价值出口闭合前继续横向扩张（知识库、子 Agent、群聊、用户自建员工同时上）。当前风险不是能力不够，而是每个能力停在 60% 的骨架，叠加起来让"哪些能信"变得不可判断。

| 优先级 | 动作 | 为什么现在做 |
| --- | --- | --- |
| **1** | **补可观测性与成本基线**：让 `AgentExecutionObserver` 真正落指标（Run 成功率/时长/工具调用数/token 用量），配 OTel exporter；给 Run 创建加配额 | 成本最低、杠杆最高；不做这一步，后面所有优化都无法度量，V 层也无从起步 |
| **2** | **解开 E/C 两层的阉割**：迭代上限改为可配置并取消 `=1` 硬编码；补上下文预算与压缩策略；明确长会话降级规则 | 现在数字员工的"智能"被硬编码限制住，加任何功能都在这个上限下打折 |
| **3** | **补 L 层的防护栏**：审批超时 + 取消路径 + 会话占死回收；工具结果进入模型前的内容边界 | 这是"明天上生产第一个崩的地方"——用户点了审批去吃饭，会话就永久堵住 |
| **4** | **V 层最小闭环**：给 1 个真实场景建 20–30 条确定性验收用例（含 ground truth），失败自动回流；作为唯一质量门禁 | 不需要完整评测平台，一个能跑的回归集就能把"改坏了"变成可发现 |
| **5** | **诚实化清理**：删/迁移孤儿表（新迁移，不改已执行迁移）、删死常量与死代码、修正文档口径（把"复用框架渠道"改成"框架作为配置注册表，链路为平台自研"） | 让代码与文档重新对齐；不清理，每个新人都会重走一遍这次的排查 |
| **6** | **闭合一个真实价值出口**：选定 1 个真实业务系统 + 1 个真实 IM provider，端到端打通 | 只有这一步能验证前面五步的设计是否成立 |

---

## 7. 未验证与未知区

本次评估是**静态证据评估**，以下项目未做运行验证：

- 未启动服务、未跑测试套件，因此"当前是否构建通过"未验证。
- `maxIters=1` 在 AgentScope 2.0.3 的确切语义未做探针验证。
- 框架重建后重复投递 ToolResultBlock 是否已影响真实执行未验证（仅测试注释）。
- 多实例部署下的实际行为（`RunRealtimeEventHub` 单实例假设、框架默认 `WorkspaceMessageBus` 不跨进程）未验证。
- 前端循环依赖的实际影响面（是否已在运行时暴露过问题）未验证。
- 真实模型的输出质量、成本量级、延迟分布无数据。

---

## 8. 本次评估的默认假设（可推翻）

1. 假设「继续横向加能力」不是当前最优路径——若业务方已承诺某个能力的时间点，此判断需重估。
2. 假设「真实价值出口」应优先于「能力数量」——若当前目标是内部技术验证而非交付，则优先级应改为先补 V 层与可观测性。
3. 假设孤儿表可以清理——若 V1/V2 表仍有未发现的读取方（如外部 BI/运维脚本），需保留。
4. 假设 `config/application-local.yml` 中的明文 Key 需要轮换——若该 Key 已被吊销或为测试专用，可降级处理。

---

## 附：本次评估的关键证据索引

| 主题 | 位置 |
| --- | --- |
| 迭代上限硬编码 | `haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/runtime/DefaultHarnessDefinitionBundleCompiler.java:22-23,41` |
| 运行时事件与挂起 | `haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/AgentScopeRuntime.java:61-111` |
| Harness 装配（记忆/子 Agent 开关） | `.../runtime/agentscope/factory/HarnessAgentFactory.java:73-95` |
| 工具视图收窄 | `.../runtime/agentscope/middleware/CapabilityViewMiddleware.java:32-49` |
| 10 步判权流水线 | `haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/tool/DefaultToolExecutionGatewayService.java:71-119` |
| 资源策略空壳 | `.../platform/tool/DefaultToolResourcePolicy.java:15-23` |
| 审批状态机 | `.../platform/tool/ToolApprovalService.java:41-53`；`haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/tool/JdbcToolApprovalRepository.java:67-108` |
| 渠道装配与出站留空 | `haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/configuration/ChannelConfiguration.java:50-59` |
| 渠道运行时与 DmScope | `.../bootstrap/channel/AgentScopeChannelRuntime.java:41,76,85,119-145` |
| 死的 dispatch | `.../bootstrap/channel/PlatformChannel.java:114` |
| 可观测性空壳 | `haizhuo-brain-observability/src/main/java/com/haizhuo/brain/observability/*.java`（合计 27 行） |
| 租约与 fence | `haizhuo-brain-infrastructure/src/main/resources/db/migration/V11__run_execution_attempt.sql` |
| 会话事件投影 | `.../db/migration/V14__session_event_projection.sql` |
| 渠道底座 | `.../db/migration/V15__channel_ingress_and_delivery.sql`、`V16__channel_account_session_scope.sql` |
| MCP 治理 | `.../db/migration/V17__mcp_tool_governance.sql`、`V18__mcp_model_tool_name_claim.sql` |
| 框架能力验证报告 | `docs/06-开发指南/AgentScope2.0.3渠道与计划能力验证报告.md` |
| 代码图谱证据 | `.gitnexus/`（GitNexus 1.6.12，5516 节点；`check --cycles` 报前端 1 条环） |
