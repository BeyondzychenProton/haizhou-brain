# SPEC-05 渠道管理与投递核查

状态：**S1 账号/身份命令保护、审计、revision/CAS、装载回执，S2 只读投递查询，S3 V46 claim 围栏与尝试历史均已编码；相关 platform/infrastructure/API/Bootstrap 定向测试本轮通过。真实 MySQL/Flyway 未执行；S4–S5 真实 sender 与证据处置尚未实现，写入口保持关闭**。编号：FE-05。基线：2026-10-09，起始 HEAD `eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`。

本规格是实施契约，不代表页面、管理查询、真实 IM 或投递核查已经交付。公共约定见 [SPEC-00](SPEC-00-公共契约与实施约定.md)，生产接入门槛见 [SPEC-12](SPEC-12-开放门槛与生产接入.md)。

## 1. 范围与当前事实

- 管理员维护渠道账号、默认员工、会话隔离、外部身份绑定，并核对实际装载状态。
- 为既有 Delivery Outbox 增加安全查询、详情和有证据的核查处置；Run 成功与送达状态分别展示。
- 不新增第二套受理队列、Session 路由或投递队列；不把模拟发送器的 DELIVERED 宣称为真实提供方送达。
- 本切片不开放原始回调正文、replyTarget、凭据内容、私有工具参数及内部协作消息。

源码基线：

| 证据 | 已有行为 | 缺口 |
| --- | --- | --- |
| [ChannelAdminController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/channel/ChannelAdminController.java) | 8 个账号、身份、运行时 API | 无管理页面；写操作当前主要记日志 |
| [ChannelAdministrationService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/channel/ChannelAdministrationService.java) | 可信操作者、员工/用户校验、写后刷新注册表 | 缺结构化操作审计和分页查询 |
| [JdbcChannelDeliveryOutbox](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/channel/JdbcChannelDeliveryOutbox.java) | 持久意图、幂等键、认领、SENDING 过期转 UNCERTAIN | 无管理查询/处置；结果更新无 claim 代次 |
| [ChannelDeliveryWorker](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/channel/ChannelDeliveryWorker.java) | 失败分级，异常记 UNCERTAIN | 不确定结果不自动重发；管理闭环尚缺 |
| [ChannelConfiguration](../../../haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/configuration/ChannelConfiguration.java) | 原生 ChannelManager 注册、模拟 sender | 真实 IM 协议与送达验收另行完成 |

### 1.1 当前实施校准

- **已实现：FE-05-S2 只读查询部分。** 新增投递列表/详情 GET、受限筛选和稳定游标、JDBC 脱敏投影及 V36 查询索引；页面组件为 [`ChannelManagementView.vue`](../../../haizhuo-brain-web/src/views/admin/ChannelManagementView.vue)，由主任务接入 Admin 管理页“渠道管理与投递核查”页签。
- **FE-05-S1 代码已实现，验收未全通过。** 账号创建、更新、身份绑定和新增 POST 撤销接受 requestId/reason；账号 PATCH 的新客户端路径带 expectedRevision，并在 JDBC 通过 revision 条件更新。旧 POST/PATCH/PUT/DELETE 仍兼容，缺元数据的旧写入以 `LEGACY_CLIENT` 来源审计且不伪造 reason/requestId；旧 PATCH 仍是兼容窗口内的非 CAS 写入，身份写入也保留顺序语义，没有宣称 identity CAS。
- 新 `ChannelManagementCommandExecutor` 将业务变化、管理审计和 requestId 回执放入同一事务；同操作者/同 requestId/同内容回放保存的响应，不重写业务或审计；不同内容映射为 409 `STATE_CONFLICT`。审计持久化 hash 与 allowlisted safeChanges，不存原始请求正文或凭据材料。迁移 V39 为账号增加 revision，并新增审计、回执表。
- POST/PATCH 在命令事务提交后刷新运行时，响应以 `runtimeRefresh` 区分 APPLIED/PENDING/FAILED；刷新或随后观测失败也回传已提交的账号版本和安全错误码，不以 500 诱发重复创建。GET runtime 记录当前进程 instanceLabel/observedAt 及从 AgentScope `ChannelManager.register/applyRoutingConfig` 成功路径追踪的 loadedRevision；UNKNOWN 不冒充已装载，多实例收敛尚未验证。
- 管理页的账号/身份写入现传 requestId/reason，账号编辑提交 expectedRevision；同一表单意图在网络失败时保留 requestId，CAS 冲突先回读账号列表并关闭旧编辑态，要求重新打开核对。该行为没有浏览器实测。
- **明确关闭：投递处置。** UI 没有 claim、重试或核查提交控件；`channels.ts` 未提供这些写 API；新投递管理 Controller 仅暴露 GET。S3 只读尝试历史返回已持久化字段；旧尝试没有记录时标记 `LEGACY_UNRECORDED`/`PARTIAL`，核查记录仍为空，不伪造历史。模拟 provider 只标 `SIMULATED`，其余来源标 `UNVERIFIED`。
- 旧账号写 API 仍可用作受控兼容窗口；这不等于并发身份 CAS 已存在，也不等于投递处置写能力已开放。真实 IM、证据解析、claim fencing 与外部发送状态均未验证。
- 本轮 Java 定向验证：platform `ChannelAdministrationServiceTest` 8/8、Infrastructure 账号 CAS 1/1 与命令执行器 3/3、API `ChannelAdminControllerTest` 10/10 通过；Bootstrap `AgentScopeChannelRuntimeTest` 历史结果 6/7。原失败断言 `runtime.channels().isEmpty()` 把运行时管理投影误当作原生 registry：`AgentScopeChannelRuntime.channels()` 会为仍有配置记录的禁用 provider 合成 `started=false`、`bindingCount=0`、`UNLOADED` 状态。现在 `disabledBindingUnloadsItsProvider` 注入同一 AgentScope `ChannelManager` 测试实例，分别断言启用时 `channelIds()` 含渠道、禁用后 `channelIds()` 为空且 `getChannel()` 不存在，并继续断言管理投影的卸载状态；未削弱原生卸载验证。AgentScope Harness 2.0.3 本地 sources JAR 的 `ChannelManager.unregister` 实现先从 registry 移除再调用 stop，`channelIds()`/`getChannel()` 是其真实查询签名。修订后尚未重跑 Maven，不能计为通过；等待统一复跑。此次未启动 HTTP 服务、使用真实 MySQL、浏览器或真实提供方。

## 2. 页面与状态

拟新增管理入口 `/admin/channels`，包含“账号与身份”“运行时”“投递”三个页签。
账号列表显示 bindingId、provider、外部账号、默认员工、隔离方式、配置启用状态；凭据仅显示引用标签。
创建表单提交成功后回读账号并刷新运行时；“配置已保存”与“运行时已装载”使用两个独立状态。
编辑表单仅开放 enabled、defaultEmployeeId、sessionScope；provider、外部账号、凭据引用为只读。
既有 PATCH 不支持修改这些只读字段；未来轮换能力依赖明确后端契约，不能由前端拼出未实现请求。
身份页显示外部用户、平台用户、LINKED/REVOKED、绑定及更新时间；平台用户通过受保护目录选择。
撤销身份前显示作用对象；成功后回读状态，避免把接口成功直接当作列表已同步。
运行时页显示 channelId、defaultAgentId、sessionScope、bindingCount、started 与观测时间。
enabled 是持久配置；started 是运行时事实，缺少运行时条目显示“未装载/未观测”。
多用户账号默认 PER_PEER；MAIN 不开放给普通创建流程，仅受控例外说明其共享上下文影响。
隔离变化只影响后续会话匹配；旧 Session 的身份、冻结版本与映射保持，迁移旧映射必须另行设计。
投递列表筛选状态、bindingId、provider、runId、时间区间；详情显示安全摘要、尝试记录与核查记录。
UNCERTAIN 显示“等待外部事实核查”，不显示一键重发；DELIVERED 显示提供方/模拟证据来源。
页面状态：初始加载、空列表、列表可用、刷新中、依赖异常、权限失效、保存中、冲突待刷新。
列表刷新保留筛选和选中项；切换页签取消旧请求，迟到响应不能覆盖新筛选结果。
当前投递页是只读查询；账号/身份写命令使用 requestId/reason 与持久化审计，账号更新使用 CAS。页面没有投递 claim、核查或重试表单。

## 3. 已有 HTTP 契约

以下接口保留原数组/响应结构；方法、路径及字段以当前 Controller 为基线。

| 方法与路径 | 请求/响应要点 |
| --- | --- |
| GET `/api/admin/v1/channels/accounts` | AccountResponse[] |
| POST `/api/admin/v1/channels/accounts` | 创建请求；返回 AccountResponse |
| GET `/api/admin/v1/channels/accounts/{bindingId}` | AccountResponse |
| PATCH `/api/admin/v1/channels/accounts/{bindingId}` | enabled?:boolean、defaultEmployeeId?:long、sessionScope?:enum |
| GET `/api/admin/v1/channels/accounts/{bindingId}/identities` | IdentityResponse[] |
| PUT `/api/admin/v1/channels/accounts/{bindingId}/identities/{externalUserId}` | `{userId:positive long}`；返回 IdentityResponse |
| DELETE `/api/admin/v1/channels/accounts/{bindingId}/identities/{externalUserId}` | 无正文；回读绑定列表确认 |
| GET `/api/admin/v1/channels/runtime` | RuntimeChannelResponse[] |

CreateAccountRequest：bindingId 必填≤64；tenantId 为 positive long；provider 必填≤32；externalAccountKey 必填≤128；credentialRef 必填≤256；defaultEmployeeId 为 positive long；sessionScope 可选；enabled 可选。
SessionScope：MAIN、PER_PEER、PER_CHANNEL_PEER、PER_ACCOUNT_CHANNEL_PEER；缺省采用 PER_PEER。
AccountResponse：bindingId、tenantId、provider、externalAccountKey、credentialRef、defaultEmployeeId、sessionScope、enabled。
IdentityResponse：bindingId、externalUserId、userId、state、linkedAt、updatedAt；路径外部 ID 必须按单个路径片段编码。
RuntimeChannelResponse：channelId、defaultAgentId、sessionScope、bindingCount、started。
所有管理请求复用认证及 CSRF；操作者来自服务端主体，不能由请求提供 actorUserId。
本次UI租户固定为服务端当前管理范围1；不提供任意tenantId切换。已有接口的positive long字段保留，不宣称企业多租户管理已完成。

### 3.1 S1命令元数据增强与旧客户端兼容

现有POST账号、PATCH账号、PUT身份没有reason/requestId。本次新增顶层控制字段requestId（1–128）、reason（1–500），不改变账号三个可变业务字段或身份userId；新UI必须填写并复用同一意图键。撤销新增POST `/api/admin/v1/channels/accounts/{bindingId}/identities/{externalUserId}/revoke`，JSON为requestId/reason；旧DELETE保留兼容，避免将原因放入URL或依赖DELETE正文。
新命令由既有领域服务处理，新增回执与业务/渠道审计同事务。回执唯一键为actorUserId+requestId，hash覆盖动作、bindingId、externalUserId和规范化正文；同键同内容重放，不重复写业务/审计；异内容409 STATE_CONFLICT。账号PATCH同时校验expectedRevision，身份操作首版保留既有顺序写入语义，不假称已有identity CAS。
受控旧客户端窗口允许缺少元数据及旧DELETE，审计标来源LEGACY_CLIENT、reason/requestId=null，不伪造填写内容；窗口结束要求新控制字段及新撤销端点。原8个已有接口表是基线，以上为S1待增强/新增。普通后台写入共用服务校验，不能只前端限制理由。

## 4. 拟新增查询及处置契约

新增接口均为 PLATFORM_ADMIN 专用，响应遵循 SPEC-00。列表和详情 GET 已实现；核查写接口仍未实现且保持关闭。

| 方法与路径 | 契约 |
| --- | --- |
| GET `/api/admin/v1/channels/deliveries` | state、bindingId、provider、runId、createdFrom、createdTo、cursor、limit |
| GET `/api/admin/v1/channels/deliveries/{deliveryId}` | DeliveryDetail，含安全尝试与核查历史 |
| POST `/api/admin/v1/channels/deliveries/{deliveryId}/verification` | 有证据地确认送达或确认最终未送达；返回最新 DeliveryDetail |

当前实际仅实现上述两个 GET 接口；没有 POST 核查、claim、重试或状态改写入口。FE-05-S3 增加了列表/详情 `revision` 和已记录尝试历史读取；存量聚合次数没有可恢复的逐次明细，仍返回 `LEGACY_UNRECORDED` 或 `PARTIAL`，verification 列表为空。以上是实现边界，不应按完整目标契约读取。

列表返回 `{items,nextCursor,hasMore}`；limit 默认20，最大100；createdAt、deliveryId 倒序稳定分页。
游标绑定筛选、管理员权限范围及时间上界；未知状态、非法时间和不匹配游标返回安全参数错误。
DeliverySummary：deliveryId、runId、sessionId、bindingId、provider、state、attempts、resultId?、contentPreview、externalMessageId?、lastErrorCode?、createdAt、updatedAt、sendingExpiresAt?、revision、evidenceSource。
DeliveryDetail 增加 attemptsHistory、verifications、runState；不返回原始 replyTarget、idempotencyKey、正文、凭据或提供方错误响应。
attemptsHistory 项：attemptNo、claimGeneration、startedAt、finishedAt?、status、safeErrorCode?；旧数据标记 `LEGACY_UNRECORDED`，有部分新旧明细时标记 `PARTIAL`，详情最多返回最近100条，不能补造历史。
verification 请求：requestId≤128、expectedRevision>0、decision=`DELIVERED|NOT_DELIVERED`、evidenceReference≤512、reason≤500、externalMessageId?≤191。
evidenceReference 只引用受控工单/回执证据，不接受密钥、回调全文、浏览器任意 URL 抓取。
响应包含 verificationId、decision、actorUserId、occurredAt、evidenceReferenceLabel；详细证据由已有受控系统查看。
同 requestId 同内容重放返回既有结果；不同内容返回409 `DELIVERY_REQUEST_CONFLICT`。
资源不存在404 `DELIVERY_NOT_FOUND`；revision/state/claim 冲突409 `DELIVERY_CHANGED`；查询依赖不可用503 `DELIVERY_QUERY_UNAVAILABLE`。
这些类型化码是新增接口目标，不改写现有全局 ApiError 已有行为的事实。

## 5. 处置安全与竞态

数据库是 Delivery 状态的唯一所有者；核查和 sender 结果写入共享同一条件更新协议。
FE-05-S3 已实现 Outbox 内部 claimGeneration、随机 claimToken 与 revision。claim 返回 DeliveryClaim；Worker 的 `deliverNext()` 保持原签名，并把同一 claim 带到 sender 结果写入。
recordOutcome 同时匹配 deliveryId、SENDING、claimGeneration、claimToken；返回 `APPLIED`/`STALE_CLAIM`，不再提供按 deliveryId 单独写结果的内部路径。
租约过期转 UNCERTAIN 时递增 revision、清除活动 token/expiry 并结束该次尝试；迟到结果不能覆盖状态。管理员核查仍未实现，expectedRevision 尚无处置写入口消费。
FE-05-S3 已建立平台投递尝试表及只读详情映射；核查动作表尚未建立。重试（若未来由已验证核查开放）仍使用原 Outbox idempotencyKey，不产生第二个投递意图。
claim 隔离能保护数据库，不能撤回已送到外部的请求；旧 sender 未停止或外部请求仍可能生效时，不开放安全重发。
UNCERTAIN→DELIVERED：提供方确认回执或可核验外部消息证据；记录消息标识和证据来源。
UNCERTAIN→RETRYABLE_FAILURE：提供方明确确认请求最终未送达，且旧 sender/请求已停止或提供方保证同幂等键重复提交不重复生效；缺任一条件保持 UNCERTAIN。
NOT_DELIVERED 不是“没找到消息”的推断；弱查询、超时、查无记录或管理员猜测都不能据此重新认领。
PERMANENT_FAILURE 先修配置；本规格首版只允许查询，不开放绕过校验的强制重发或修改目标。
PENDING/SENDING/DELIVERED 不接受核查改写；并发完成和管理员操作只有一个 CAS 可成功，失败方回读事实。
在真实 sender 核查适配未验收前，verification 写接口由部署开关关闭；只读页面仍可实施。
账号写入与结构化审计在同一事务；运行时刷新失败需反馈“保存成功、装载待核查”，不撤销已经持久化事实。
新增刷新结果契约需要显式区分持久化成功和装载成功；不得让超时前端自动再次创建账号。
具体增强保持原AccountResponse字段，POST/PATCH成功响应追加revision及runtimeRefresh={status:APPLIED/PENDING/FAILED,requestedRevision,loadedRevision?,instanceLabel,observedAt,safeErrorCode?}。业务提交后刷新失败仍返回持久化成功与FAILED，不用模糊500促使重复创建；真正事务失败才返回写失败。
RuntimeChannelResponse追加instanceLabel、observedAt、accountSnapshots:[{bindingId,configuredRevision,loadedRevision?,loadState}]。原生注册成功后才更新此实例已装载revision；started=true不能替代版本匹配。GET账号返回配置revision，GET runtime提供实际装载事实，页面显示“保存r2，当前实例仍r1”。无观测记录为UNKNOWN，不填最新revision冒充已加载。
装载观测是本实例状态，不宣称整个集群同步；生产多实例需FE-12验证各节点既有注册表刷新/配置检测。刷新失败只重试既有装载端口，不重写账号或创建Run；日志仅保存受控错误码。
并发账号编辑的新增目标：AccountResponse加入revision，PATCH加入expectedRevision条件元数据；业务可变字段仍只有enabled/defaultEmployeeId/sessionScope。
当前已有PATCH没有该条件保护；FE-05-S1同时补正数revision与服务端CAS，新UI必须带expectedRevision，冲突返回409 CHANNEL_ACCOUNT_CHANGED并回读。
旧客户端无expectedRevision仅在受控兼容窗口保留既有行为，关闭窗口后要求条件元数据；不得宣称仅靠前端回读能防止覆盖。
V46 已新增 `platform_channel_delivery_attempt`：deliveryId/attemptNo 唯一，记录 claimGeneration/claimToken、开始/结束时间和安全结果；token 仅内部存取。
拟新增platform_channel_delivery_verification：requestId唯一，deliveryId、expectedRevision、decision、证据引用、actor、reason、payloadHash、createdAt。
拟新增platform_channel_management_audit：auditId、bindingId、action、actor、requestId?、reason?、clientSource、safeChanges、createdAt；与账号/身份变化同事务写入，接SPEC-10查询适配器。拟新增platform_channel_management_receipt保存actor/requestId、payloadHash及安全结果引用，不存凭据或重复审计正文。
核查证据解析端口DeliveryVerificationEvidenceResolver只读取受控证据记录，返回finalOutcome、confirmedFinal、oldSenderStopped、providerIdempotent与安全回执标识。
无适配器/无权读取/事实仍未知时禁止状态改写；不能将非空引用直接转换为确认未送达，也不抓取管理员任意URL。

## 6. 模块与文件入口

| 层 | 入口与实施职责 |
| --- | --- |
| web | `src/views/admin/ChannelManagementView.vue`、`src/api/channels.ts`；已由主任务接入 AdminView 页签；投递部分只读 |
| api | 既有 ChannelAdminController；新增 `ChannelDeliveryAdministrationController`，仅 GET 列表/详情 |
| platform | 既有 ChannelAdministrationService、ChannelDeliveryOutbox、Worker；新增只读 `ChannelDeliveryAdministrationService` 与查询端口 |
| infrastructure | `JdbcChannelDeliveryOutbox` 使用 V46 claim/revision 与尝试表；新增 `JdbcChannelDeliveryAdministrationQuery` 和 V36 查询索引；V39 持久化账号 revision、管理审计及幂等回执。投递核查仍未落库 |
| bootstrap | 复用 ChannelRuntimeAdmin/AgentScopeChannelRuntime；接部署开关和真实核查适配 |

任何上述符号实现修改前执行 GitNexus impact；HIGH/CRITICAL 风险先报告，UNKNOWN 补源码调用证据。
AgentScope 继续提供 ChannelManager 与 Channel 发送扩展；平台保留身份、Run、Outbox 与可靠处置责任。

## 7. 实施步骤与数据兼容

1. FE-05-S1：账号/身份/运行时页面和后端命令保护代码已实现；待 API/Infrastructure/Bootstrap 目标测试、HTTP 权限/CSRF、MySQL 迁移与浏览器验收。旧客户端兼容窗口的无 CAS 写入及身份顺序写入均明确保留。
2. FE-05-S2：只读投递列表/详情、分页与脱敏查询及筛选索引已实现；安全权限 HTTP 契约、浏览器和真实 MySQL 仍待验证。
3. FE-05-S3：Outbox claim 代次、revision、尝试历史、条件更新及旧 claim 竞争测试代码已实现；本轮相关 Java/H2 定向类通过。MySQL/Flyway 和多实例竞态仍待执行；真实 sender 与外部动作停止没有验收证据。
4. FE-05-S4：证据契约、幂等核查事务和管理员表单未实施；核查写入口未提供。
5. FE-05-S5：真实提供方送达探针及租约故障演练未实施；不确定投递处置保持关闭。

仅新增 Flyway 迁移，版本号实施时与全仓迁移顺序统一；不修改 V15/V21 等已执行文件。
迁移 V46 将历史 revision 初始化为1、claimGeneration按已有 attempts 计数初始化；新行从 revision=1/generation=0 开始，认领时递增。存量 SENDING 被迁移为 UNCERTAIN、清租约并递增 revision；既有聚合次数不伪造为尝试明细。
升级须先停旧投递 dispatcher 并完成旧 writer/sender 的部署切换，确认没有旧版按 deliveryId 写状态的实例后再恢复新 dispatcher；迁移本身不能停止正在执行的旧 sender。禁止新旧结果写协议混跑。
回退先关闭处置和 dispatcher；保留新表/列/审计事实，只读页可回退。旧 writer 不能直接重新上线覆盖新代次。
凭据引用的轮换与撤销遵循 SPEC-10/SPEC-12，不通过编辑账号绕过凭据治理。

## 8. Given / When / Then 验收

| 编号 | Given / When / Then |
| --- | --- |
| FE-05-A01 | Given 管理员与有效员工；When 创建 PER_PEER 账号并回读；Then 持久配置与装载状态分别正确显示 |
| FE-05-A02 | Given 普通用户或角色已撤销；When 调管理接口；Then 403且无渠道/投递数据泄漏 |
| FE-05-A03 | Given 外部身份绑定；When 撤销再入站；Then 不继续解析为原用户，管理页显示REVOKED |
| FE-05-A04 | Given 两个外部用户；When 同账号入站；Then PER_PEER 隔离，旧Session冻结版本不变 |
| FE-05-A05 | Given Run已成功、Delivery失败；When 打开详情；Then 展示两个状态且不触发Run重执行 |
| FE-05-A06 | Given SENDING过期；When回收后旧sender写结果；Then转UNCERTAIN且旧claim不覆盖新事实；S3 代码有 H2 定向测试入口，尚未在本轮执行 |
| FE-05-A07 | Given UNCERTAIN无确定证据；When尝试NOT_DELIVERED核查；Then保持状态且不会自动重发 |
| FE-05-A08 | Given确定未送达及安全停止/幂等保证；When核查；Then单次CAS允许重认领，迟到旧结果被拒绝 |
| FE-05-A09 | Given 同requestId；When同内容重放/不同内容复用；Then前者幂等、后者409且审计不重复 |
| FE-05-A10 | Given 多页和并发新增；When分页/改筛选；Then游标稳定、旧响应不覆盖，秘密字段缺席 |

## 9. 验证层级与完成条件

### FE-05-S2 既有验证记录（2026-10-09）

- **静态/源码核对：** 新 GET Controller 未实现写方法；新 DTO 查询未选择 `reply_target` 或 `idempotency_key`；模拟来源只在 provider=`simulated` 时标 `SIMULATED`，否则为 `UNVERIFIED`。管理 API 的 HTTP 权限/CSRF 本轮未发真实 HTTP 请求。
- **Java 定向：** JDK 17 下 platform/API 3 项、Infrastructure JDBC 查询 2 项通过；命令如下。测试为单元/H2投影，不是真实 MySQL。
  - `mvn -ntp -pl haizhuo-brain-api -am -Dtest=ChannelDeliveryAdministrationServiceTest,JdbcChannelDeliveryAdministrationQueryTest,ChannelDeliveryAdministrationControllerTest -DforkCount=0 -Dsurefire.failIfNoSpecifiedTests=false -Dreactor.schedulers.defaultBoundedElasticTtlSeconds=1 test`：platform 2/2、API 1/1 通过；此 reactor 不含 infrastructure，因此 JDBC 类由下一条命令单独执行。
  - `mvn -ntp -pl haizhuo-brain-infrastructure -am -Dtest=JdbcChannelDeliveryAdministrationQueryTest -DforkCount=0 -Dsurefire.failIfNoSpecifiedTests=false test`：Infrastructure JDBC 2/2 通过。
- **前端：** FE-04 稳定其并行修改并由主任务接入 AdminView 后，执行 `npm --prefix haizhuo-brain-web run build`，`vue-tsc --noEmit` 与 Vite 生产构建通过（1757 modules transformed）。有既存 router 混合导入和主 JS chunk 超过 500 KB 警告；浏览器未执行。
- **未执行：** 真实 MySQL 迁移与并发；HTTP 权限/CSRF；浏览器账号、绑定和投递查询；真实 IM、送达回执/核查； claim fence 与外部动作停止证据。

### FE-05-S1 本轮验证记录（2026-10-09）

- **静态：** 对 FE-05 Java/Vue 代码执行 `git diff --check`，无空白错误。V39 SQL 已审阅但未通过真实 MySQL/Flyway 执行。
- **Java 定向：** 使用 JDK 17.0.9 执行以下 FE-05 定向 reactor 命令：
  - PowerShell 实际命令：`$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'; & 'D:\code environment\apache-maven-3.9.14\bin\mvn.cmd' '-ntp' '-pl' 'haizhuo-brain-bootstrap' '-am' '-Dtest=ChannelAdministrationServiceTest,ChannelAdminControllerTest,JdbcChannelManagementCommandExecutorTest,JdbcChannelAdministrationStoreTest,AgentScopeChannelRuntimeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dreactor.schedulers.defaultBoundedElasticTtlSeconds=1' 'test'`（Surefire 默认 fork，未使用 `-DforkCount=0`）。
  - 本轮结果：`ChannelAdministrationServiceTest` **8/8**；`JdbcChannelAdministrationStoreTest` **1/1**；`JdbcChannelManagementCommandExecutorTest` **3/3**；`ChannelAdminControllerTest` **10/10**；`AgentScopeChannelRuntimeTest` **6/7**。全部模块完成 testCompile；Bootstrap 仅上述 1 项测试断言失败。
  - 先前 Mockito 控制器测试通过 mock Java `AuthenticatedUser` record 触发 Mockito inline 动态 agent；两次 Surefire dumpstream 记录 `Corrupted channel by directly writing to native stream in forked JVM` 及 JDK attach named-pipe 错误。该测试现改为真实 `AuthenticatedUser` 加本地 fake service/command executor；本轮默认 fork 无 attach 错误，API 10/10 正常完成。
  - Bootstrap 失败根因由测试源码、运行时投影代码、Surefire 输出和本地 AgentScope 2.0.3 sources JAR 核对：原生 registry 已注销；`AgentScopeChannelRuntime.channels()` 另外合成禁用配置的管理状态，导致 `isEmpty()` 断言不成立。现已修订为直接检查注入 registry 的 `channelIds()` 与 `getChannel()`，并保留 `UNLOADED` 管理视图断言；此修订尚未复跑，等待统一 Maven 验收。Maven 启动仍显示本机 `settings.xml:77` XML 格式 warning 和 `Access is denied` 启动器提示；未将其误报为测试通过。
- **前端：** `npm --prefix haizhuo-brain-web run test:unit` **29/29 通过**。FE-04 确认前端稳定后重跑 `npm --prefix haizhuo-brain-web run build`，`vue-tsc --noEmit` 在共享 FE-03 `src/composables/useSessionRenderTransport.ts:110` 因 TS2367 失败，Vite 未启动；此前一次类型检查通过后的 Vite 阶段另遇到 `EPERM realpath src/main.ts`。本轮没有成功生产构建。
- **未执行：** 真实 MySQL V39 迁移/并发；HTTP 权限与 CSRF；浏览器账号/身份/CAS 冲突操作；真实渠道提供方。投递 claim/核查写仍关闭。

### FE-05-S3 首次实施记录（2026-10-09）

- **实现：** `ChannelDeliveryOutbox`/`JdbcChannelDeliveryOutbox` 通过随机 claim token、递增代次与 revision 条件更新，过期租约转 `UNCERTAIN`；每次新 claim 写入尝试行，迟到或旧 claim 返回 `STALE_CLAIM`。GET 详情仅投影尝试号、代次、时间、状态和安全错误码，不查询/返回 claim token、replyTarget、idempotencyKey 或提供方响应。
- **迁移：** 新增 V46；既有行 revision 初始化为1、代次从聚合 attempts 初始化，旧 `SENDING` 转 `UNCERTAIN` 并清理 lease/token。该 SQL 尚未在真实 MySQL/Flyway 执行。
- **测试入口（首次实施时尚未运行）：** `ChannelDeliveryWorkerTest`、`JdbcChannelDeliveryTest`、`JdbcChannelDeliveryAdministrationQueryTest`、`ChannelDeliveryAdministrationControllerTest`。最新统一 Maven 复验已执行上述相关测试；结论见本节末尾补记。
- **静态检查：** `git diff --check` exit 0；本轮新增文件另行检查无行尾空白。没有运行 Maven、Vue 构建或单测。HTTP、真实 MySQL、多实例/慢 sender、浏览器和真实提供方均未验证。
- **GitNexus：** 当前索引仍对应起始 HEAD `eda9c79`。`ChannelDeliveryOutbox.claimNextDelivery#0` 与 `recordOutcome#2` 方法级 upstream 均为 CRITICAL（各 direct=1、impacted=57）；`ChannelDeliveryOutbox`、`JdbcChannelDeliveryOutbox`、`ChannelDeliveryWorker` 类级分别 CRITICAL（impacted 299/404/77，direct 6/3/4）。Worker/JDBC 具体方法和新增只读 GET route 无法由当前索引解析，记为 UNKNOWN；源码搜索确认接口调用经 Worker，JDBC 类实现接口，配置/dispatcher/probe 调用 Worker，直接 JDBC 操作位于 Outbox 测试。保留 CRITICAL 风险并以这些源码调用面核验，不用较低 shared-axis 覆盖风险结论。
- **外部门槛：** V46 应在旧版 Outbox writer 与 dispatcher 全部下线后部署；S3 只围栏数据库结果，不能撤销已发出的外部请求。claim/retry/核查 UI 与 HTTP 写入口继续关闭，真实 sender 未接入。

### FE-05 最新统一验收补记（2026-10-09）

本轮 JDK 17 bootstrap reactor 定向套件通过：`ChannelAdministrationServiceTest` 8/8、`JdbcChannelAdministrationStoreTest` 1/1、`JdbcChannelManagementCommandExecutorTest` 3/3、`ChannelAdminControllerTest` 10/10、`AgentScopeChannelRuntimeTest` 7/7、Delivery worker 3/3、Outbox JDBC 8/8、只读投影 JDBC/API 测试通过。套件总结果为 265 项、264 通过、1 跳过、0 失败/错误。V46 仍未在真实 MySQL/Flyway 执行；多实例旧 sender、HTTP CSRF、浏览器和真实提供方未验证，S4/S5 继续关闭。详见[实施报告](../../07-测试报告/2026-10-09功能闭环实施报告.md)。

- [x] 只读投递查询的服务游标、Controller 映射、JDBC SQL 顺序与脱敏单元检查通过。
- [x] FE-05-S1 命令元数据、事务审计/幂等回执、账号 revision/CAS、运行时装载观测与页面元数据代码已实现。
- [ ] FE-05-S1 API/Infrastructure/Bootstrap 定向测试、MySQL 迁移和端到端验收通过。
- [ ] MySQL 真机并发 claim、租约过期、旧 sender 迟到、重复核查、迁移兼容验证通过；H2 仅作补充。
- [ ] HTTP 权限/CSRF 与浏览器账号/绑定/查询/冲突场景通过。
- [ ] 不确定状态的证据核查、幂等写事务及真实提供方门槛通过后，才考虑开放处置。

### 9.1 验收编号状态

| 编号 | 本轮结论 |
| --- | --- |
| FE-05-A01 | **部分后端验证。** 本轮 `ChannelAdministrationServiceTest` 8/8、`JdbcChannelAdministrationStoreTest` 1/1、`ChannelAdminControllerTest` 10/10、`AgentScopeChannelRuntimeTest` 7/7 通过，包含原生 registry 卸载和 `UNLOADED` 管理视图断言；MySQL 迁移、HTTP/页面及逐实例浏览器观测未验收。 |
| FE-05-A02 | **未验收。** 新路由位于管理员 API 前缀；本轮没有 HTTP/角色撤销/CSRF 请求验证。 |
| FE-05-A03–A04 | **未验收。** 未运行入站撤权、跨用户 PER_PEER 隔离及旧 Session 冻结版本场景。 |
| FE-05-A05 | **部分后端验证。** 本轮 `JdbcChannelDeliveryTest` 8/8、只读投影 JDBC/API 测试及 Delivery worker 3/3 通过；Run `SUCCEEDED` 与 Delivery `UNCERTAIN` 分开呈现，尝试记录不泄露 token。没有 HTTP/浏览器刷新后不重执行验收。 |
| FE-05-A06 | **代码与 H2/worker 单测通过，生产验收未完成。** V46 和本轮测试覆盖租约过期转 `UNCERTAIN`、revision 增加及旧 claim 返回 `STALE_CLAIM`；真实 MySQL/Flyway、多实例和外部 sender 停止证据尚未验证。 |
| FE-05-A07–A08 | **未实施/未验收。** 证据核查适配、核查写事务及安全重认领仍未实现；投递处置写入口保持关闭。 |
| FE-05-A09 | **部分后端验证。** `JdbcChannelManagementCommandExecutorTest` 3/3 覆盖同键重放、异内容冲突、业务/审计回滚及旧客户端来源；`ChannelAdminControllerTest` 10/10 覆盖 API 命令元数据与账号 CAS 冲突响应。HTTP 幂等重放/409 尚未实测。 |
| FE-05-A10 | **部分后端验证。** service/JDBC 单测覆盖稳定排序与游标、筛选/操作者绑定、越界参数、脱敏和缺失私密字段；未验证真实并发新增、HTTP 请求权限、浏览器迟到响应保护及多页 E2E。 |
