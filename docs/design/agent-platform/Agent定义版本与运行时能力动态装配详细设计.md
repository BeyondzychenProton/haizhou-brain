# 海卓智慧大脑：Agent 定义版本与运行时能力动态装配详细设计

| 项目 | 内容 |
| --- | --- |
| 版本 | 0.2 |
| 更新日期 | 2026-09-27 |
| 状态 | 首轮实现与本地 MySQL/真实模型验证已完成；生产身份与权限能力待后续接入 |
| 目标 | 将 Run 到 AgentScope Toolkit 的能力来源从 Java 硬编码改为 API + 数据库驱动 |
| 适用范围 | Agent 定义草稿、不可变发布版本、有效能力集、工具执行提供者和 AgentScope 动态装配 |
| 上位设计 | [Agent 能力配置与发布架构](Agent能力配置与发布架构.md)、[Agent 资源与动作权限设计](Agent资源与动作权限设计.md)、[请求执行链路与控制概要](请求执行链路与控制.md) |

本文把已经确认的产品约束细化为接口、数据、运行时装配和验收设计，也是代码基线。首轮实现已包含 Flyway 表、MySQL/JDBC 仓储、测试内存仓储、Mock 管理 API、有效能力解析、AgentScope Toolkit 动态装配、执行时授权复核和摘要审计。MySQL 迁移及真实模型的查询/预定闭环已通过；管理员身份、完整管理审计和细粒度资源权限尚未实现，因此首轮只用于本机 Mock。逐项验收证据见[实现测试报告](../../reports/Agent动态能力装配设计实现测试报告.md)。

## 1. 目标与验收结果

将当前的固定会议室工具注册改成以下链路：

**管理员 API 修改草稿 → 数据库存储 → 发布不可变 AgentDefinitionVersion → Run 开始时读取发布版本 → 按当前用户和权限生成 EffectiveCapabilitySet → 持久化本 Run 能力快照 → AgentScope Toolkit 动态装配 → 工具执行前再次授权 → 记录真实执行结果。**

完成后应满足：

1. 管理员能通过 API 从能力目录选择已登记能力、编辑员工指令和模型引用、校验草稿并发布。
2. 配置与发布事实以数据库为准；应用不需为更改员工绑定或启停一项已注册能力而重启。
3. 新 Run 使用启动时的当前发布版本；已开始的 Run 固定其版本与能力修订，不被后续发布悄悄改写。
4. AgentScope 收到的 Toolkit 只包含本次 EffectiveCapabilitySet 允许的工具，不再由 MeetingRoomRunService 直接写死能力列表。
5. 工具执行边界再次检查 Run 快照、当前能力状态、Mock 用户授权和固定会议室动作；细粒度资源范围尚未接入，模型可见工具不等于平台授权。
6. 数据库、配置版本、提供者注册或授权状态不可确认时默认拒绝工具调用，不能回退到旧的硬编码会议室清单。
7. 首轮审计可关联 Run、能力修订、用户、动作、参数哈希、准入决定与结果摘要；参数只留哈希，不能还原具体资源对象。完整对象级审计和管理配置前后变更审计仍待实现。

## 2. 当前代码基线

以下结论来自当前仓库源码：

| 位置 | 当前行为 | 需要改变的边界 |
| --- | --- | --- |
| platform.employee.AgentDefinitionVersion | 保存版本 ID、员工 ID、版本号、指令、模型提供方、模型名、内容摘要和发布时间 | 已由 JDBC 仓储持久化发布；历史版本不更新，发布通过事务切换当前指针 |
| platform.employee.CapabilityBinding | 用类型、引用 ID 和修订号表示版本绑定 | 首轮发布绑定指向能力目录中的精确修订；绑定不等于用户授权 |
| runtime-api.RuntimeCapability | 已承载 type、referenceId、revision、工具名、描述、实现键、业务动作和输入 Schema | 只承载平台解析后的安全 DTO，不把平台数据库实体泄漏到框架模块 |
| platform.meetingroom.MeetingRoomRunService | 当前按外置配置指定的租户、员工和 Mock 用户读取发布版本，生成并保存本 Run EffectiveCapabilitySet | 员工选择仍限于 meeting-mock 配置，不是通用员工入口 |
| runtime-agentscope.AgentScopeRuntime | 当前创建独立 Toolkit，并委托 AgentScopeToolkitAssembler 装配 Run 能力 | Runtime 不再直接依赖会议室工具类 |
| runtime-agentscope.tool.MeetingRoomRuntimeToolProvider | 只接受 `meeting-room-v1` 白名单中的两个会议室能力 | 能力提供者创建本 Run AgentTool；数据库不能提供任意执行代码 |
| platform.meetingroom.MeetingRoomToolGateway | 当前核对 Run 快照、即时能力开关和用户授权，再验证会议室业务条件并记录参数哈希审计 | 保持受控执行边界；细粒度资源/动作授权仍需后续接入统一 security 决策 |

锁定的 AgentScope Java 2.0.3 中，Toolkit 可接收 AgentTool。当前动态适配代码已按项目锁定版本编译；API 发布、有效集合、受控 Gateway 和真实模型工具循环均已验收。两种实际装配清单及 MySQL 写入证据见测试报告。

## 3. 范围与明确不做的事

### 3.1 本设计包含

- 通过 Mock 管理 API 查询已登记能力目录，编辑员工草稿与能力绑定、发布不可变版本，并设置全局启停和首轮用户授权。能力目录新增、能力修订编辑不开放 API。
- 数据库作为员工发布版本、能力修订、Run 能力快照和审计事件的权威来源。
- 对用户、渠道和资源权限进行有效能力收敛；能力绑定、平台权限和目标系统权限继续分层。
- AgentScope Toolkit 按有效 Tool 能力集合动态装配。
- 将会议室查询和预定作为首个通用链路的迁移验证对象。
- 能力启停、版本回滚、权限撤销、并发编辑、运行中变更和依赖缺失的处理。

### 3.2 首轮代码实现范围建议

首轮仅迁移现有 meeting_room.search 与 meeting_room.reserve：

- 管理 API、草稿、发布版本、能力绑定和 Run 有效能力快照走数据库。
- 两项工具都必须通过能力目录中的受控提供者注册表找到 Java 执行实现。
- 修改员工指令、模型引用、能力绑定、工具显示 Schema 或停用状态后，新 Run 读取新配置，不要求重启。
- 正在执行的 Run 继续使用创建时的定义版本；每一次工具调用仍检查即时停用与当前授权。

### 3.3 本轮及首轮实现不包含

- 从数据库执行任意 Java 类名、脚本、表达式、SQL、HTTP URL 或反射目标。
- 通过纯数据库配置创造从未部署过的业务副作用实现。
- MCP Server 的在线发现与远程 Tool 动态执行、Skill 文件装载、Knowledge 检索装配。
- 用户自建 Agent、员工市场、流程引擎和前端管理界面。
- 重新实现整个平台 RBAC；本设计规定有效能力解析器调用 security 的决策边界，详细授权模型仍归属权限专题设计。

本轮管理 API 没有登录或管理员认证，只在 `meeting-mock`/`test` Profile 启用；meeting-mock 配置绑定 `127.0.0.1`。API 的 actorId 是本地 Mock 配置值，不能将此安全边界用于局域网或公网部署。首轮拒绝空能力列表，因此如需让员工版本不具备任何工具，目前应停用员工/限制入口，不能发布空列表版本。

## 4. 核心对象与不变量

| 对象 | 说明 | 变更规则 |
| --- | --- | --- |
| DigitalEmployee | 可被用户使用的员工身份，持有当前发布版本指针 | 状态和当前指针保存在数据库；首轮仅有 employee 1 种子，无员工启停管理 API |
| AgentDefinitionDraft | 管理员编辑区，含指令、模型提供方/名称和能力选择 | 可编辑；以 draftRevision 做并发控制；不能直接用于普通 Run |
| AgentDefinitionVersion | 发布时形成的不可变配置快照 | 不更新、不复用版本号；修改或回滚都产生新版本 |
| CapabilityDefinition | 能力目录中的稳定能力身份，例如 meeting_room.search | 目录由迁移种子初始化；首轮可全局停用/启用，不能通过 API 新增能力身份 |
| CapabilityRevision | 能力的不可变修订，含说明、Schema、业务动作映射和实现键 | Schema 或动作语义变化产生新修订 |
| CapabilityBinding | AgentDefinitionVersion 对某项能力精确修订的引用 | 只回答“员工版本装了什么”，不授予用户权限 |
| EffectiveCapabilitySet | 根据发布绑定、能力启停、白名单提供者和当前 Mock 用户布尔授权计算出的本 Run 允许能力集合 | 每个 Run 启动时生成并留存摘要；渠道、资源范围等细粒度权限尚未接入 |
| AgentToolInstance | 由受控提供者按 EffectiveCapabilitySet 创建的 AgentScope 工具实例 | 仅属于当前 Toolkit/Run；禁止跨用户复用带身份状态的实例 |
| ToolInvocation | 一次真实工具调用及其授权、参数哈希和结果摘要 | 追加留痕；首轮不保存原始参数、资源分解或外部系统 ID；拒绝调用也只在能解析出能力时写入能力审计表 |

必须保持以下不变量：

1. 未发布草稿不能进入用户 Run。
2. 能力绑定不是 Permission；某个工具在 Toolkit 可见不代表对应用户可执行其业务动作。
3. EffectiveCapabilitySet 只能由服务端从员工版本、能力目录、白名单提供者和当前 Mock 用户授权计算。
4. Runtime 装配器只使用允许集合；统一工具执行入口再检查，不能依赖 Prompt 或模型自律。
5. 已发布版本引用的能力修订必须可追溯；不得在旧修订缺失时退回最新修订。
6. 用户授权撤销和能力紧急停用对未越过执行检查的下一次调用生效；员工停用接口尚未实现；已发出的请求按真实结果留痕。
7. 能力实现必须在受控注册表中存在；数据库中保存的值不能成为类加载、脚本执行或任意网络访问入口。

## 5. 总体架构与主流程

管理配置与发布：

**本地管理 API → AgentDefinitionManagementService → AgentDefinitionDraft 与草稿能力绑定 → 提供者/启停/Schema 校验 → 发布事务写入 AgentDefinitionVersion 和版本能力绑定 → 原子更新员工当前发布版本指针。**

Run 执行（首轮会议室 Mock）：

**本地固定用户和 Session → Run 开始时读取配置指定员工的当前发布版本 → EffectiveCapabilitySetResolver 收敛已登记工具和当前布尔授权 → 保存 Run 能力快照 → AgentExecutionRequest → AgentScopeToolkitAssembler → 白名单提供者创建工具对象 → AgentScope 执行 → ToolExecutionGateway 再次授权 → MeetingRoomSystem 执行固定 A-201 Mock 操作 → 记录参数哈希和结果摘要。**

### 5.1 管理配置与发布

1. Mock 操作者查询 Flyway 初始化的两项会议室工具能力。
2. Mock 操作者编辑种子员工草稿；草稿绑定能力代码和精确修订，不直接指定 Java 类名。
3. 校验服务验证模型提供方允许值、指令长度、能力引用和白名单提供者，校验目录能力状态、工具名及 Schema；资源范围及统一业务权限校验待后续接入。
4. 发布服务在单个数据库事务内创建新版本和版本能力绑定，再更新员工当前发布版本指针。
5. 若版本插入或当前指针更新失败，事务回滚；不能出现指针已切换但版本内容不完整。
6. 发布成功只改变新 Run 的选择；进行中的 Run 不切换定义版本。

发布校验不要求固定 Mock 用户拥有每一项业务动作权限。允许发布一项已登记但当前用户无权调用的能力；解析器会将未授权能力从有效集合中排除。首轮 API 未实现管理端预览界面。

### 5.2 Run 开始与有效能力解析

1. 普通 Run API 只能提交业务消息、Session 和幂等键；不能提交员工版本 ID、能力列表、角色或 UserId 来覆盖服务端事实。首轮用户身份由 Mock 配置固定。
2. Run 实际开始执行时，服务端读取配置指定员工的当前发布版本；有效能力快照绑定 Run 与定义版本。
3. Resolver 读取该版本的不可变绑定与精确 CapabilityRevision。
4. 首轮对每项绑定按以下条件收敛：能力与修订启用、实现提供者已注册、Mock 用户拥有该能力的布尔授权。
5. 仅将本次允许的工具能力投影为 Runtime DTO；不允许能力及拒绝原因留在受控诊断视图，不把隐藏能力名称及权限信息发给模型。
6. 把定义版本 ID、有效能力项、排除原因和集合 Hash 持久化后，再调用 AgentRuntime。当前 Run 状态与快照写入不是同一事务；进程中断后的会议室恢复逻辑通过操作键核验 booking 并收敛 Run，但查询收据只保存在当前进程内存中，跨进程续跑完整工具循环尚未实现。
7. 若当前授权状态不可判定，工具能力默认拒绝。非工具能力未来分别交给 Skill、Knowledge 等受控适配器，不应伪装成 Tool。

### 5.3 AgentScope Toolkit 装配

1. AgentScopeRuntime 接收经平台投影的 AgentExecutionRequest 与能力快照标识。
2. ToolkitAssembler 为当前 Run 创建新的 Toolkit，读取请求中的允许 Tool 描述。
3. 对每项 Tool，根据稳定的 implementationKey 查询 CapabilityAdapterRegistry。
4. 提供者验证能力代码、修订、参数 Schema、配置摘要和 Run 上下文，再创建当前 Run 专属 AgentTool/ToolBase 对象。
5. 装配器逐项注册，不注册未绑定工具、内置宽权限工具、任意 MCP Server 全量工具或其他 Run 的工具。
6. 任一获准 Tool 无法注册或 Schema 不一致时，该 Run 装配失败并记录原因；不静默跳过后仍让模型声称可以完成，也不切回硬编码旧工具。
7. 只有 Toolkit 通过清单比对与安全检查后，Agent 才开始第一次模型调用。

每个 Run 使用独立 Toolkit 及带 Run 上下文的 Tool 实例。模型客户端可按安全条件复用；持有 UserId、RunId、授权集合、操作收据等可变状态的工具对象不可在请求间共享。

## 6. API 设计

首轮已实现的管理 API 如下。后续统一身份接入后，再将 actorId 绑定到认证主体；不能从请求体声明角色。

首个 Mock 实现暂不包含登录和管理员认证，管理 API 只在 meeting-mock/test Profile 中启用，meeting-mock 服务绑定本机回环地址。该接口不能暴露到局域网或公网；正式环境启用前必须接入身份专题定义的管理员认证和权限校验。以下路径与字段是当前代码接口；错误码建议不表示所有错误映射均已实现。

| 方法与路径 | 用途 | 关键约束 |
| --- | --- | --- |
| GET /api/admin/v1/capabilities | 查询已登记能力目录 | 当前种子目录含会议室查询和预定两项；返回修订与启停状态 |
| GET /api/admin/v1/agents/{employeeId}/draft | 读取草稿 | 返回 draftRevision、指令、模型提供方/模型名和绑定列表 |
| PUT /api/admin/v1/agents/{employeeId}/draft | 保存草稿 | 携带 expectedDraftRevision；只接受目录中的能力代码与修订 |
| POST /api/admin/v1/agents/{employeeId}/validate | 校验草稿 | 返回可否发布和问题清单，不改当前版本 |
| POST /api/admin/v1/agents/{employeeId}/publish | 发布草稿 | 携带 expectedDraftRevision/requestId；事务内新建不可变版本并切换当前指针 |
| PUT /api/admin/v1/capabilities/{capabilityCode}/status | 启用或停用能力 | 操作者与原因写入目录状态；下次工具执行前即时复核 |
| PUT /api/admin/v1/users/{userId}/capability-grants/{capabilityCode} | 设置用户能力授权 | 首轮为按用户+能力的布尔授权；撤权立即影响已有 Run 的后续调用 |

首轮草稿请求允许修改员工指令、模型提供方/模型名和能力绑定列表；模型 API Key 与 Base URL 继续只从外置本地配置读取。能力项使用 capabilityCode 与 revision；当前不支持通过 API 新增能力目录项、改 Schema 或登记新的 Java 提供者。不能从 API 写入实际 API Key、类名、Java 代码、脚本、任意路径或任意 URL。

现有用户运行接口 POST /api/v1/sessions/{sessionId}/runs 保持用户侧业务输入职责。它不能允许调用者挑选任意已发布版本或自行附加 RuntimeCapability。当前的 Mock 会议室管理接口用于验证业务数据，不等同于 Agent 配置管理接口。

推荐错误语义：

| 状况 | HTTP 建议 | 说明 |
| --- | --- | --- |
| 草稿字段、能力引用或员工不存在 | 400 | 首版将不存在对象映射为参数错误；不回显凭据 |
| draftRevision 不匹配、发布状态冲突 | 409 | 客户端重新读取后再编辑 |
| 草稿结构有效但不满足发布准入 | 422 | 返回不可发布项及安全原因 |
| 当前操作者无管理权限 | 尚未实现 | 首版不做身份认证；仅靠 Mock Profile 和本机回环绑定隔离 |
| 发布或解析时数据库不可用 | 当前映射由框架异常处理决定，具体 HTTP 状态尚未统一 | 不切换到静态配置，不装配任何未核验能力 |

## 7. 数据库模型建议

数据库是 meeting-mock 首轮配置与运行快照的权威来源。以下表已由 `V2__agent_capability_dynamic_configuration.sql` 建立；业务授权仍为首轮简化模型。

| 表 | 主要字段 | 用途和约束 |
| --- | --- | --- |
| digital_employee | id、tenant_id、employee_code、display_name、enabled、current_published_version_id、row_version | 首轮迁移种子员工 ID 1；保存当前版本指针 |
| capability_definition | capability_code、capability_type、status、updated_by、updated_at、status_reason | 稳定能力身份和全局启停状态 |
| capability_revision | capability_code、revision、display_name、description、tool_name、implementation_key、business_action、input_schema_json、content_hash | 已登记能力的不可变修订和 AgentScope 工具描述 |
| agent_definition_draft | employee_id、draft_revision、instructions、model_provider、model_name、updated_by、updated_at | 当前可编辑草稿；首版以 employee_id 为主键 |
| agent_definition_draft_capability | employee_id、capability_code、capability_revision、position_no | 草稿选择能力，外键约束能力修订 |
| agent_definition_version | id、employee_id、version_no、instructions、model_provider、model_name、content_hash、publish_request_id、published_by、published_at | 不可变版本；员工+版本号与员工+发布请求 ID 唯一 |
| agent_definition_version_capability | definition_version_id、capability_code、capability_revision、position_no | 发布版本精确绑定能力修订 |
| agent_user_capability_grant | user_id、capability_code、enabled、updated_by、updated_at | 首轮按用户+能力的布尔授权，不含资源 Scope |
| run_effective_capability_set | run_id、definition_version_id、user_id、snapshot_hash、resolved_at | 本 Run 的能力集合摘要 |
| run_effective_capability_item | run_id、capability_code、capability_revision、allowed、reason_code、runtime_capability_json | 保存允许 DTO 和拒绝原因；仅 allowed 项进入 Toolkit |
| tool_invocation_audit | invocation_id、run_id、capability_code、capability_revision、user_id、business_action、arguments_hash、decision、result_status、result_summary、created_at | 仅摘要审计；不保存工具原始参数 |

首轮已实现事实与后续数据库要求：

1. 已发布版本及其版本能力绑定由仓储只插入；常规变更通过新版本表达。
2. 发布事务要同时写版本、版本绑定、内容摘要和员工当前版本指针。
3. 版本绑定引用 CapabilityRevision 的外键；运行中的 Run 在可恢复期间保留所需引用和内容摘要。
4. 运行快照按 RunId 唯一，Repository 使用事务保存快照头与允许/排除项；它与 Run 状态转换不是同一个事务。
5. 当前审计表索引为 `(run_id, created_at)`；员工版本、能力状态与管理审计的查询索引可随后续查询和审计范围补充。不存在 `subject_id/decision/time` 复合索引。
6. 能力紧急停用应有可及时读取的权威状态；不能只依赖长 TTL 缓存。
7. 工具调用审计只追加并只保存参数哈希。首轮草稿、能力状态和授权保存最近操作者与时间，发布版本保存发布者；尚无完整的管理操作前后变更审计事件表。
8. 数据库中只保存 model_provider/model_name；原始模型 Key 与 Base URL 不进入定义版本、Prompt、Run 快照或审计日志。

## 8. Java 模块和契约建议

### 8.1 platform.employee

首轮 Java 边界如下；跨业务通用能力待后续扩展：

- AgentDefinitionManagementService：读取/更新草稿、校验发布、发布、停用能力及配置用户授权。
- AgentDefinitionRepository：组合持久化员工发布定义、草稿、目录、用户授权、Run 快照和工具审计。
- MeetingRoomToolGateway：在真实业务动作前再次检查快照、当前能力状态与授权，再验证会议室规则。

AgentDefinitionVersion 和 CapabilityBinding 从当前内存 record 契约演进为可完整代表不可变快照的领域对象。Repository 返回的历史版本不能被管理 API 直接修改。

### 8.2 platform.capability

- `platform.capability.EffectiveCapabilitySetResolver`：首轮接收已解析员工版本、RunId 和固定 Mock UserId；按已发布绑定、能力目录状态、实现提供者与布尔用户授权输出本 Run 的 EffectiveCapabilitySet。
- `platform.employee.AgentDefinitionRepository`：保存与读取有效能力快照及定义配置事实。
- CapabilityRunAuthorizer：再次检查 Run 快照、当前能力开关、用户授权和白名单提供者。
- ToolInvocationAudit：记录 allow/deny、结果状态与参数哈希；资源 Scope、确认和统一权限策略尚未接入。

Resolver 只收敛能力；不执行业务副作用。Permission 决策仍使用 security 模块的可信主体和资源规则。

### 8.3 runtime-api

当前 `AgentExecutionRequest` 携带服务端生成的以下信息：

- TenantId、UserId、SessionId、RunId、TraceId。
- AgentDefinitionVersionId、EffectiveCapabilitySet Hash。
- 指令、模型配置引用与当前 Run 必要上下文。
- 有效能力集合 Hash，以及精确 RuntimeCapability 列表：能力 code、revision、工具名、描述、业务动作、输入 Schema 和 implementationKey。

RuntimeCapability 是跨平台与 AgentScope 的数据传输对象，不包含平台数据库实体、原始权限表、密钥、可信身份的客户端可覆盖字段或可执行代码。当前 Gateway 通过 RunId 读取持久化快照并复核数据库当前授权；不只依赖请求对象里的字符串 allow-list。

### 8.4 runtime-agentscope

- CapabilityAdapterRegistry：启动时由 Spring 注册一组白名单实现，按 implementationKey 解析；拒绝重复键和缺失实现。
- RuntimeToolProvider：验证能力修订和配置，创建当前 Run 专属 AgentTool/ToolBase 适配实例。
- AgentScopeToolkitAssembler：将 RuntimeCapability 投影为 AgentScope 工具对象，注册到新 Toolkit，并比较最终工具名集合与有效能力清单。
- AgentScopeRuntime：只接收准备好的模型、指令、Toolkit 和上下文；不硬编码某个业务工具类或会议室能力 ID。

数据库中 implementationKey 只允许引用已注册的白名单键。不能用 Class.forName、Spring Bean 名直接透传、脚本引擎、动态 SQL 或任意远程地址来决定执行代码。

## 9. 动态配置生效、缓存与撤销

| 配置或状态变化 | 生效规则 |
| --- | --- |
| 草稿文字或绑定变更 | 对现有 Run 和新 Run 均不生效，直到发布 |
| 发布新版本 | 新开始的 Run 读取新版本；已经固定版本的 Run 保持旧版本 |
| 已发布能力修订被新修订替代 | 不影响引用旧修订的 Run；新草稿显式选择新修订后再发布 |
| 员工停用 | 拒绝新 Run；现有 Run 在下一控制边界按停用策略处理 |
| 能力全局紧急停用 | 已装配 Toolkit 中即使仍有工具对象，下一次 Gateway 检查也拒绝调用 |
| 用户权限撤销 | 下一次工具调用重新查询或验证权限版本后拒绝；不因旧 Run 快照放行 |
| 提供者未注册或 Schema 不匹配 | 新 Run 装配失败关闭；不回退旧能力实现 |

缓存只用于已不可变、可按版本 Hash 命中的定义内容与只读能力 Schema。EffectiveCapabilitySet、用户授权、员工/能力 kill switch 和待确认状态不作为长期授权缓存。若引入短期缓存，必须定义版本号/失效事件，并且 Gateway 每次副作用前仍执行权威校验。首轮建议先不缓存授权决策，确保撤销语义可验证。

发布接口对重复请求使用幂等键；并发编辑用 draftRevision/If-Match 拒绝覆盖。发布返回 definitionVersionId、versionNo、contentHash 和发布时间。回滚复制历史版本生成新的草稿并再次校验发布，不能把员工当前指针静默改回旧版本来绕过紧急停用。

## 10. 会议室场景的具体装配示例

能力目录包含两个已注册工具：

| capabilityCode | toolName | implementationKey | 业务动作 |
| --- | --- | --- | --- |
| meeting_room.search | meeting_room_search | meeting-room-v1 | meeting_room.availability.read |
| meeting_room.reserve | meeting_room_reserve | meeting-room-v1 | meeting_room.booking.create |

管理员发布的员工版本绑定这两个精确修订。运行用户有会议室查询和预定权限时，解析器将两个能力放入本 Run 有效集合；如果只有查询权限，Toolkit 仅登记查询工具。即使用户在运行开始时获准，预定调用到达 Gateway 时仍验证当前授权、会议室目标、时间范围、先前查询收据、幂等操作键和当前停用状态。

`MeetingRoomRuntimeToolProvider` 根据能力项创建 `DynamicCapabilityAgentTool`，使用当前 Run 上下文调用 `MeetingRoomToolGateway`。Gateway 固定分派到 `MeetingRoomSystem` 的 A-201 Mock 查询或预定实现；它还要求同一 Run 先以完全相同的时间和人数成功查询，才允许预定。查询收据存于当前进程的内存映射，服务重启后不会恢复；预定结果本身以数据库中的 booking 记录为准。`AgentScopeRuntime` 不再导入旧的 `MeetingRoomTools`。

管理员通过 API 发布新版本、停用预定能力或改变指令后：

- 新 Run 按新的数据库版本和权限重算 Toolkit。
- 已经开始的 Run 继续使用旧定义版本及能力修订。
- 旧 Run 的后续写调用仍会受到最新授权和紧急停用规则约束。
- 服务重启后从数据库重建配置，不依赖 Java 常量恢复员工能力。

## 11. 错误处理与安全边界

| 错误 | 运行时处理 |
| --- | --- |
| 无当前发布版本或员工已停用 | 不创建可执行 Agent；返回明确配置不可用状态 |
| 发布版本引用缺失或摘要不符 | Run 失败并告警；不取最新修订代替 |
| 有效能力解析服务或权限数据库不可用 | 失败关闭；不把待判定能力暴露给模型 |
| implementationKey 未注册 | 发布校验阻止发布；运行中遇到时装配失败并留痕 |
| Tool Schema 与发布摘要不一致 | 注册失败或调用拒绝，要求生成新修订 |
| AgentScope 工具装配集合多于有效集合 | 阻断第一次模型调用并输出受控诊断 |
| 工具参数无法映射到确定业务动作/目标资源 | Gateway 拒绝，不能根据工具描述或模型声明放行 |
| 权限检查被拒绝 | 返回不暴露他人资源细节的拒绝结果；审计记录具体 reason code |
| 外部操作请求已发出但结果不确定 | 标记 UNKNOWN/待核查；不伪造成功，不自动重复可能产生副作用的请求 |

管理 API 应对配置内容做长度、Schema、引用范围和版本校验；敏感凭据以 SecretRef 关联独立凭据管理，不在草稿 GET、发布响应和审计文案中回显。工具描述、参数 Schema 和 Tool 名都属于配置输入，需要进行大小限制、命名唯一性和格式校验；这些校验不能代替执行授权。

## 12. 验收测试设计

### 12.1 已实现并通过的自动化测试

- API 集成测试覆盖目录读取、草稿写入、草稿校验、V2 发布和用户授权撤销。
- H2/JDBC 集成测试覆盖乐观锁草稿写入、发布版本生成、幂等重放、有效能力快照序列化/读回、授权撤销复核和工具审计写入。
- 会议室 Run 测试覆盖新发布定义切换到下一 Run、只将所选能力带入 Run、运行中撤权后拒绝工具调用、预定 Mock 核验、模型虚报和模型失败。总测试数及真实模型验收另见测试报告。

### 12.2 尚未覆盖或应补充的单元测试

1. Provider 缺失、重复工具名或 Schema/实现映射不一致时 Toolkit 装配失败关闭。
2. 同一能力在不同授权用户下得到不同的有效集合与 Hash；固定首轮配置只有一个用户，需构造测试数据覆盖。
3. 多 Run 并发时查询收据、调用结果和工具上下文互不串用。
4. DraftRevision 过期时草稿保存和发布冲突，不覆盖并发更新。
5. 发布事务中途失败时版本、绑定和当前版本指针整体回滚。

### 12.3 后续通用化的发布与数据库集成测试

本轮已由 MySQL V2 迁移和 H2/JDBC Repository 测试验证基本落库、发布幂等、快照读回和撤权复核。以下故障注入及完整不可变性场景尚未全部覆盖：

1. 发布事务成功时版本、版本能力绑定、Hash 和当前版本指针一致提交。
2. 中途故障时全部回滚，当前发布指针保持原值。
3. 发布后草稿修改不改变历史版本和已保存 Run 快照。
4. 版本绑定只能引用指定修订；不能发布未审核、已停用或 Schema 不完整的修订。
5. 数据库重启后 API 能读取同一发布版本和内容摘要。

### 12.4 AgentScope 装配与真实模型验证

1. 已在真实模型调用中验证“仅查询”版本只装配 `meeting_room_search`，请求不产生预定记录。
2. 已在真实模型调用中验证查询+预定版本装配 `meeting_room_search` 与 `meeting_room_reserve`，完成 A-201 Mock booking 并从 MySQL 核验。
3. 自动化测试已覆盖动态 API 和 Mock Run；尚未对 Tool 名/Schema 篡改、重复名称及多 Run 并发隔离做独立专项测试。

### 12.5 API 到数据库的端到端验收状态

已完成：

1. 已通过 API 发布仅查询版本并在不重启进程的情况下创建新 Run；之后恢复双工具版本，再用真实模型完成预定。
2. API 草稿保存使用 expectedDraftRevision，发布接口使用 requestId；Repository 测试覆盖同 requestId 重放与快照读回。
3. 用户授权撤销后已有 Run 的下一次工具调用在自动化测试中被拒绝。

尚待扩展和专项验证：

1. 并发发布期间旧 Run 固定旧版本、新 Run 使用新版本的交错时序测试。
2. 能力全局紧急停用对已装配 Toolkit 的后续调用生效。
3. 非管理员访问拒绝及用户 Run API 篡改身份/定义/能力列表的安全测试；首轮没有认证，不能据此满足生产安全要求。
4. 任意类名、脚本和 URL 无法通过管理 API 注册；当前 DTO 只接收目录中已存在的 code/revision，目录新增 API 尚不存在。
5. 发布事务中途失败和应用重启后的版本指针一致性故障注入。
6. 工具审计可与 Run 快照关联；当前仅保存参数哈希，不能从审计恢复具体业务对象参数。

## 13. 实施阶段与完成门槛

| 阶段 | 工作 | 阶段完成条件 |
| --- | --- | --- |
| 0. 契约与表设计 | 明确 Draft、PublishedVersion、EffectiveCapabilitySet、RuntimeCapability DTO 与 Flyway 关系 | 已完成；以本文件与 V2 迁移为准 |
| 1. 数据持久化 | 实现草稿、目录、发布版本、绑定、当前指针与有效快照 Repository | 已完成代码、H2/JDBC 测试及本地 MySQL V2 迁移验收；故障注入项列于 12.3 |
| 2. 管理 API | 实现目录查询、草稿读写、校验、发布、能力停用和用户授权 | 本地 Mock API 已实现并通过 Spring API 集成测试；尚无管理员认证 |
| 3. 有效能力解析 | 组合发布绑定、实现白名单、能力启停和首轮用户授权 | 已实现并通过 Run 测试；细粒度资源/动作权限待接入 |
| 4. 动态 Toolkit | 为 Run 创建受控 AgentTool 并装配独立 Toolkit | 已按 AgentScope 2.0.3 编译；本地真实模型分别验证了仅查询工具与查询+预定工具 |
| 5. 会议室迁移 | 去除 Run 与 AgentScopeRuntime 的会议室能力硬编码 | 已在 MySQL V2 和真实模型下通过动态查询及 Mock 预定闭环 |
| 6. 审计与运行验收 | 记录参数哈希、撤权拒绝和实际结果 | 自动化测试覆盖 H2 审计写入和撤权；真实 MySQL Run/booking 已核验 |

阶段 0 到阶段 6 的首轮会议室范围均已完成并验收。后续待办测试不改变本轮交付结论，但上线到非本机环境前必须先完成身份认证、统一授权和相应安全测试。

## 14. 待评审的实现决策

1. **管理身份与接口认证**：首轮明确不实现登录；管理 API 仅在 meeting-mock/test Profile 启用，meeting-mock 配置绑定 `127.0.0.1`。任何非本机部署前必须接入管理员认证和权限过滤。
2. **模型配置生命周期**：首轮 API/DB 动态保存 modelProvider 与 modelName；Key 和 Base URL 仍来自外置 YAML，不在本设计管理 API 中维护。
3. **能力目录与提供者边界**：首轮目录由 Flyway 种子创建，只能选择两项已注册会议室工具；新增副作用实现必须部署白名单提供者。
4. **权限策略持久化**：首轮使用 `user_id + capability_code` 布尔授权；业务 Permission、Subject、Resource 和 Scope 的细则后续接 security 模块。
5. **缓存和多实例一致性**：首轮不缓存授权；能力停用和撤权在每次 Tool 调用前查询数据库。多实例事件失效机制待部署形态确定。
6. **员工与租户范围**：首轮种子为单租户、员工 ID 1 的会议室验证；员工创建和多租户配置入口不在本次实现范围。

## 15. 本版修订

| 日期 | 版本 | 摘要 |
| --- | --- | --- |
| 2026-09-27 | 0.1 | Luna 初稿提出 API + 数据库动态配置设计；实现时修正接口路径、表结构、首轮认证边界和验收状态，并完成 MySQL/真实模型动态能力验证。 |
| 2026-09-27 | 0.2 | 对照代码、V2 迁移与真实验收结果修正实现状态、API/模块名称、目录管理边界、审计粒度、查询收据生命周期和未覆盖测试；补充实现测试报告链接。 |
