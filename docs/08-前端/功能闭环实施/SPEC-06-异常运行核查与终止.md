# SPEC-06 异常运行核查与终止

状态：**FE-06 查询、持久投影、管理操作界面及路由已落地；本轮 API/基础设施定向 Java 测试通过；HTTP 权限/CSRF、真实 MySQL 并发、浏览器流程及外部停止证据未验收**。编号：FE-06。基线：2026-10-09，HEAD `eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`。

本规格补管理查询、证据核查流程和管理界面。终止写接口已有，但它不会停止旧进程，也没有自动核验停止证据。公共契约见 [SPEC-00](SPEC-00-公共契约与实施约定.md)，运行事件展示见 [SPEC-02](SPEC-02-会话事件与完整结果.md) 与 [SPEC-03](SPEC-03-实时消息恢复与渲染.md)。

## 1. 范围与基线

- 管理员发现 RECOVERY_REQUIRED Run，查看安全执行事实，确认旧执行已停止后审计终止。
- 展示终止后 Session 占用解除及后续排队事实；复用既有平台队列，不新建恢复调度器。
- 不实现“任意运行强杀”“自动推断安全停止”“自动重新执行”“跳过旧Writer隔离”。
- 普通用户看到待核查说明与已终止状态，不获得 fence、worker、管理证据和内部协作正文。

| 当前证据 | 已有行为 | 待补齐 |
| --- | --- | --- |
| [RunState](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunState.java) | RECOVERY_REQUIRED 占会话活动槽，终态包含 TERMINATED | 管理员列表/详情与操作入口 |
| [RunRecoveryAdministrationController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/admin/RunRecoveryAdministrationController.java) | 管理终止 POST、请求字段限制；本切片新增独立安全查询Controller | 查询 API 已实现；终止接口仍沿用已有POST |
| [RunRecoveryAdministrationService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunRecoveryAdministrationService.java) | 必填证据引用与 reason | 引用非空不等于停止事实已核验 |
| [JdbcRunRecoveryStore](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunRecoveryStore.java) | 锁、fence、幂等审计、终态/事件同事务 | 新增独立只读安全投影；未修改既有终止写链 |
| [JdbcRunExecutionStore](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunExecutionStore.java) | 非稳定执行租约失效进入待核查 | 不能由租约失效推断外部动作已停止 |
| [V26恢复审计](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V26__run_recovery_action_audit.sql) | 保存 requestId、操作者、fence、引用和原因 | 管理查询；跨域审计页集成 |

## 2. 页面流程与状态

已新增独立管理页组件 `RunRecoveryPanel.vue`；主任务负责注册 `/admin/run-recovery` 路由和菜单。页面默认只列待核查记录，另可筛选已终止记录。
列表显示 runId、Session、属主、员工/冻结版本、runtimeProfile、failureCode、创建/开始时间及待核查时长。
详情分为业务状态、最新 attempt、核查时间线、终止记录和同Session活动槽/排队事实；不默认展示原始输入或结果正文。每次选择/刷新都启动新详情读取，较新选择的代次会丢弃旧请求迟到响应。
管理员从详情读取 workerLabel、attemptId、heartbeatAt、leaseExpiresAt、expectedFenceToken。
“确认停止证据后终止”入口仅在 RECOVERY_REQUIRED、非 LEGACY_STABLE、有正数 fence 时开放。
第一步明确旧 worker、子执行和外部请求是否停止；运维系统执行停止操作，本页面仅记录证据引用。页面明确“TERMINATED仅表示平台状态结束，不证明旧进程或外部动作已停止”。
第二步填写受控证据引用与原因，人工确认引用对应此 runId、attemptId、fence 且覆盖全部旧Writer。
第三步再次回读详情；若 state/fence 改变，清除确认并要求重新核查，不自动替换 fence 后提交。
第四步提交既有终止接口；响应成功后回读详情与 Session/队列状态，再显示平台状态“已终止”；不据此推断旧执行停止。
前端确认只是操作流程，不是服务端可靠证明；API当前只能验证引用非空、状态和 fence。
页面明确显示“此操作记录并结束平台待核查 Run；平台不会据此确认旧执行或外部动作已停止”。
终止按钮不可用于 RUNNING、WAITING_TOOL、WAITING_CONFIRMATION、CANCELLING 或普通取消替代。
提交成功后保留部分公开输出，标注未形成完整成功结果；不伪造成功回答或创建新 Run。
终止后若队列仍未推进，展示当前事实与刷新入口；不得仅凭接口成功宣称下一轮已开始。
状态：加载、空列表、待核查、核查中、证据待确认、提交中、已终止、冲突、未知提交结果、依赖异常。
权限失效立即关闭表单和清除管理详情；已打开页面不能沿用撤销前的管理员能力。

## 3. 已有终止契约

POST `/api/admin/v1/runs/{runId}/recovery/terminate`；管理认证及 CSRF，actor来自可信主体。
runId：必填字符串≤36；JSON请求字段如下。

| 字段 | 类型/限制 | 含义 |
| --- | --- | --- |
| expectedFenceToken | positive long | 从管理详情取得的待核查执行代次 |
| requestId | 必填字符串≤128 | 当前确认操作的稳定幂等标识 |
| stopEvidenceReference | 必填字符串≤512 | 外部停止证据引用，不是证据正文 |
| reason | 必填字符串≤500 | 操作原因；禁止密钥、个人敏感正文 |

返回：`{runId,state,finishedAt,requestId}`；state成功为TERMINATED。
事务顺序：request重放检查→锁Run/Session边界→复查重放→检查Run状态/profile→匹配attempt fence→写审计→attempt CANCELLED→Run TERMINATED→追加RUN_RECOVERY_TERMINATED。
同 requestId、同 actor/run/fence/evidence/reason 返回既有结果；任一内容不同构成冲突。
LEGACY_STABLE 不使用该管理终止流程；页面不能仅按状态允许操作。
当前IllegalArgumentException映射400 INVALID_REQUEST、IllegalStateException映射409 STATE_CONFLICT；资源缺失未统一映射404。
实施可增加类型化异常和安全错误码，同时保留现有调用方兼容行为；不得把所有失败都显示为“已终止”。

## 4. 安全管理查询实现状态

以下只读接口与后端白名单投影已实现；页面组件已消费接口，但路由/菜单接入由主任务统一处理。写入继续复用既有终止接口。

| 方法与路径 | 请求/响应 |
| --- | --- |
| GET `/api/admin/v1/runs/recovery` | state=`RECOVERY_REQUIRED\|TERMINATED`、userId?、employeeId?、createdFrom?、createdTo?、cursor?、limit? |
| GET `/api/admin/v1/runs/{runId}/recovery` | RecoveryRunDetail；仅核查相关安全事实 |

列表返回 `{items,nextCursor,hasMore}`；limit默认20最大100；createdAt、runId倒序稳定分页。
游标为不透明Base64URL载荷并绑定可信操作者及筛选摘要；按`createdAt,runId`倒序分页，待核查时间用于展示，不能随刷新改变排序键。数据库查询继续受 `/api/admin/**` 管理角色与身份刷新规则保护。
RecoveryRunSummary：runId、sessionId、ownerUserId、employeeId、definitionVersionId、runtimeProfile、state、failureCode?、createdAt、startedAt?、finishedAt?、recoveryRequiredAt?。
recoveryRequiredAt 从首次待核查持久事件确定；旧记录无法确定时null并标记UNKNOWN，不使用当前时间补造。
RecoveryRunDetail 增加 latestAttempt?、terminationEligibility、publicEventSummary、recoveryActions、observedAt。
latestAttempt：attemptId、attemptNo、workerLabel、fenceToken、state、heartbeatAt、leaseExpiresAt、startedAt、finishedAt?；不含 leaseToken。
terminationEligibility：allowed、reasonCode、expectedFenceToken?；由服务端计算，不由浏览器比较枚举自行判权。
reasonCode：ELIGIBLE、NOT_RECOVERY_REQUIRED、LEGACY_PROFILE、ATTEMPT_MISSING、FENCE_UNAVAILABLE；allowed仅表示数据库前置成立，不表示旧执行已停止。
publicEventSummary 仅安全状态事件；不查询 INTERNAL正文、思考链、原始工具参数、团队 inbox。
recoveryActions：requestId、actorUserId、expectedFenceToken、reason、stopEvidenceReferenceLabel、createdAt；仅返回通用证据引用标签，不返回原始受控引用或证据正文。
详情权限按 /api/admin/** 规则；跨租户管理范围必须由可信管理策略判定，不能使用浏览器tenantId扩权。
查询不存在返回404 `RECOVERY_RUN_NOT_FOUND`；依赖异常503 `RECOVERY_QUERY_UNAVAILABLE`；不能把查不到视为已经完成。API层对这两个错误码和管理响应形状已有定向控制器测试。
新增写异常目标：409 `RECOVERY_STATE_CHANGED`、`RECOVERY_FENCE_CHANGED`、`RECOVERY_REQUEST_CONFLICT`；400 `RECOVERY_EVIDENCE_REQUIRED`。
401/403清理管理缓存；503保留输入但不允许把失败请求标记成功；API错误响应不包含内部SQL或主机凭据。

## 5. 幂等、失败与竞态

每次人工核查生成一个 requestId；提交超时后保留完全相同的请求，不生成新id、不修改payload。
详情读取按选择代次保护迟到响应；管理员在Run A读取未完成时切换到Run B，B会立即启动独立请求，A的迟到响应不得覆盖B。
刷新详情确认是否已完成；需要重试时使用同requestId重放，避免双击和网络重试产生两条处置。
发生409后刷新最新详情，显示具体安全原因；若fence/state变化，废弃当前确认和原未提交操作。
终止与 worker 写入竞态继续使用既有 Run/attempt fence和事务；新的查询不拥有队列或执行状态。
数据库 fence 能拒绝旧状态写入，但无法撤销已经触发的第三方动作；外部停止核查是必要人工条件。
leaseExpiresAt早于现在、heartbeat停止、进程列表查无worker，都不能单独构成“外部副作用已停止”的证明。
若不能确认全部旧Writer/子执行/外部请求停止，则保持 RECOVERY_REQUIRED，不释放活动槽。
证据应来自可信运维记录或提供方确定状态；客户端不上传任意脚本，不触发远程kill或抓取任意链接。
同管理员并发操作、两名管理员同时终止和角色撤销必须有契约测试；只有一次事务提交有效。
已TERMINATED详情仍可读审计；对相同payload的幂等请求可重放，其他新终止操作不可再次执行。
与 [SPEC-10](SPEC-10-审计观测与运维连接.md) 统一查询同一恢复审计事实，不复制恢复动作表。
与 SPEC-02/SPEC-03 约定 RUN_RECOVERY_TERMINATED 更新公开运行状态、结束pending气泡，并保持部分结果标签。

## 6. 模块与文件入口

| 层 | 入口/职责 |
| --- | --- |
| web | 已新增 `src/views/admin/RunRecoveryPanel.vue`、`src/api/runRecovery.ts` 和证据确认流程；路由/菜单待主任务接入 |
| api | 复用 RunRecoveryAdministrationController；新增 RecoveryRunQueryController 和安全DTO |
| platform | 复用 RunRecoveryAdministrationService/RunRecoveryStore；新增 RecoveryRunQueryService/查询端口 |
| infrastructure | 复用 JdbcRunRecoveryStore；新增 JdbcRecoveryRunQueryRepository，按白名单投影attempt/事件/审计 |
| bootstrap/security | 保留 /api/admin/** 与身份刷新；只装配查询端口，不添加第二套恢复Worker |

所有实现符号变更前跑 GitNexus impact；图谱空集/UNKNOWN需源码补证，HIGH/CRITICAL先报告风险。
AgentScope执行与状态存储保持原生扩展；平台继续负责Run恢复语义、fence和审计边界。

## 7. 实施步骤、迁移与回退

1. FE-06-S1：已完成安全查询模型/DTO、错误码和白名单只读投影；不返回leaseToken或原始stopEvidenceReference。
2. FE-06-S2：已完成列表/详情、稳定游标及服务端终止资格判断；H2/服务测试覆盖过滤分页、缺失/旧attempt、队列事实与幂等写链，HTTP/权限边界仍未实测。
3. FE-06-S3：已完成独立管理页面组件、证据确认、同requestId未知结果重试和状态/fence复核；路由/菜单接入由主任务统一处理，浏览器验收未执行。
4. FE-06-S4：已回读Session活动槽及排队Run的持久事实；普通用户终态气泡由FE-02/FE-03负责，尚无浏览器联验。
5. FE-06-S5：待真实MySQL竞争/回滚、旧Writer隔离及外部停止证据演练。

查询首版复用V11/V25/V26事实，不改变核心Run状态模型，不新增迁移；当前仓库V35/V36由其他切片占用，与FE-06无关。
若需保存证据核验类型/核验操作者，新增关联核验表并保留原stopEvidenceReference契约；本版不伪造自动核验标志。
迁移号实施时统一分配，禁止改写已执行V26或重写历史证据、actor和fence。
部署先后端查询、后前端入口；关闭管理入口可回退页面，既有终止审计与终态不得回滚。
不将TERMINATED恢复为RUNNING，不把RECOVERY_REQUIRED批量改成FAILED来释放槽。
查询依赖暂不可用时停止新处置、保留既有事实；人工核查完成前不启用自动恢复重执行。

## 8. Given / When / Then 验收

| 编号 | Given / When / Then |
| --- | --- |
| FE-06-A01 | Given管理员与待核查Run；When打开详情；Then正确显示profile/fence/安全attempt事实，无leaseToken |
| FE-06-A02 | Given普通用户或撤销管理员角色；When查询/终止；Then403且普通Session响应无管理字段 |
| FE-06-A03 | Given匹配fence且外部停止证据已核查；When终止；ThenRun TERMINATED、attempt CANCELLED、审计与持久事件原子提交 |
| FE-06-A04 | Given租约过期但旧Writer未确认停止；When打开表单；Then不能把过期当停止证据并且不得无证据释放槽 |
| FE-06-A05 | Given详情回读后fence改变；When提交旧fence；Then409、无状态改变且要求重新核查 |
| FE-06-A06 | Given同requestId同payload并发/超时重试；When重放；Then相同终态且仅一个审计动作 |
| FE-06-A07 | Given同requestId不同actor或payload；When提交；Then409且原审计不可覆盖 |
| FE-06-A08 | GivenLEGACY_STABLE或普通RUNNING；When终止；Then服务端拒绝，界面不提供可用操作 |
| FE-06-A09 | Given待核查占槽及排队Run；When核查终止后刷新；Then槽解除，后续执行由原队列推进，消息展示TERMINATED |
| FE-06-A10 | GivenSQL/依赖故障发生在事务中；When终止；Then审计、attempt、Run、事件全部回滚，没有假成功 |

## 9. 验证层级与完成条件

FE-06 的实现级定向测试与跨层验收分开记录。FE-06-A01–A10 当前证据如下；任何未执行层级不视为通过。

| 编号 | 当前证据与边界 |
| --- | --- |
| FE-06-A01 | H2 查询仓储测试覆盖profile、fence、attempt和leaseToken脱敏；HTTP/浏览器未执行。 |
| FE-06-A02 | 管理路由由既有 `/api/admin/**` 规则保护，身份刷新在授权前执行，属于源码核对；401/403/CSRF与普通用户HTTP隔离未执行。 |
| FE-06-A03 | 既有H2终止Store定向测试覆盖匹配fence、Run/attempt/审计/终止事件原子状态及同请求重放；没有外部停止实证。 |
| FE-06-A04 | 前端单测确认过期lease/旧heartbeat不能替代显式人工确认；浏览器操作未执行。 |
| FE-06-A05 | 页面实施提交前详情回读及state/fence比较；没有真实并发HTTP或浏览器竞态验收。详情A→B迟到响应由generation单测覆盖。 |
| FE-06-A06 | H2覆盖顺序同payload幂等重放；双管理员并发和超时HTTP重试未执行。 |
| FE-06-A07 | H2覆盖同requestId的不同payload冲突且原审计不被覆盖；不同actor冲突HTTP路径未执行。 |
| FE-06-A08 | H2查询覆盖LEGACY_STABLE资格关闭，现有Store测试覆盖非恢复态拒绝；真实HTTP/浏览器未执行。 |
| FE-06-A09 | H2覆盖活动槽和排队Run事实读取且查询不提升队列状态；真实队列后续推进与FE-02/FE-03消息终态未联验。 |
| FE-06-A10 | 未注入事务中SQL故障；本项未验收通过。 |

本轮已执行层级：FE-06 API与仓储定向Java测试、包含详情代次竞态的前端Node单测、构建命令（当前失败，错误位于FE-04定义页类型）。最终数字与命令见[实施测试报告](../../07-测试报告/2026-10-09功能闭环实施报告.md)。不修改历史迁移；FE-06不新增迁移。

未执行：真实MySQL事务/双管理员/旧fence/超时重放/故障回滚；服务HTTP认证、CSRF、权限撤销；浏览器管理流程和Run终态消息联验；外部停止证据演练。`TERMINATED`只表示平台持久状态结束，绝不证明旧进程、子执行或第三方动作停止。活动槽释放和下一轮实际开始必须分别依据数据库事实确认。
