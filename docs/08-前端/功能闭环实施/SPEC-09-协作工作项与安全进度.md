# SPEC-09 协作工作项与安全进度

状态：**安全协作投影、成员状态与基于完整事实的版本摘要已落地；本轮 platform/API/H2 定向测试通过，前端类型/35项单测/生产构建通过；真实协作运行、MySQL 与浏览器验收未执行**。编号：FE-09。日期：2026-10-09。
基线：`eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`，AgentScope Java `2.0.3`。
前置：[公共契约](SPEC-00-公共契约与实施约定.md)、[详细设计](../功能与界面闭环详细设计.md)、[员工配置](SPEC-04-员工运行配置与版本管理.md)。

## 1. 目标与边界

会话中展示可授权的专家调用、工作项修订、预算和 Team 收尾状态，让用户知道当前工作处于哪一步。
新增后端安全查询与投影；前端消费白名单 DTO，不直接读取协作内部事件或原生存储。
AgentScope 继续拥有子 Agent/Team 的执行和调度；平台仅管理可信 Run 边界、持久事实与用户可见投影。
这是一项 C 类补齐：内部事实存在，不代表页面或用户可见契约已经存在。
只公开状态、确定来源的进度和用户已获授权结果；默认不把专家私有结果发布给普通用户。
根 Agent 的工作项验收是内部质量检查，与用户工具批准、用户接受交付分别管理。

## 2. 当前证据

| 当前实现 | 源码证据 | 本轮状态/剩余验收 |
| --- | --- | --- |
| 工作项受理、修订、精确结果校验和根验收持久化 | [JdbcDelegationWorkItemRepository](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcDelegationWorkItemRepository.java)、[RunProgressQueryService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunProgressQueryService.java) | 白名单投影、属主查询、分页与修订摘要已落地；本轮 Java 定向验收通过 |
| work_item_result/work_item_review 为根 Agent 工具 | [RunWorkItemTools](../../../haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/tool/RunWorkItemTools.java) | 保持为根 Agent 内部工具；没有新增用户可调用的验收端点 |
| WORK_ITEM_ACCEPTED/REVISED/验收与结果事件为 INTERNAL | [JdbcRunProgressNotifier](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunProgressNotifier.java)、V31 | 新增 `RUN_PROGRESS_UPDATED` 白名单通知；原事件可见性不变；本轮相关事务/幂等测试通过 |
| Team 关联、动作预算、成员事件和恢复状态已存储 | [JdbcTeamExecutionPersistence](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcTeamExecutionPersistence.java)、[V30](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V30__autonomous_team_execution_and_budget.sql)、[V44](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V44__team_member_lifecycle_progress.sql) | 安全汇总、成员生命周期来源与通知接线已落地；真实 MySQL 和受控运行待验证 |
| 原生 Team 复用 TeamClient/TeamsMiddleware/MessageBus | [NativeTeamExecutionGateway](../../../haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/team/NativeTeamExecutionGateway.java) | 继续由 AgentScope 承担调度；本轮未复制计划或协作调度器 |
| 用户 Session 与结果按属主保护，Run mode 仅 DIRECT | [SessionApplicationService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/session/SessionApplicationService.java)、[JdbcRunProgressQueryStore](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunProgressQueryStore.java) | 查询同时核对 Run、Session 和用户属主；本轮 controller/service/H2 单测通过，真实 HTTP 未运行 |
| 协作结果正文按需读取 | [RunProgressController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/RunProgressController.java)、[RunWorkItemResult](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunWorkItemResult.java) | 仅显式 USER 可见且关联/哈希/字节数有效的结果可读；无真实授权结果生产者时链接保持隐藏 |

本轮已完成进度安全投影、事实变更通知、Team 成员生命周期持久化、工作项分页/修订读取及前端进度卡。协作结果正文仅在显式 USER 可见且完整关联可证实时按需读取；子 Agent 保存的 INTERNAL 结果不被提升。
当前 Team member_binding 的 state 初始为 BOUND，成员生命周期主要记录为 INTERNAL 事件；不能把 BOUND 展示为正在执行。

当前代码证据仍需经过下方各层验收。真实 MySQL、受控协作运行和浏览器场景尚未验证。

## 3. 用户页面

会话回答旁增加默认折叠的“协作进度”卡，展示工作项摘要、角色、状态、修订号和可见结果入口。
无协作事实时不显示空卡；profile 已配置但本轮未委派时，不伪造“正在协作”。
固定专家显示管理员配置的角色与员工名称；动态专家显示通用“只读分析”，不展示模型构造的私有指令。
预算以“已预留调用/上限、当前活动/并行上限”展示；预留不等同成功调用，不能承诺剩余次数可用。
工作项列表支持查看历史修订状态；最新修订为默认视图，旧结果不会冒充当前结果。
Team 卡显示已开始、执行中、已收尾、待核查；成员未知状态显示“状态暂无记录”。
暂不展示任意 objective、输入引用、review reason 或工具参数；这些内容可能包含私有资料。
最终交付仍是根结果消息，按 [SPEC-02](SPEC-02-会话事件与完整结果.md) 校准；协作卡完成不能提前把根回答标为已交付。

```text
卡片：加载 → 有记录/无记录/查询失败 → 实时失效通知 → 刷新安全投影
工作项：已分配 → 执行中 → 已返回 → 格式检查 → 已验收/需修订/已拒绝
运行：执行中 → 取消中/待核查/已结束；页面不根据专家完成自动推断 Run 成功
```

状态来自后端 DTO，不以时间流逝、等待时长、文字 delta 或最后一条气泡推断。

## 4. 查询契约

以下为当前实现的安全查询契约，返回 schemaVersion=1 的用户安全 DTO；路径不提供内部事件原文。
认证用户必须先通过 `sessions.getRun(runId,userId)` 的归属验证；管理员也不能借普通端点绕过属主。
管理核查使用独立权限和 DTO，关联 SPEC-10，不通过 `?includeInternal=true` 扩权。

| 方法与路径 | 用途 | 请求与响应 |
| --- | --- | --- |
| GET `/api/v1/sessions/runs/{runId}/progress` | 首次、重连、通知后读取 | 完整安全汇总，支持 ifVersion；相同 version 返回 304 |
| GET `/api/v1/sessions/runs/{runId}/work-items` | 分页列表 | cursor、limit 默认20/max100；items、nextCursor、hasMore |
| GET `/api/v1/sessions/runs/{runId}/work-items/{workItemRef}` | 一个工作项的状态历史 | 白名单详情及修订摘要，不含结果正文 |
| GET `/api/v1/sessions/runs/{runId}/work-items/{workItemRef}/revisions/{assignmentRevision}/result` | 读取一个已授权修订的完整结果 | 路径以工作项和修订定位，不接受任意 resultId；只有结果 kind 为 `DELEGATION_FINAL`/`TEAM_RESULT`、visibility 为 USER、父 Run 与 Session 属主一致、修订 result_id/result_sha256 与结果行一致、正文非空且不超过 1 MiB、存储字节数与数据库正文长度一致时才返回；否则统一 404 |

progress 字段：schemaVersion、runId、runtimeProfile、runState、version、asOfSessionCursor、updatedAt、available。
summary：workItemTotal、activeCount、returnedCount、acceptedCount、revisionRequestedCount、rejectedCount、unknownCount。
delegationBudget：invocationsReserved、invocationLimit、activeInvocations、parallelLimit；没有事实时为 null。
workItemsPreview：最多 20 个摘要；hasMoreWorkItems 控制分页入口，不把预览数量当全部任务数。
team：executionId、state、startedAt、completedAt、safeStopReasonCode、memberSummaries、actionBudgets；无事实为 null。
memberSummaries：roleId、displayName、kind、state、lastTransitionAt、stateSource（DURABLE_EVENT/NO_FACT）。
成员日志缺失时 state=UNKNOWN；绑定只证明身份，不能推断执行完成或失败。
成员 state 为 RUNNING/IDLE/STOPPED/UNKNOWN：STARTED→RUNNING、ENDED→IDLE、STOPPED→STOPPED；ENDED 只说明本轮执行结束，不代表整个任务交付完成。
actionBudgets：actionType、reserved、limit，仅对服务端允许公开的预算种类输出；不含 reservationId。
当前原生 Team 适配常量为 TASK_CREATED=6、MESSAGE_RECIPIENT=20；limit 按已知执行适配版本解析，无法确认历史上限时为 null，不把当前常量伪装成历史冻结事实。
available=false 表示历史事实不支持当前投影，显示“无可用进度记录”；不能返回伪造空成功。

工作项摘要字段：workItemRef、displayLabel、roleId、executorDisplayName、sourceKind、required、assignmentRevision、state、createdAt、updatedAt。
sourceKind 白名单为 PUBLISHED_EXPERT/BUILTIN_GENERAL_PURPOSE/RUNTIME_GENERATED，名称由前端本地化；不扩展成原始动态专家定义。
revision 摘要：assignmentRevision、executionState、formatStatus、reviewStatus、resultAvailability、createdAt、completedAt。
displayLabel 由服务端生成“分析任务 1”等安全名称；业务原文不直接复制到该字段。
executionState 映射 ACCEPTED→待执行、ACTIVE→执行中、FINISHED→执行结束、NOT_STARTED→未执行。
formatStatus 支持 PENDING/VALID/INVALID/UNKNOWN；reviewStatus 支持 PENDING/ACCEPTED/REVISION_REQUESTED/REJECTED/UNKNOWN。
工作项总 state 同时考虑最新修订和调用状态；“执行结束”本身不等同结果验收通过。
resultAvailability 只能为 PRIVATE/USER_VISIBLE/UNAVAILABLE；默认私有的 DELEGATION_FINAL/TEAM_RESULT 为 PRIVATE。
仅当当前结果表 kind、visibility、父 Run/Session 属主、工作项修订关联及存储哈希都已允许读取时，输出公开 resultId；否则省略并映射为 PRIVATE 或 UNAVAILABLE。
本切片不新增把 INTERNAL 结果改为 USER 的命令；未来公开子结果要另设明确发布契约和审计。
正文读取每次重新检查完整归属链及修订哈希，并在服务端重新计算 UTF-8 正文哈希和字节数；授权撤销、关联缺失、哈希/字节数不匹配、超长或其他无法证明的结果均隐藏为 404/UNAVAILABLE。结果读取设置 private/no-store，不查询普通子消息或原生消息正文。
旧工作项存在精确依赖，但依赖原始引用不进入 DTO；可输出服务端计算的 blockingDependencyCount。
分页按工作项 createdAt+workItemRef 稳定排序，cursor 绑定用户、Run 和过滤条件。

## 5. 持久投影与实时通知

投影只读取当前 Run 的既有工作项、修订、调用、结果可见性、冻结预算及 Team 安全生命周期事实。
同一数据库一致性读取边界内获取 Session cursor 上界，再读取事实；asOfSessionCursor 是本次可解释的事实边界。
version 为规范化安全进度投影内容 hash，包含 Run 的完整安全工作项摘要（与分页读取一致，不限于首屏 20 项）和事实派生的 updatedAt；排除查询时间和 asOfSessionCursor。后者仅是读取边界，其他事件推进游标不能改变同一进度 version。
不得使用当前时间生成 version，也不得用瞬时流 streamOffset 充当持久进度版本。
`JdbcRunProgressNotifier` 在持久事实变化事务中追加 `RUN_PROGRESS_UPDATED`，visibility=USER；通知 payload 仅含 runId、projectionKind=COLLABORATION、schemaVersion=1，不携带私有工作项内容。
通知通过既有持久 Session 事件与 sessionCursor 补读；原 INTERNAL 事件保留原样。
投影器与事实写入同事务：成功提交后才能被 SSE 读取，失败时事实和通知一起回滚。
不要让通知反过来产生通知；幂等键绑定原事实变更标识，重复内部事件不得重复追加。
用户根本无权读取的协作内容，即使 INTERNAL 记录存在，也只能通过服务端生成的安全状态摘要呈现。
Team start、reserveAction、complete、delegation 与 reservation 生命周期入口已接入通知/持久写入适配；入口覆盖、同事务回滚及幂等重放仍待 Java 定向用例和真实 MySQL 验证。
前端收到通知合并刷新请求，单 Run 只允许一个在途查询；后续通知标记 dirty，完成后再读最新版本。progress version 变化时，顺序重读用户已加载的工作项页并替换旧页数据，保留最新游标与 hasMore；不能只合并首屏预览而让后续页停留在旧状态。
乱序响应通过 runId、页面 generation 和 asOfSessionCursor 校验；旧投影不能覆盖新状态。
重连后先补 Session 持久事件，再读取 progress；即使没有收到通知，也在 Run 终态时校准一次。
活动 Run 的查询可用 3 秒轮询降级；终态确认后停止，切换 Session 卸载所有计时器和订阅。
协作进度查询失败不阻断文字流；保留最后成功数据、标记更新时间并允许重试。
事件重连、v3 视图及渲染 generation 与 [SPEC-03](SPEC-03-实时消息恢复与渲染.md) 共用 transport，不新增第二条自维护 Session 队列。

## 6. 失败、恢复与信息保护

Run CANCELLED/FAILED/TERMINATED 时，UI 收尾并保留已持久事实；不得把未知子调用标成“成功”。
Run RECOVERY_REQUIRED 时卡片显示待核查，停用用户重试/重开子任务；管理员终止见恢复 spec。
Team 状态仅映射已有 RUNNING/COMPLETED/RECOVERY_REQUIRED；父 Run 终态与 Team 状态分开展示。
旧 fence 的子事件被既有写入边界拒绝；查询不把 attemptId、fenceToken、nativeSessionId 返回普通用户。
脱敏采用服务端字段白名单，而非拿原始 JSON 删除几个字段；日志也不记录被过滤正文。
禁止输出思考链、原生 inbox、accepted_payload、input_refs_json、dependencies_json、私有专家正文和模型消息。
错误 reason 仅输出受控 safeReasonCode 及本地化文案，不输出异常栈或远端响应。
结果链接点击时重新校验可见性；权限变化后历史卡片链接失效应返回 404，不沿用缓存授权。
服务端不可靠的状态为 UNKNOWN；前端呈现有界不确定性，不能用“已完成”掩盖缺少记录。

## 7. 模块和实施入口

| 模块 | 当前入口 | 本轮状态 |
| --- | --- | --- |
| platform | RunProgressQueryService、RunProgressProjection、RunProgressQueryStore、RunWorkItemResult | 已实现属主验证、安全 DTO、状态映射、分页/修订与受控结果正文模型；本轮定向测试通过 |
| infrastructure | JdbcRunProgressQueryStore、JdbcRunProgressNotifier、JdbcDelegationReservationLifecycle；现有 JdbcDelegationWorkItemRepository/JdbcTeamExecutionPersistence | 已实现一致性查询、安全通知事务接线和 reservation 生命周期持久写入；真实 MySQL 未验证 |
| api | RunProgressController | 已实现 progress、work-items、详情及修订结果只读路由；controller 单测通过，真实 HTTP 未执行 |
| runtime-api/runtime-agentscope | 现有 TeamExecutionPersistence/RunWorkItemTools | 继续保持 AgentScope 执行所有权；不新增前端调度 API |
| web | [RunProgressCard.vue](../../../haizhuo-brain-web/src/components/conversation/RunProgressCard.vue)、[runProgress.ts](../../../haizhuo-brain-web/src/api/runProgress.ts)、SessionView.vue | 已接入默认折叠进度卡、分页/修订、合并刷新、version 变化后重读已加载页、活动 Run 3 秒轮询、按需结果读取与 404 撤下链接；类型检查和单元测试通过，浏览器验收未做 |
| events | 既有 RunEvent/Session projector | 已接入 `RUN_PROGRESS_UPDATED` 白名单通知并沿用持久 Session 事件与 cursor 补读；事务、重连和多实例行为待集成验证 |

## 8. 可执行切片与回退

1. 查询/事务接线符号已做 impact 并以源码核对 UNKNOWN；核对 V30–V34 与现有 sourceKind/state 集合已完成。
2. 白名单 DTO、状态映射和安全查询实现已落地；Java 测试（含私有标记样本）本轮通过。
3. 一致性查询及属主 HTTP 路由已落地；controller/H2 单测通过，真实 HTTP 未执行。
4. 已接入 RUN_PROGRESS_UPDATED 与 reservation 生命周期写入；通知事务、幂等与版本摘要定向测试本轮通过，真实 MySQL/旧 fence 多实例仍待验收。
5. 卡片已接入会话视图，SSE 仅触发刷新；完整结果使用独立只读路由，结果正文不进入协作状态事件。progress version 覆盖全部分页安全摘要；version 改变后重读所有已加载页。类型检查及单元测试已通过，浏览器验收待执行。
6. 真实 MySQL 事件/查询一致性、受控 Team/委派场景及浏览器验收未执行。

优先利用既有表和索引；查询计划有证据需要新索引时新增 Flyway，不修改 V30–V34。
无需为安全 UI 复制工作项/Team 执行表或迁移原生 inbox；如新增通知幂等索引，按现有事件唯一键扩展。
旧服务不会生成新通知，前端通过轮询保持安全查询能力；不把无通知误认为无协作。
回退前端只隐藏卡片；回退查询/通知接线不删除协作事实、结果或持久事件。
关闭协作 profile 不改历史卡片；查询历史与准入新执行分别管理。

## 9. 验收与 DoD

| 编号 | Given / When / Then |
| --- | --- |
| FE-09-A01 | 给定无协作事实的 DIRECT Run，查询进度，则 available/空状态准确且 UI 不造执行卡 |
| FE-09-A02 | 给定已受理、执行、返回、根验收工作项，查询/接收通知，则状态对应持久事实且最终回答独立 |
| FE-09-A03 | 给定修订 1 被要求修改、修订 2 返回，则默认展示修订 2，旧结果不替代当前验收 |
| FE-09-A04 | 给定内部正文含唯一秘密标记，查询/事件/日志，则 DTO 与用户 SSE 均无该标记和私有字段 |
| FE-09-A05 | 给定另一个用户 Run、私有结果 ID，或不匹配的工作项/修订关联，读取进度/结果，则 404；不透露存在性 |
| FE-09-A06 | 给定成员仅 BOUND 无生命周期事件，则显示未知记录，不显示运行中或完成 |
| FE-09-A07 | 给定旧 cursor 断线后事实变化，重连补读，则卡片恢复到最新安全投影且不重复状态 |
| FE-09-A08 | 给定同事实事件重放及通知事务回滚，则成功通知至多一次，失败事务无孤立通知 |
| FE-09-A09 | 给定 Team RECOVERY_REQUIRED/父 Run 取消，则卡片显示正确不确定/终态，用户无法重开内部任务 |
| FE-09-A10 | 给定旧请求迟到、切换会话，则旧 progress 不覆盖新 Run，计时器和订阅被释放 |
| FE-09-A11 | 给定同一安全投影重复查询，version 不变可返回 304；任意分页工作项安全字段变化（即使总数/分类计数不变）后 version 改变，已加载页刷新到新事实，且无关 sessionCursor 推进不改变 version |
| FE-09-A12 | 给定预算已预留但未成功调用，则 UI 展示“预留”且不计为成功完成次数 |

单元测试覆盖白名单、状态映射、version 稳定性、刷新合并和竞态；HTTP 测试覆盖属主与参数。
MySQL 集成验证事实/通知/查询事务一致性、旧 fence、分页和重复通知；运行契约验证复用原生调度。
浏览器验证工作项卡、修订、断线、取消、私有标记和历史记录；不依赖截图代替权限响应检查。
DoD：代码及本轮 Java/API/H2 选择集、前端类型检查和单元测试通过；真实 MySQL/Flyway、受控协作运行及浏览器验收未执行，不能判定端到端 DoD 完成。

### 本轮验证记录

| 层级 | 实际命令/结果 | 状态 |
| --- | --- | --- |
| 前端类型检查 | `npm --prefix haizhuo-brain-web run type-check`：exit 0，`vue-tsc --noEmit` 通过 | 通过 |
| 前端单元测试 | `npm --prefix haizhuo-brain-web run test:unit`：35/35 通过；包含已加载分页重读、消息身份和安全脱敏用例 | 通过 |
| Diff 格式 | `git diff --check`：exit 0；仅报告共享工作树 LF/CRLF 转换提示 | 通过 |
| Java 平台/基础设施/API 定向编译与测试 | 最新统一 Maven 选择集包含 `RunProgressQueryServiceTest` 11/11、`JdbcRunProgressQueryStoreTest` 4/4、`JdbcTeamExecutionProgressTest` 2/2 及 API/controller 定向类；265项总计中0失败/错误 | 通过（选择集） |
| HTTP 路由、H2 查询与安全关系测试 | Controller/查询直接单测及 H2 通过；未启动真实 HTTP 服务 | 局部通过；HTTP 未验证 |
| 真实 MySQL/Flyway | 未连接、未执行；未验证迁移后真实 MySQL 查询计划与隔离级别行为 | 未验证 |
| 浏览器/受控协作运行 | 未执行；未验证真实 SSE 刷新、浏览器点击结果、终态及断线恢复 | 未验证 |
