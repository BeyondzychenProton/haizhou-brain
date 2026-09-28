# Harness Durable Runtime 开发记录

| 项目 | 内容 |
| --- | --- |
| 需求来源 | 《海卓智慧大脑 Harness Durable Runtime 详细设计与编码实施规格 v0.3》 |
| 记录日期 | 2026-09-27 |
| AgentScope 版本 | 2.0.3（`agentscope-harness` / `agentscope-extensions-jdbc` / `agentscope-extensions-model-openai` / `agentscope-extensions-model-dashscope`） |
| 适用模块 | runtime-api、runtime-agentscope、platform、infrastructure、api、bootstrap |

本文是 Harness Durable Runtime 这一轮实施的开发记录：需求拆解与实现方案、各模块职责与关键
设计决策、与规格文档的偏差及原因、遗留问题与后续建议。P0 框架探针结论见同目录
《HarnessDurableRuntime-P0验证报告》。

## 1. 需求拆解与实现方案

规格要求把 Agent 运行从"进程内即时调用"升级为 **Harness Durable Runtime**：
平台（platform/infrastructure）持有全部持久化事实与授权决策，Runtime（AgentScope）只做
无状态的推理执行，两侧通过冻结的 `AgentExecutionRequest`（规格 §7.6）衔接。拆解为六条主线：

| 主线 | 规格章节 | 落地位置 |
| --- | --- | --- |
| 模板缓存与 Harness 工厂 | §18–§22 | `runtime-agentscope/factory/*` |
| 调用上下文与命名空间 | §23–§26 | `runtime-agentscope/context/*`、`factory/DefinitionWorkspaceMaterializer` |
| 中间件链（安全/视图/桥接/控制/观测） | §27–§28、§31 | `runtime-agentscope/middleware/*` |
| Schema-only 外部工具与事件翻译 | §29、§33–§34 | `runtime-agentscope/tool/ExternalToolSchemaAssembler`、`event/AgentScopeEventTranslator`、`AgentScopeRuntime` |
| Durable 执行循环（claim/lease/fence/心跳/回收） | §13–§15、§43–§46 | `platform/run/RunExecutionService`、`infrastructure/run/JdbcRunExecutionStore`（V11） |
| 企业工具执行与审批 | §36–§39、§43、§45 | `platform/tool/ToolExecutionWorker`、`DefaultToolExecutionGatewayService`、`infrastructure/tool/JdbcToolExecutionRepository` / `JdbcToolApprovalRepository`（V12） |

支撑面：发布期 bundle 编译与 run spec 冻结（§52、V9）、会话→Harness 绑定与一次性桥接快照
（§53、V10）、§39.2 审批 API（`SessionController` 两个端点）、bootstrap 全套接线
（`AgentRuntimeConfiguration` / `HarnessRuntimeConfiguration` / 薄 `RunWorker`）。

## 2. 模块职责与关键设计决策

### 2.1 runtime-agentscope（Harness 适配层）

- **模板键（§18，I-04）**：`HarnessTemplateKey.from(snapshot)` = SHA-256(bundleHash +
  modelProvider + modelName + maxIterations + staticToolCatalogHash + workspaceContentHash +
  harnessPolicyHash)。显式排除 userId/sessionId/runId/toolViewHash/bridge——同一定义版本的
  所有 Run 共享同一 Harness 实例，缓存仅 `computeIfAbsent`。
- **工厂纪律（§21）**：`HarnessAgentFactory` 只读冻结快照与配置，**禁止**触碰 Run DB /
  User DB / 最新草稿 / 当前权限 / Credential。模型构造收敛在
  `AgentScopeModelFactory`（openai / openai-compatible / dashscope 三态），并提供
  `protected createModel(...)` 钩子供测试替换。
- **P0 安全姿态**：在规格 §21.1 之外统一追加 `disableShellTool/disableSubagents/
  disableTranscript/disableDynamicSkills/disableDefaultWorkspaceSkills/disableDynamicSubagents/
  disableToolsConfig/disableAtPathExpansion` 与 `disableMemoryHooks/disableMemoryTools`（I-15），
  默认能力面最小化。
- **中间件链**：SecurityMiddleware（无 HarnessCallContext 即拒绝）→
  CapabilityViewMiddleware（`onReasoning` 做 call 级 Tool View 过滤；catalog 名集合随模板注入，
  非 catalog 名视为 harness 内部工具始终可见；不用 `ToolGroup.activatedGroups`，因其会进入
  AgentState 造成跨 Run 泄漏）→ SessionBridgeMiddleware（走 `onSystemPrompt` 官方缝拼接桥接摘要，
  不注入消息）→ RunControlMiddleware（取消→`RequestStopEvent`，guidance→SystemMessage）→
  ObservabilityMiddleware（只记标识，不记负载/密钥）。
- **AgentScopeRuntime（§30）**只做：取模板 → 建上下文 → 构造输入 → call → 翻译。
  suspend 后用 AtomicBoolean 抑制尾随的 AgentRunCompletedEvent；resume 保持原
  toolUseId/toolName 构造 `ToolResultBlock`；异常只向外暴露类型，不泄漏细节。

### 2.2 platform（无 Spring、无日志框架的纯领域层）

- **RunExecutionService（§13/§44–§46）**：claim → 心跳（ttl/3，单线程 daemon）→
  drive（组装 §7.6 请求，blockLast 收集终止事件）→ settle（suspend/complete/cancel/fail
  恰好一种落定）。Runtime 不碰 DB，本服务是唯一写方。
- **幂等键（§43.1）**：`SHA-256(runId | toolUseId | capabilityRevisionId | inputDigest)`，
  inputDigest 为 CanonicalJson 的 SHA-256。
- **一次性投递守卫（§45.1/§46）**：resume 只取 `result_delivery_state=READY` 的终态结果；
  complete **和 suspend** 都会把已消费结果翻转 READY→DELIVERED（suspend 也算 Harness 已接受，
  否则再次 suspend 会重复投递）。框架层实测"重复 model 调用但不重新执行外部 Tool"（P0 探针），
  真正防重 = 平台投递守卫 + `(run_id, tool_use_id)` 唯一约束幂等插入。
- **RECONCILIATION_REQUIRED（§43.4）**：副作用可能已离开平台但结果未知时落定该状态，
  禁止盲目重试，等待人工对账。
- **ToolExecutionWorker（§36/§39）**：claim → 网关 prepare（固定授权管线）→
  DENIED 落终态 / APPROVAL_REQUIRED 停靠审批 / ALLOWED 执行后 Tx-05 原子落定并 requeue Run。
  异常兜底 FAILED("TOOL_WORKER_ERROR")，文案安全。
- **风格约定**：platform 模块无 Spring 注解、**无日志框架**（全模块无 Logger 先例），
  诊断信息由 durable 状态与事件承载。本轮因此移除了两个 worker 里误引入的 slf4j。

### 2.3 infrastructure（JDBC 适配 + 白名单执行器）

- **JdbcRunExecutionStore（§13.2/§13.6）**：所有写操作校验 attemptId + leaseToken +
  fenceToken + attempt RUNNING；fence 单调递增，过期 worker 无法覆写新 attempt（I-11）。
  租约回收（§15）：RUNNING→QUEUED + `RUN_REQUEUED_AFTER_LEASE_TIMEOUT`；CANCELLING→CANCELLED。
- **JdbcSessionRunStore 取消矩阵（§47）**：QUEUED 直接取消；RUNNING→CANCELLING +
  durable flag（RunControlMiddleware 在安全检查点确认）；WAITING_TOOL 有 EXECUTING 执行时
  **诚实拒绝**（不假装回滚已离开的副作用），否则取消 REQUESTED/APPROVED 执行并取消 Run；
  WAITING_CONFIRMATION 关闭 PENDING 审批（REJECTED/"运行已取消"）+ 取消执行 + 取消 Run。
- **MeetingRoomCapabilityExecutor**：种子实现 `implementation_key=meeting-room-v1`
  （meeting_room_search / meeting_room_reserve），SQL 语义取自 git 历史（A-201、
  Asia/Shanghai、operation_key 幂等、容量/重叠校验）；幂等键相同而内容指纹不同返回
  `IDEMPOTENCY_CONFLICT`，重复请求去重成功；`@Transactional` 标在外部入口 `execute()`，
  避免自调用绕过代理。
- **事件类型与规格 §49 对齐**：`RUN_REQUEUED_AFTER_LEASE_TIMEOUT`、`TOOL_APPROVED` /
  `TOOL_REJECTED`、`RUN_RESUME_QUEUED`、`TOOL_RESULT_READY` 等，全库统一。

### 2.4 api / bootstrap

- `SessionController` 新增 §39.2：`GET /api/v1/sessions/runs/{runId}/tool-executions`
  （含 approvalDecision）与 `POST .../tool-executions/{toolExecutionId}/decision`；
  重复决定按 §84 幂等返回 `decided=false`。
- `AgentRuntimeConfiguration`：整个 runtime 栈 `@ConditionalOnProperty(run-worker.enabled=true)`
  （默认 false）；`JdbcAgentStateStore(DataSource, new MysqlDialect())`；`filesystemSpec=null`
  （见偏差 §3.3）。
- `HarnessRuntimeConfiguration`：CapabilityExecutorRegistry（List 注入）、bundle compiler、
  runSpecFactory、SessionBridgeService、DefaultToolResourcePolicy、CredentialResolver P0 桩
  （`ResolvedCredential.none()`）、DefaultToolExecutionGatewayService、ToolApprovalService；
  双 worker 条件于 run-worker.enabled。
- `RunWorker` 为薄调度器：每 tick 按需 reclaim → 先 drain 工具队列（完成会 requeue Run）→
  再 drain Run 队列，无业务逻辑。

## 3. 与规格文档的偏差及原因

| # | 偏差 | 原因与依据 |
| --- | --- | --- |
| 3.1 | 规格 §22 的 `MysqlAgentStateStore` 实际落地为 `JdbcAgentStateStore(DataSource, MysqlDialect)` | 2.0.3 extensions-jdbc 的真实形态（javap 核验）；方言体系含 Mysql/H2/Postgres/Sqlite。 |
| 3.2 | 规格 §21.1 的 `disableSubagentTool()` 实际为 `disableSubagents()` | 2.0.3 API 核验，已在 HarnessAgentFactory javadoc 标注。 |
| 3.3 | `RemoteFilesystemSpec` 无规格 §22 设想的 `(efsBaseUrl, namespaceFactory)` 构造器 | jar 内仅有 `()` 与 `(BaseStore)` 两个构造器，且只自带 `InMemoryStore`；durable 文件面需自研 BaseStore（P1），本轮 wiring 传 `filesystemSpec=null` 并关闭定义工作区文件写入。 |
| 3.4 | P0 安全 disable 集超出规格 §21.1 列表 | 2.0.3 builder 提供的最小能力面选项全部关闭，理由见 §2.1。 |
| 3.5 | 事件名以规格 §49 清单为准（`RUN_REQUEUED_AFTER_LEASE_TIMEOUT`、`TOOL_APPROVED`/`TOOL_REJECTED`） | 早先草稿使用了 `RUN_REQUEUED`/`TOOL_APPROVAL_DECIDED`，统一对齐 §49。 |
| 3.6 | TenantId 暂硬编码 `1L` | P0 单租户；多租户模型落地后由请求上下文解析。 |
| 3.7 | CredentialResolver 为 P0 桩（恒 `ResolvedCredential.none()`） | 种子能力（会议室）不需要外部凭据；规格凭据面属 P1。 |
| 3.8 | WAITING_TOOL 取消在执行中（EXECUTING）时拒绝 | 副作用已离开平台，诚实拒绝优于假装回滚（§47.3 备注）。 |
| 3.9 | 审批关闭 SQL 用子查询替代 MySQL `UPDATE...JOIN` | H2 不支持多表 UPDATE（探针实证）；子查询形态在 MySQL/H2 语义一致。 |
| 3.10 | 平台投递守卫同时覆盖 complete 与 suspend | 规格 §45.1 只明示 complete；suspend 说明 Harness 已接受恢复结果，不翻转会重复投递（测试锁定）。 |

### 测试发现并修复的真实缺陷

1. **`JdbcToolApprovalRepository.map()` 的 `wasNull()` 求值顺序缺陷**：构造器参数按序求值，
   `requested_for_user_id` 的读取把 `wasNull()` 的参照列从 `decided_by_user_id` 顶掉，
   PENDING 审批（decided_by 为 NULL，读出 0）会抛 "userId must be positive"。已改为先读列、
   就地捕获 wasNull 再组装（由 `JdbcToolApprovalRepositoryTest` 锁定）。
2. **platform 模块误引 slf4j**：模块 pom 无该依赖且全模块无日志先例，移除两个 worker 的
   Logger（见 §2.2 约定）。

## 4. 测试资产（本轮新增/重写，全部可重复执行）

| 模块 | 测试 | 用例数 | 覆盖要点 |
| --- | --- | --- | --- |
| runtime-agentscope | AgentScopeRuntimeTest 等 10 类 + HarnessP0IsolationTest | 34 + 7 | 模板缓存复用（I-04）、suspend→resume 全链路、中间件语义、翻译器全分支、P0 隔离探针 |
| runtime-agentscope | JdbcAgentStateStoreVerificationTest | 2 | JDBC 状态跨实例恢复（P0 缺口补验）、会话槽位隔离 |
| platform | RunExecutionServiceTest / ToolExecutionWorkerTest | 9 + 7 | 冻结请求组装、四种终止落定、§43.1 幂等键、§45.1 守卫、网关三分支、对账态、异常兜底 |
| infrastructure | JdbcRunExecutionStoreTest | 10 | claim/fence/心跳、§13.6 过期 worker 隔离、§15 回收、幂等 suspend、投递守卫 |
| infrastructure | JdbcSessionRunStoreTest | 10 | createRun 同事务冻结、幂等重放/冲突、§47 取消全矩阵、引导一次性消费 |
| infrastructure | JdbcToolExecutionRepositoryTest / JdbcToolApprovalRepositoryTest | 7 + 5 | Tx-05 原子落定、可投递视图、§39 停靠/批准/拒绝、§84 幂等决定、安全文案 |
| infrastructure | JdbcAgentDefinitionRepositoryTest（重写） | 5 | 草稿乐观修订、发布不可变+幂等重放、目录条目（revisionId/审批标记）、授权 upsert、审计脱敏 |
| infrastructure | JdbcHarnessDefinitionBundleRepositoryTest / JdbcHarnessRunSpecRepositoryTest / JdbcHarnessBindingBridgeTest | 2 + 1 + 2 | bundle 内容寻址 insert-only、spec 往返、binding/snapshot scope 唯一 |
| infrastructure | MeetingRoomCapabilityExecutorTest | 7 | 可用性文案、幂等去重/冲突、容量/时间校验、参数与未知 action 拒绝 |
| bootstrap | ApplicationContextSmokeTest | 2 | 匿名访问控制、CSRF；test profile 以 AgentDefinitionRepository mock 补齐装配（该接口继承 EmployeeCatalog，@Primary 单 bean 同时满足两类注入点） |
| bootstrap | AgentStateStoreSchemaMigrationTest | 2 | V13 迁移与 `MysqlDialect` 的 schema 契约：列名集合+主键静态比对（方言 DDL 实跑抽取）、迁移脚本在 H2 MODE=MySQL 上执行后 Store 读写/删除往返 |
| bootstrap | MysqlMigrationProbe（真机，手工触发） | 2 | MySQL 8.0.45 上跑 V1–V13 全量 Flyway + 严格 2 参 Store 往返 + Harness 挂起/重建/恢复；需 `-Dmysql.probe.password`，未给则跳过、不碰任何库 |

H2 测试约定：内存库 `MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE`，表结构用内联
最小 DDL（仅保留 store 实际访问的列），共享夹具在
`infrastructure/src/test/java/com/haizhuo/brain/infrastructure/support/HarnessJdbcTestSupport.java`；
`FOR UPDATE SKIP LOCKED`、`AUTO_INCREMENT UNIQUE` 等关键语法已经探针确认 H2 2.3.232 可用。

## 5. 遗留问题与后续建议

1. **durable BaseStore（P1）**：自研 JDBC BaseStore 承接 workspace 文件面，或跟踪框架后续版本；
   在此之前 `filesystemSpec=null`。
2. **CredentialResolver / 多租户 / AgentStateStore 版本冲突专项**：均为 P1 候选
   （版本冲突能力 `saveIfVersion`/`getVersioned` 已在 jar 中确认存在）。
3. **run-worker 默认关闭**：`run-worker.enabled=false` 为默认；开启前库需迁移到 V13
   （`agentscope_sessions` 已建表）。
4. **本地 profile 的库凭据**：`application-local.yml` 仍写 `root` + 空密码，与本机 MySQL 8.0.45
   （root 需密码）不符；建议改读环境变量/本地覆盖文件，避免提交真实口令。
5. **API 端点集成测试**：§39.2 两个端点目前由仓储层测试间接覆盖，建议补一层
   WebTestClient 级别的契约测试。

> 2026-09-28 闭环（原遗留两项）：
> ① `agentscope_sessions` 建表 DDL 固化——新增 `V13__agentscope_session_state.sql`
> （列名/类型/主键取自 `MysqlDialect#sessionStateCreateTableDdls()` 实跑输出），生产维持 2 参构造
> 严格模式，由 `AgentStateStoreSchemaMigrationTest` 守住迁移与方言的一致性。触发原因：真机 local
> profile 启动时 `JdbcAgentStateStore` 构造器校验失败，导致 run-worker bean 图整体装配崩溃，
> 详见 P0 报告"启动故障复盘"一节。
> ② MySQL 实库跨实例验证——已在 MySQL 8.0.45 上跑通 V1–V13 迁移 + 严格 Store + Harness
> "挂起→重建→恢复"（`MysqlMigrationProbe`，见 P0 报告"真机 MySQL 验证"一节）。
