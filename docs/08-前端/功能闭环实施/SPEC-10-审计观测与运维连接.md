# SPEC-10 审计观测与运维连接

状态：**审计/运维查询与 FE-10-S4 精确连接 revision/Run 冻结绑定已编码；本轮 Java/API/H2 选择集通过；Vault 解析器、连接目录生产写入、旧 Session pin 回填及真实模型/部署灰度未完成，连接写 gate 保持关闭**。编号：FE-10。基线：2026-10-09，HEAD `eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`。

本规格补安全审计查询、运维视图、模型连接版本及凭据引用管理边界。公共契约见 [SPEC-00](SPEC-00-公共契约与实施约定.md)，生产身份与提供方验证见 [SPEC-12](SPEC-12-开放门槛与生产接入.md)。

## 1. 当前事实与实施范围

| 源码证据 | 已有事实 | 尚缺能力 |
| --- | --- | --- |
| [V4用户与认证审计](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V4__platform_user_identity.sql) | 用户及认证审计表 | 安全管理查询/筛选/导出 |
| [V5定义管理审计](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V5__agent_definition_management_audit.sql) | 定义、能力、授权的成功变更审计 | 跨域管理投影；MCP也复用该表 |
| [JdbcMcpCatalogRepository](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/mcp/JdbcMcpCatalogRepository.java) | MCP连接、发现、批准写入审计 | 统一安全详情入口 |
| [V22资产审计](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V22__capability_skill_knowledge_assets.sql) | 资产发布/修改审计 | 管理可读查询 |
| [RunEvaluationController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/RunEvaluationController.java) | 评分202响应、accepted标志 | accepted仅本地队列接收，非远端持久成功 |
| [LangfuseObservationRecorder](../../../haizhuo-brain-observability/src/main/java/com/haizhuo/brain/observability/LangfuseObservationRecorder.java) | 脱敏记录与评分转发 | 远端健康、投递结果不能由配置enabled推断 |
| [AgentScopeRuntimeProperties](../../../haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/config/AgentScopeRuntimeProperties.java) | 仍有旧 apiKey/baseUrl 配置字段，供配置兼容；模型工厂已不直接消费 | 需受控 LEGACY pin resolver 才可能兼容旧部署连接 |
| [AgentScopeModelFactory](../../../haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/factory/AgentScopeModelFactory.java) | 使用原生 `Model` 接口按单次流解析精确引用；无引用时失败关闭 | 默认无 Vault resolver；不再从共享 apiKey/baseUrl 隐式回退 |
| [V47连接冻结迁移](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V47__model_connection_revision_and_run_binding.sql) | 目录/revision、草稿/定义版本引用及Run执行者绑定表；只保存外部凭据引用标识，不保存凭据 | 尚无目录写API、Vault适配或旧版本 pin 回填 |
| [RunWorkerProperties](../../../haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/configuration/RunWorkerProperties.java) | enabled、leaseTtlSeconds、reclaimEveryTicks等启动参数 | 管理只读展示；本版不增加热更 |

范围包含安全审计列表/详情/受限导出、配置与实测分离的运维页、模型连接目录、Vault引用选择、连接版本发布与无业务副作用测试。
不复制监控平台、业务事件总线或Agent循环；不在浏览器保存密钥，不给普通用户提供管理审计旁路。
MCP生产身份、真实IM、目标资源细粒度授权与功能开放门槛继续由SPEC-12限制。

## 2. 页面与状态

拟新增 `/admin/audits`、`/admin/operations`、`/admin/model-connections` 三个管理入口。
审计按领域、操作者、目标、动作、时间、requestId/runId筛选；详情只显示白名单差异与相关安全标识。
运维页分别显示“配置启用”“实例装载”“最近检查”“外部结果未知”；未观测不能显示健康。
已部署日志、指标和Langfuse平台使用服务端配置的受保护链接；不能由用户输入任意URL跳转或代理。
连接页区分目录状态、revision、测试结果、已引用员工版本；编辑生成新revision，不原位改已发布内容。
凭据选择器只列授权范围内的引用标签、用途、版本和可用状态；密钥创建/轮换由专用Vault流程完成。
连接测试失败显示安全错误码、检查类型和时间；可连通不等于模型获权，更不等于业务工具可执行。
Worker页面只读实例标签、启用配置、租约参数、装配状态和observedAt；不提供无门槛热更新按钮。
页面支持loading、empty、ready、refreshing、unavailable、permission-lost、conflict、test-running、test-partial。
切换筛选取消旧请求；迟到响应不能覆盖当前列表；权限撤销清理管理缓存和外部定位链接。
导出在明确时间范围内执行，显示条数上限和是否完整；达到上限不能宣称导出了全部记录。

## 3. 拟新增审计契约

当前没有统一管理查询API；以下为新增目标，全部要求PLATFORM_ADMIN与服务端范围检查。

| 方法与路径 | 请求/响应 |
| --- | --- |
| GET `/api/admin/v1/audits` | domain、action、actorUserId、targetType、targetId、requestId、runId、createdFrom、createdTo、cursor、limit |
| GET `/api/admin/v1/audits/{auditId}` | AuditDetail；auditId为白名单领域与源主键生成的稳定ID |
| GET `/api/admin/v1/audits/export` | 同筛选，无cursor；CSV，显式有界时间段，最多10000条 |

列表采用 `{items,nextCursor,hasMore}`，limit默认20最大100；按createdAt、auditId倒序分页，游标绑定范围/筛选/首次查询上界。
审计表occurredAt/恢复表createdAt统一投影为API createdAt；不存在的历史字段返回null，不补造操作人和结果。
domain：USER、AUTHENTICATION、DEFINITION、CAPABILITY、MCP、ASSET、TOOL、RUN_RECOVERY、CHANNEL、MODEL_CONNECTION。
AuditSummary：auditId、domain、action、actorUserId?、targetType、targetId、requestId?、runId?、outcome、safeSummary、createdAt。
outcome：SUCCESS、DENIED、FAILED、UNKNOWN；源表没有失败信息时不从“缺少记录”推断成功或失败。
AuditDetail增加safeChanges、correlationIds、evidenceReferenceLabel?、sourceCompleteness；变更项只允许field/oldLabel/newLabel。
认证失败展示安全摘要，禁止返回登录主体/网络来源digest、密码、Token或可用于用户枚举的细节。
工具审计仅投影能力标识、公开状态、耗时和safeErrorCode；不返回原始参数、响应正文和INTERNAL消息。
export固定首次查询时间上界，先检查最多10001条以判定超限，再稳定读取分块；CSV转义分隔符/换行并防止`= + - @`等表格公式注入。
拒绝超过31天时间段，超10000条时返回409 `AUDIT_EXPORT_TOO_LARGE`，要求缩小筛选，不能静默截断。
导出与敏感详情访问记录管理员读审计，目标指向筛选摘要；不把导出内容写入日志。
查询404 `AUDIT_NOT_FOUND`、游标/参数400、依赖503 `AUDIT_QUERY_UNAVAILABLE`；异常不能泄露表名或SQL。
这些安全码是新增接口设计，既有ApiError当前只含code/message；按类型化异常扩展，不宣称旧接口已支持。

## 4. 审计所有权与聚合

保留既有各域事务审计表为事实源，通过白名单查询适配器聚合；不将普通运行事件流当作管理审计。
MCP记录从定义管理审计按动作/目标类型投影，避免同一源行同时出现在DEFINITION和MCP域。
查询使用源主键与领域保证auditId稳定；只在可信映射内查详情，不能把ID解释为任意表或SQL片段。
sourceCompleteness区分COMPLETE、LEGACY_PARTIAL、LOG_ONLY；渠道历史日志不能迁移为伪造成功审计行。
新增渠道及连接管理审计写入必须与对应业务写事务提交；不允许先写业务、异步丢审计后仍称完整留痕。
渠道账号/身份变更由SPEC-05接审计端口；恢复终止复用V26动作，不另存第二份恢复事实。
首版直接安全投影既有表，按查询增加索引；若性能探针证明需要读模型，再设计可重建投影及唯一源映射。
跨域分页用统一时间上界及稳定排序；每个适配器返回候选上限，合并后产生同一游标，不用“各域第一页拼接”代替全局分页。
查询路径固定为静态白名单适配器：各源先按同一上界、过滤项和游标读取有界候选，再按`createdAt DESC,auditId DESC`全局归并；详情以受控领域到源主键的映射做等值读取。导出在同一`REPEATABLE READ`快照中先计数并拒绝第10001条，再按500条分块读取；请求结束后只记录管理员、访问类型、筛选/目标摘要的SHA-256、数量和时间，不保存审计正文。V43为全局时间顺序增加时间/主键索引，并为素材、工具调用、恢复审计的操作者筛选增加操作者/时间/主键复合索引；渠道请求号继续使用请求号/时间/主键索引。复合索引列均来自现有审计表，后续仍需MySQL迁移验证。

## 5. 拟新增运维只读契约

GET `/api/admin/v1/operations/overview` 返回observedAt、instanceLabel、runtime、workers、observability、links。
runtime：agentScopeVersion、loaded、profileGates；只读取实际装配和服务端开关，不根据UI配置推断可用。
workers项：kind=`RUN|CHANNEL_DELIVERY`、configuredEnabled、loaded、safeInstanceLabel、leaseTtlSeconds?、reclaimEveryTicks?、lastObservedAt?。
observability：configuredEnabled、loaded、captureContentEnabled、lastProbeAt?、probeStatus、safeErrorCode?、scoreQueueDepth?。
probeStatus为HEALTHY、UNAVAILABLE、UNKNOWN、NOT_CONFIGURED；没有实测时UNKNOWN，不返回假造lastProbeAt。
links为kind/label/url的部署白名单项；管理平台自身授权独立校验，应用登录不等于外部平台获权。
Actuator已有health探针及配置中的prometheus暴露；当前安全链并未保证prometheus可从浏览器访问，不能写成已可用监控页。
不扩大匿名Actuator详情权限；metrics与日志仍通过受保护部署链路，不给运维页提供任意URL代理。
评分accepted=true仅表示当前队列接收；远端入库、flush失败和队列饱和如无持久证据分别显示UNKNOWN/未接收。
Worker参数的修改继续通过部署配置和受控重启完成；本版无POST/PATCH Worker参数接口。

## 6. 拟新增模型连接与引用API

新增接口均受管理员权限、CSRF、endpoint出站策略与Vault引用范围检查；未接真实Vault时写开关关闭。

| 方法与路径 | 契约 |
| --- | --- |
| GET `/api/admin/v1/model-connections` | status、provider、cursor、limit；安全ConnectionSummary分页 |
| POST `/api/admin/v1/model-connections` | code≤64、displayName≤128、provider、endpoint、credentialReferenceId、credentialVersion、reason、requestId；创建revision1 |
| GET `/api/admin/v1/model-connections/{connectionId}` | 安全目录信息及currentRevision；不含密钥 |
| GET `/api/admin/v1/model-connections/{connectionId}/revisions` | cursor/limit；不可变revision列表 |
| GET `/api/admin/v1/model-connections/{connectionId}/revisions/{revision}` | 精确revision安全详情，用于草稿选择/版本回读 |
| POST `/api/admin/v1/model-connections/{connectionId}/revisions` | expectedRevision、endpoint、credentialReferenceId、credentialVersion、reason、requestId；创建新revision |
| PUT `/api/admin/v1/model-connections/{connectionId}/status` | expectedRowVersion、status=`ACTIVE\|DISABLED`、reason、requestId |
| POST `/api/admin/v1/model-connections/{connectionId}/revisions/{revision}/tests` | requestId、mode=`TRANSPORT\|AUTH\|MODEL_METADATA`；受限测试结果 |
| GET `/api/admin/v1/credential-references` | purpose、provider、cursor、limit；专用Vault授权引用目录 |

provider首版对应已有dashscope、openai、openai-compatible；endpoint≤512，必须HTTPS和部署出站白名单，禁userinfo、查询凭据、任意内网探测及无校验重定向。
credentialReferenceId≤128、credentialVersion≤128表示专用凭据服务的不可变引用；不允许latest、明文API key、Token或自由输入秘密路径。
ConnectionSummary：connectionId、code、displayName、provider、status、currentRevision、rowVersion、lastTestStatus?、createdAt、updatedAt。
ConnectionRevision：connectionId、revision、endpointLabel、endpointFingerprint、credentialReferenceLabel、credentialVersionLabel、contentHash、createdBy、createdAt。
管理编辑详情可按权限返回allowlistedEndpoint；列表和日志只用标签/指纹；任何响应不返回解析后的凭据。
CredentialReferenceSummary：referenceId、displayName、purpose、provider、version、status、expiresAt?；由Vault授权范围返回，非全Vault枚举。
TestResult：requestId、mode、status=`PASSED|FAILED|PARTIAL|UNSUPPORTED`、checks、checkedAt、latencyMs、safeErrorCode?；每项检查有name/status。
测试超时上限5秒、并发/频率有限；只探测连接、认证或模型元数据，不创建Run、不调用业务工具、不提交IM消息或写业务资源。
provider无安全元数据接口时返回UNSUPPORTED/PARTIAL；禁止以固定模拟结果标记连接健康。
测试响应/日志不返回远端错误正文和header；诊断信息使用安全分类，认证失败不自动替换凭据。
同requestId同内容幂等；revision冲突409 `MODEL_CONNECTION_CHANGED`，引用不可用503 `CREDENTIAL_REFERENCE_UNAVAILABLE`，依赖503 `MODEL_CONNECTION_UNAVAILABLE`。
目录状态/currentRevision变更均在连接行锁内递增rowVersion；status以expectedRowVersion做CAS，不以不变的内容revision充当状态锁。新增内容revision仍以expectedRevision核对head并递增rowVersion。两种版本分别表示不可变内容和可变目录状态，响应均回读更新值。

## 7. 发布冻结与凭据边界

新增 `agent_definition_draft_model_connection` 保存employeeId、draftRevision、connectionId、connectionRevision；与草稿保存做一致性校验。
新增 `agent_definition_version_model_connection` 保存definitionVersionId、connectionId、connectionRevision、connectionContentHash；发布时不可变冻结。
定义草稿API新增可选modelConnectionRef=`{connectionId,revision}`，与SPEC-04协同；模型provider/modelName仍来自定义配置并校验与连接一致。
新增 `platform_run_model_connection_binding` 保存runId、executorRoleId、executorDefinitionVersionId、connectionId、connectionRevision、connectionContentHash；唯一键(runId,executorRoleId,executorDefinitionVersionId)。直接专家按RunExecutionTarget的精确版本绑定，不能取AgentRun.definitionVersionId的会话主版本；固定专家/Team成员按各自冻结成员版本，通用/动态专家继承父执行者冻结连接。所有来源从精确版本绑定复制，禁止读取currentRevision。
RuntimeDefinitionSnapshot/RunSpec只加入非秘密ModelConnectionRef和hash；apiKey、endpoint、凭据解析结果不进入定义包、workspace、事件或用户DTO。
新增RuntimeModelConnectionResolver读取精确revision并向原生ModelFactory提供短生命周期ResolvedModelConnection；继续复用AgentScope模型创建组件。冻结模板只持非秘密resolver/ref，不能缓存含apiKey的原生模型/Agent来假装短生命周期；每次模型调用先按精确Vault引用复核可用性、解析，再通过薄Model包装调用原生提供方模型，结束/取消释放解析资料，流式调用仅在该调用存续期间持有秘密。同Run的下一轮也重新判定，不能只在新Run时检查吊销；已发送请求无法被吊销撤回，取消/未知结果仍按既有规则核查。
本地2.0.3 source/javap已确认接入点为io.agentscope.core.model.Model：Flux<ChatResponse> stream(List<Msg>,List<ToolSchema>,GenerateOptions)，以及模型名/结构化能力/上下文窗口查询方法；没有同步call/invoke入口。HarnessAgent.Builder.model(Model)可直接接包装器。现有AgentScopeModelFactory.create及HarnessAgentFactory.createModel返回ChatModelBase，切片需改成Model接口并更新受影响调用/测试。
包装器用Flux.defer在每次推理订阅时解析精确引用、创建单次原生delegate并转发stream，doFinally释放本次解析句柄/引用；能力查询使用冻结非秘密元数据，不触发秘密缓存。不得subclass ChatModelBase再重复调用delegate.stream，其stream为final且已包原生tracing；只包Model边界避免重复span。原生transport若无close契约不杜撰close方法，按固定版本资源所有权验证取消、流结束和实例复用；不承诺Java字符串可物理擦除。
原生OpenAIChatModel/DashScopeChatModel的builder支持HttpTransport，其execute(HttpRequest)、stream(HttpRequest)、close()已从2.0.3源码核对；Model本身没有close。共享非秘密transport由平台生命周期管理，单次只释放resolver lease/secret引用与delegate；只有拥有单次transport时才在complete/error/cancel关闭，禁止误关共享连接。单次流的重订阅是否重发请求须按原生契约验证，UI不得触发模型订阅或重试。
连接revision表保存允许的endpoint与Vault具体版本引用，不存秘密；解析后凭据只在必要调用内存中使用，不缓存到Harness或Session持久状态。

本次 FE-10-S4 增量：`RuntimeModelConnectionRef` 包含 connectionId、revision、contentHash 与 `MANAGED_REVISION`/`LEGACY_DEPLOYMENT_PIN` 类型；旧 `RuntimeDefinitionSnapshot` 构造器仍可编译，但没有冻结引用的快照在 `AgentScopeModelFactory` 处安全失败。Run 适配先以属主读取持久 Run 与 `RunExecutionTarget`，校验 role/version，再从不可变定义版本绑定复制引用到根与固定成员快照，并幂等冻结 `(runId, roleId, definitionVersionId)`；缺引用或不匹配在调用 AgentScope 前返回安全失败事件。`HarnessTemplateKey` 同时纳入根与固定成员的精确引用，避免不同 revision 复用模板。

模型接入继续使用 AgentScope Java 2.0.3 的 `Model.stream(...)` 原生接口。连接 resolver 在每次模型流订阅时取得短期 `ResolvedModelConnection`，终止、错误或取消时释放；默认 resolver 不可用，且 bootstrap 的 `haizhuo.brain.model-connections.resolution-enabled` 默认 `false`。普通未绑定快照、未配置 Vault resolver 的托管引用，以及尚未实现的 LEGACY pin resolver 均不会访问模型；明确 LEGACY pin 仍须由受控迁移绑定固定身份和 hash，不能读取共享 apiKey/baseUrl。当前未回填旧定义版本，因此未绑定存量版本的后续 Run 会在 Runtime 委托前以 `MODEL_CONNECTION_UNAVAILABLE` 失败；需在部署前提供可审计的精确 pin/凭据解析，或保持能力关闭。
连接编辑创建revision2，旧Session与Run继续用revision1；禁用阻止新发布/新Session选择，存量执行采用已有冻结revision。
安全撤销凭据由Vault即时拒绝解析，旧Session明确失败并进入既有安全失败/恢复流程；不能悄悄切到新凭据或latest连接。
在切换到目录管理前，为旧定义/Session建立受控LEGACY_DEPLOYMENT_PIN绑定，固定当前部署的非秘密连接版本映射；无法可靠映射时阻止切换并明确未迁移。
旧版本内容不改写；旁路绑定表保存迁移来源、时间和管理员审计。迁移前旧连接仍属部署配置，不能宣称历史已冻结。
definitionBundleHash原值保留；新增connectionContentHash单独验证配置绑定，模板缓存键纳入该hash避免不同连接复用旧模型实例。

## 8. 文件入口、实施步骤与迁移

| 层 | 实施入口 |
| --- | --- |
| web | 拟新增 AuditPanel.vue、OperationsPanel.vue、ModelConnectionPanel.vue及对应api封装；复用AdminView |
| api | 拟新增 AuditQueryController、OperationsOverviewController、ModelConnectionAdministrationController、安全DTO |
| platform/security | 拟新增AuditQueryService/域适配端口、ModelConnectionService、CredentialReferenceDirectory；复用可信身份与事务边界 |
| infrastructure | 拟新增Jdbc审计查询、连接目录/revision/冻结引用仓储；连接专用Vault适配器 |
| runtime-api/agentscope | `ModelConnectionRef`/解析端口、原生 `Model` 薄适配、根及固定成员模板缓存隔离（本轮已编码，待Java验收） |
| bootstrap/observability | OperationsOverview装配、受限连接测试、部署白名单；保留原生观测SDK与Worker配置 |

1. FE-10-S1：安全审计DTO、域归类、稳定分页、详情/导出；补CHANNEL审计事务入口。
2. FE-10-S2：运维只读总览及受保护外部链接，区分配置、实测、未知和评分队列接收。
3. FE-10-S3：Vault引用契约与连接目录/revision、测试白名单、审计、幂等/冲突；写开关仍关闭。
4. FE-10-S4：与SPEC-04统一草稿/发布引用；迁移旧Session绑定、Run冻结和缓存隔离。引用冻结/Run执行者绑定/缓存隔离代码已编码；FE04草稿选择与旧版本 pin 回填仍缺，待统一Java验收。
5. FE-10-S5：受控真实Vault/模型连接验证、凭据撤销及滚动部署演练后再开放连接写管理。

仅新增Flyway迁移，实施时分配版本；新增目录/revision/绑定/管理审计表及查询索引，不能改写已执行V9或现有审计行。
部署先迁移与查询、后引用冻结适配、再UI；写开关默认关闭；新旧实例不能对同一Session使用不同连接解析策略。
回退先关闭新连接选择及写管理，保留不可变revision和绑定；不得把已冻结Session恢复为读取latest部署配置。
新绑定执行仍需精确revision解析；无法兼容的旧runtime只能停止受影响新执行并保留结果/审计，不删除绑定降级。
实现改动前执行GitNexus impact；索引异常或UNKNOWN需源码校准，高风险先报告，提交前执行detect_changes。

## 9. Given / When / Then 验收

| 编号 | Given / When / Then |
| --- | --- |
| FE-10-A01 | Given多个域审计且时间相同；When筛选翻页；Then全局稳定无重复漏读，详情对应唯一源事实 |
| FE-10-A02 | Given普通用户/管理员撤权；When读审计/连接；Then403、缓存清理且无敏感字段泄漏 |
| FE-10-A03 | Given渠道/连接变更事务失败；When写操作；Then业务与结构化成功审计共同回滚 |
| FE-10-A04 | Given观测配置启用但未实测或远端失败；When打开运维页；Then不显示假健康或“评分已远端保存” |
| FE-10-A05 | Given越权Vault引用、任意内网endpoint或重定向；When创建/测试；Then服务端拒绝且无秘密/业务副作用 |
| FE-10-A06 | Given连接revision1已发布；When新增revision2并继续旧Session；Then旧Run保持revision1/hash，新发布才引用revision2 |
| FE-10-A07 | GivenVault撤销旧凭据版本；When旧Session调用；Then安全失败，未自动切换latest或记录秘密 |
| FE-10-A08 | Given连接并发修订/重复request；When提交；ThenCAS冲突或幂等重放，不覆盖不可变历史 |
| FE-10-A09 | GivenCSV恶意公式、超过10000条或31天范围；When导出；Then安全转义或明确拒绝，不静默截断 |
| FE-10-A10 | Given旧Session无法可靠映射；When切换目录解析；Then阻止切换并明确未迁移，历史定义和结果完整 |

## 10. 验证层级与DoD

首次记录中“未运行 Maven”仅反映当时状态；本轮统一 Maven 复验已通过 `AgentScopeModelFactoryConnectionTest` 9/9、`RunModelConnectionSnapshotBinderTest` 4/4、`JdbcModelConnectionBindingStoreTest` 4/4，且审计 store/service/controller 和运维总览定向类通过。上述是本地单测/H2 证据，不验证真实 MySQL、Vault、模型 provider、浏览器或部署灰度。

- 新增 `AgentScopeModelFactoryConnectionTest`：覆盖引用解析延迟到模型流订阅、无引用/无 resolver/LEGACY pin 不走共享配置、revision不匹配阻止 provider 调用、完成/错误/取消释放 lease、根与固定成员 revision 分离缓存键、lease `toString` 不暴露 delegate。
- 新增 `RunModelConnectionSnapshotBinderTest`：覆盖持久 Run 属主与 executor role/version 校验、根和固定成员精确引用递归绑定、Run绑定冻结、缺引用时不委托 AgentScope并返回安全失败。
- 新增 `JdbcModelConnectionBindingStoreTest`：覆盖活动草稿精确 revision 发布冻结、禁用后存量 revision 仍可解析、新发布拒绝禁用/草稿 revision 冲突、provider/hash不匹配拒绝、Run绑定幂等及不允许改写。
- GitNexus：`AgentScopeModelFactory` 和 `HarnessTemplateKey` 为 CRITICAL；`RunExecutionService` 为 CRITICAL 但未修改。`RuntimeDefinitionSnapshot` 影响结果为 UNKNOWN/调用未解析；源码搜索确认其直接构造调用在 `RunExecutionService` 根/固定成员快照及 runtime/bootstrap 测试中，保留旧构造器，且通过新增平台适配层接入。
- 首次实施静态核对：20个 FE-10 文件无尾随空白；`AgentScopeModelFactory` 不引用共享 `apiKey`/`baseUrl` 回退；V47 不含凭据值字段；bootstrap 解析开关默认 `false`。本轮 Java 选择集已通过；MySQL/Flyway、Vault、真实模型和浏览器仍未执行。不得把本地测试视作生产连接验收。

- [ ] 审计脱敏/分页/导出、权限、错误码、连接revision与幂等契约测试通过。
- [ ] MySQL真机跨域查询/索引、发布和Run绑定事务、迁移与回退验证通过。
- [ ] 真实Vault范围/撤销、endpoint安全测试、冻结连接与缓存隔离验证通过。
- [ ] 前端build及浏览器管理流程/冲突/未知观测/导出验收通过，Worker无热更入口。
- [ ] 观测SDK与远端持久证据分层记录；生产门槛依SPEC-12通过后才开放写管理。
