# SPEC-12 开放门槛与生产接入

状态：**运行准入、只读 readiness、严格资源默认及模拟接线的 profile 隔离已编码；本轮 readiness/profile/resource 配置定向测试通过。真实 IM、企业身份与资源授权适配仍无前提，保持关闭。** 编号：FE-12。日期：2026-10-09。
基线：`eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`，AgentScope Java `2.0.3`。
前置：[公共契约](SPEC-00-公共契约与实施约定.md)、[详细设计](../功能与界面闭环详细设计.md)。

## 1. 本 spec 的作用

为报告中 D 类能力和生产依赖建立独立开放门槛，避免一个管理页面上线就被称为能力全部可用。
账号、实时结果、授权、资产、恢复和安全查询等 A/B/C 切片按各自 spec 实施，不被未选定的外部提供方整体阻塞。
本文定义真实 IM、MCP 身份和资源权限的接入边界、实施切片与验收；提供方/企业 SSO 尚未指定是生产前提。
可写长期记忆、完整RAG、会议室业务不是本次默认新增业务；用户选择和成果物按SPEC-11 A/B/C独立纵向实施及原生探针门槛，不因已有类型默认宣称可用。
每轮 COLLABORATIVE/AUTONOMOUS 与员工 Team profile 分别管理；禁止用其中一项验收代替另一项开放。
模型连接、凭证引用、运行参数与观测页面按 [SPEC-10](SPEC-10-审计观测与运维连接.md) 实施，本文不重复 CRUD。

## 2. 基线事实与承接范围

| 能力 | 当前事实 | 本次承接 | 独立生产前提 |
| --- | --- | --- | --- |
| 在线员工创建/启停 | 当前 HTTP 仅编辑已有草稿 | SPEC-04 定义完整生命周期 | 并发 ID、审计、发布和可用性验证 |
| 新 profile | 配置/原生受控执行存在，默认仅 LEGACY_STABLE | 配置表单、精确版本、安全进度 | 单项 profile 验收，服务端开关 |
| 真实 IM | 模拟 HMAC 入站/模拟发送 | 渠道管理、Delivery 核查，真实适配契约 | 指定提供方、测试账号、凭据与回执验证 |
| 生产 MCP 身份 | 未配置时拒绝；模拟 Token 实现存在 | 连接治理和凭据边界设计 | 企业身份源、Token 交换契约、远端权限 |
| 资源级权限 | 默认 deriveResource=businessAction、allows=true | 安全默认策略切片和资源适配契约 | 每业务能力资源语法/授权事实来源 |
| 可写长期记忆 | 发布校验拒绝 memoryEnabled=true | 保持关闭、说明原因 | 独立需求、权限、删除/恢复/隔离设计 |
| 完整 RAG | 当前为只读技能/知识文件资产 | 资产修订管理 | 独立导入、检索、ACL、评测与更新方案 |
| 会议室 | V3 退出 Mock 种子/表，残留执行器 | 无生产页面新增 | 真实业务源与预约权限/冲突契约 |
| 每轮协作/自治 mode | Session 服务明确拒绝非 DIRECT | 保持 DIRECT，区分 profile | 独立路由/状态/权限/取消验收 |
| 通用选择/成果物 | 类型与注册表不等于业务闭环 | 已有文本和特定工具审批继续接入 | 上传、存储、ACL、类型 DTO 和生命周期 |

源码证据：[配置校验](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/AgentDefinitionManagementService.java)、[默认装配](../../../haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/configuration/HarnessRuntimeConfiguration.java)。
渠道证据：[ChannelWebhookController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/channel/ChannelWebhookController.java)、[模拟发送器](../../../haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/channel/SimulatedChannelOutboundSender.java)。
权限证据：[DefaultToolResourcePolicy](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/tool/DefaultToolResourcePolicy.java)、[McpCapabilityExecutor](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/mcp/McpCapabilityExecutor.java)。
Run mode 证据：[SessionApplicationService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/session/SessionApplicationService.java)。
本轮未运行以上生产接入验收；历史模拟测试不能填写为本轮真实提供方验证通过。

## 3. 开放状态与产品行为

每项能力的开放记录包含 gateId、状态、依赖、环境、版本、验证记录引用、责任人和更新时间。
状态为 DESIGNED→IMPLEMENTED_CLOSED→VALIDATED→ENABLED；验证失败/依赖撤销时为 SUSPENDED。
VALIDATED 要求所部署版本和环境对应的实际验证证据；文档完成只能到 DESIGNED。
已新增只读 GET `/api/admin/v1/runtime/readiness`，聚合配置开关、依赖接线和本部署验证清单匹配状态。
响应 `{schemaVersion:1,items:[{gateId,state,configured,verificationMatched,enabled,reasonCode,verificationReasonCode,requirements,checkedAt}],nextCursor:null,hasMore:false}`，固定门槛集合不分页；不返回证据引用、清单路径或凭据。
该接口不自动写 ENABLED，不把一次健康检查当端到端验证，也不返回 Token、密钥或回调原始载荷。
后台显示能力状态及缺少的明确前提；普通用户仅呈现已开放且授权可用的功能。
未实现业务不放可点击“执行”入口；管理页可以展示只读“待接入”说明和验证要求。
readiness 查询失败显示状态未知，服务端执行仍按门槛拒绝；前端隐藏按钮不能承担权限控制。
门槛变更由运维配置/受审计发布流程执行，本次不提供任意浏览器开关绕过验收。
验证记录由受审计部署清单提供，包含 commit、环境、gateId、evidenceRef、owner 与 verifiedAt；只接受 schemaVersion=1、唯一精确匹配、有效时间戳和完整字段；不扫描历史报告自动认定通过。
readiness 分开返回“配置启用”和“验证记录匹配”；配置开启却缺少本部署证据时返回前提缺失，不能标记已验证。

## 4. profile 开放与 Run mode

沿用 `haizhuo.brain.employee.enabled-profiles`，LEGACY_STABLE 必须保留；配置选项来自 SPEC-04。
`RuntimeProfileAdmissionPolicy` 已接入发布校验与 SessionApplicationService 的新 Session/Run 准入；两处读取同一策略，执行中的 Run 不重新改写冻结包。
SINGLE_SKILLED：验证资产加载、零工具运行、计划上限、完整结果、隔离、取消与审批恢复。
TEAM_READONLY：验证固定/通用/动态只读专家、父权限上界、预算、精确结果和根验收、修订/依赖与安全进度。
TEAM_AUTONOMOUS_READONLY：在前项基础上验证原生 Team、持久预算、停止超时、fence、重启恢复和管理员核查。
采用 AgentScope Java 2.0.3 原生 Harness/子 Agent/Team 扩展；不为开放按钮重写循环或 Team 调度。
上线每个 profile 前固定依赖版本、运行配置、数据迁移、worker 配置和验收环境；记录证据并灰度。
新建 Session 使用新发布版本；旧 Session 的版本/成员/授权快照不得随开关重写。
关闭门槛后停止新准入；正在运行任务按原控制/恢复流程收尾，禁止为“关闭”直接删除持久状态。
当前 `POST /api/v1/sessions/{sessionId}/runs` 仅支持 DIRECT；非 DIRECT 继续返回冲突。
不新增 Run mode 开放 API；未来若支持每轮 mode，须单独定义与 profile、角色、会话槽、排队和结果交付的关系。

### 4.1 本轮实现与开放状态

- 启动配置默认只允许 `LEGACY_STABLE`。profile manifest 需要 `HAIZHUO_BRAIN_VERIFICATION_MANIFEST`、`HAIZHUO_BRAIN_DEPLOYMENT_COMMIT` 与 `HAIZHUO_BRAIN_DEPLOYMENT_ENVIRONMENT`；额外 profile 必须同时显式列入配置并匹配本 commit/环境的唯一 gate 记录，缺项、重复、未来时间、损坏或超限清单均失败关闭。
- `DeploymentVerificationManifestReader` 不把路径或 evidenceRef 返回管理 API；生产门槛仍需要部署流水线和负责人维护证据真实性。当前没有本部署验证清单，因此 SINGLE_SKILLED、TEAM_READONLY、TEAM_AUTONOMOUS_READONLY 均未开放。AgentScope Java 依赖锁定为 2.0.3；将来每条 profile 证据仍须覆盖精确版本和验收场景。
- MCP 模拟 Token 和 loopback HTTP endpoint 现仅在 active profiles 恰为 `test` 且显式设置 simulator 开关时装配；默认关闭，在生产 profile、`test+prod` 等混合 profile 中即使显式误开仍返回不可用 Token provider 并拒绝 loopback HTTP。`AuthenticatedUser` 只有平台 userId/角色/authVersion/改密状态，不含企业 subject；当前未接企业身份源，readiness 不把模拟 provider 计为企业接入。
- `StrictToolResourcePolicy` 为默认主策略；未登记精确修订为 RESOURCE_FREE 的能力一律拒绝，尚无业务资源 resolver/授权事实来源。生产不能切回 allow-all 策略；`LEGACY_CAPABILITY_ONLY` 仅允许 active profiles 精确为 `test`，混入 `prod` 等其他 profile 会启动拒绝；readiness 将该门槛显示为 IMPLEMENTED_CLOSED。
- Markdown 导出只在 `HAIZHUO_BRAIN_MARKDOWN_ARTIFACTS_ENABLED=true`、配置私有目录并有 `artifact.markdown-export` 本部署证据时启动。当前环境没有这些条件，API 保持 FEATURE_NOT_AVAILABLE。内容块生产者与通用 USER_SELECTION 仍分别显示 IMPLEMENTED_CLOSED/DESIGNED。
- 模型连接目录依赖专用 Vault 凭据引用与解析边界；本轮未虚构 Vault，也未持久化凭据，readiness 将其显示为 DESIGNED。真实 IM 和企业身份 gate 始终要求具体适配器，只有配置值/模拟实现不能打开 gate。
- 当前新增 gate 包括 `tool.resource-authorization`、真实 IM/企业身份、三个 profile、每轮协作/自治、长期记忆、完整 RAG、会议室、Markdown 导出、内容块、模型连接目录及通用选择。所有未满足前提的 gate 均不返回 ENABLED。

## 5. 真实 IM 接入切片

优先验证指定提供方与 AgentScope Channel/ChannelManager 的现成扩展；在 PlatformChannel 边界薄适配。
平台 Session/Run 继续拥有排队、取消、身份、版本和持久事实；不并行维护第二套 Gateway 会话状态。
提供方适配器契约需要同时覆盖验证入站、解析消息、发送消息、核查发送结果；具体签名字段随选定提供方确定。
保留现有模拟 `POST /api/v1/channels/{provider}/webhook` 行为，不把通用模拟 DTO 当真实协议。
拟新增真实提供方入口 `/api/v1/channels/providers/{provider}/accounts/{bindingId}/callback`；确切 HTTP 方法由原生协议确定。
它以原始字节验签/解密/防重放，验证成功后映射可信 accountBinding、providerEventId、peer、sender 与 replyTarget。
外部 ID 不直接变成平台 userId；绑定到现有可信用户后才受理，撤销/停用身份即时阻止新 Run。
群 peer 和 sender 分开；DmScope 显式配置，默认不采用共享 MAIN；群共享上下文另行验收。
按提供方消息唯一键持久去重；受理事实提交后才确认回调，重复消息返回原受理结果。
出站沿用既有 Outbox/lease/fence/idempotency；sender 只提交真实协议并返回确定的结果级别。
结果级别 DELIVERED/RETRYABLE_FAILURE/PERMANENT_FAILURE/UNCERTAIN 与提供方文档对应，HTTP 2xx 是否送达必须核验。
UNCERTAIN 必须按业务幂等键或 providerMessageId 核查，不能直接重发；核查/人工处置沿用 Delivery spec。
真实回执也验签、去重并绑定原 Delivery，不允许回执修改 Run 的执行成功事实。
首次切片仅指定提供方的文本入站与根完整结果出站；图片、语音、附件另列契约。
模拟无 endpoint 时视为投递成功的行为不能出现在生产 sender；真实配置缺失返回不可用。
生产前提：提供方和账户、受控测试用户、验签/解密凭据引用、网络/回调登记、速率限制与回执能力已确定。

## 6. MCP 身份与凭证接入切片

沿用 [McpUserTokenProvider](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/mcp/McpUserTokenProvider.java) 的短期用户 Token 端口。
当前 AuthenticatedUser 不含企业租户/SSO subject；不得从浏览器 userId 或模型参数构造企业身份。
拟新增 EnterpriseMcpUserTokenProvider，通过服务端可信 userId 解析企业 subject、目标 connection audience 和 scopes。
首次适配选一个受控企业身份源；Token 交换协议、签名校验、有效期、刷新/吊销由该身份源契约确定。
长效凭据只保存于 SPEC-10 的专用秘密边界；连接 DTO 保留引用，绝不在草稿、Run 包或日志保存 Token。
短效 Token 仅在执行阶段获取，可缓存到过期前安全余量；缓存键含用户、连接修订、audience 和授权版本。
模拟 Token 提供者和允许 loopback HTTP 的 MCP endpoint policy 限制在 active profiles 仅为 `test` 的隔离测试装配；配置 simulator secret 或 simulator enabled 属性不能在其他 profile 激活模拟身份或开放 loopback。
MCP 服务端仍按终端用户和目标资源判权；管理员发现权限不自动授予普通用户调用权限。
沿用当前连接修订/工具 schema/readOnly 校验、逐工具批准、员工绑定、发布和用户授权流水线。
缺身份/凭据返回 CREDENTIAL_UNAVAILABLE；过期、禁止与不可用保留现有安全错误映射。
写操作超时/结果未知仍进入核查，不自动获取新 Token 重放原动作。
生产前提：企业身份主键映射、Token broker、audience/scopes、测试用户权限样例、远端幂等/核查能力已确定。
不提供用户向页面粘贴企业 access token 的常态接入方案；账号连接入口由专门 SSO 契约确定后再设计。

## 7. 资源级授权的安全默认

保留 ToolResourcePolicy 扩展点和现有流水线顺序；资源由已审核能力修订与参数在服务端派生。
拟新增 StrictToolResourcePolicy、CapabilityResourceResolverRegistry、ResourceAuthorizationPort。
资源键结构由业务适配器注册 `{resourceType,canonicalId,action}`；浏览器/模型传入 resourceId 不直接成为判权事实。
没有 resolver、资源不存在、身份/策略不可用时拒绝执行；资源字符串不可回退为 capability.businessAction 后全允许。
MCP 在平台能力授权后仍由远端按受信 Token 判权；真实本地写工具必须有本地资源适配器与授权来源。
已审核无需对象资源的纯计算/只读工具可显式声明 RESOURCE_FREE；缺声明不视为无需资源权限。
部署模式拟新增 `haizhuo.brain.tool.resource-policy-mode=STRICT`；生产启动要求 STRICT。
旧模式 `LEGACY_CAPABILITY_ONLY` 仅用于 active profiles 恰为 `test` 的本地兼容测试，必须显式设置；生产或混合 profile 启动拒绝该模式，不自动开放真实写能力。
策略逐能力注册灰度：先实现 resolver 和授权测试，再启用该能力；缺配置的能力显示不可执行原因。
本次定义端口/安全默认，不建设通用资源权限后台；具体业务资源授权管理随业务源实施。
所有拒绝/核准写安全审计（user、action、规范化资源摘要/哈希、decision、policyVersion），不写原始工具参数。
审批恢复后重新检查资源权限与凭据，撤权即拒绝；用户点同意不会跨过资源策略。
停用/授权变化由实际执行边界生效；冻结员工能力快照不冻结对业务资源的永久授权。

## 8. 文件入口与实施顺序

| 模块 | 现有/拟新增入口 | 职责 |
| --- | --- | --- |
| bootstrap | HarnessRuntimeConfiguration、ChannelConfiguration、RuntimeReadinessConfiguration、RuntimeProfileAdmissionConfiguration、DeploymentVerificationManifestReader | 严格装配、同一 profile 准入、按部署证据读取 gate、只读 readiness |
| api | 已新增只读 `RuntimeReadinessController`；指定提供方 CallbackController 仍待真实协议选择 | 管理状态与协议入口，不承接调度 |
| platform | ToolResourcePolicy、McpUserTokenProvider、ChannelOutboundSender；拟新增 RuntimeProfileAdmissionPolicy、资源 resolver/身份映射端口 | 可信授权与边界契约 |
| infrastructure | 拟新增指定提供方 adapter/sender、EnterpriseMcpUserTokenProvider、资源授权适配器 | 原生扩展连接业务源 |
| web | 管理状态提示，引用 SPEC-04/10 与渠道/Delivery 页面 | 仅消费实际可用状态 |

1. 固定 gate 清单与生产环境定义；对装配/授权/回调符号做 impact，补证 UNKNOWN/HIGH 风险。
2. 完成 A/B/C 页面及查询切片，保持 D 类入口门槛；实现只读 readiness 和配置错误提示。
3. 实现 STRICT 资源默认、生产装配拒绝和纯资源自由工具声明；先用受控业务 resolver 验证双用户隔离。
4. 选定企业 SSO/MCP 服务，按 token 端口实现最小真实只读调用；再验证受控写动作与未知结果。
5. 选定 IM 提供方，先验证验签/身份/重复入站，再验证 Outbox/回执/重启恢复。
6. 为每个新 profile 执行独立验收，灰度开放；门槛条件与实际部署版本绑定。

## 9. 兼容、迁移与回退

不修改旧 Flyway；新增业务适配需要映射/秘密引用/回执字段时，新建迁移并单独审批上线切片。
旧模拟入口、模拟 Token 和 legacy 资源策略保留用于隔离回归，生产接线禁止隐式回退到它们。
新增 readiness 为旁路读接口；旧前端无该接口仍执行服务端门槛，不改变已有业务响应。
退出提供方或关闭 profile 只阻止新准入；保留 Run、结果、Delivery、审计和凭据引用供核查。
回退 sender 前先停止认领并核查在途 UNCERTAIN，不跨适配器盲重发；幂等键不能更换。
凭据吊销/身份撤销立即使新调用失败，已有 UNKNOWN 写动作继续核查；不得删除事实掩盖未知结果。
STRICT 授权发布回退只允许关闭受影响能力，不能在生产切回全允许以维持可用性。

## 10. 验收与 DoD

| 编号 | Given / When / Then |
| --- | --- |
| FE-12-A01 | 给定无真实提供方/SSO，查询 readiness，则列具体缺前提；A/B/C 已完成页面仍正常可用 |
| FE-12-A02 | 给定默认配置，发布未验收 profile/提交非 DIRECT mode，则分别被后端拒绝，UI 无假可用入口 |
| FE-12-A03 | 给定生产 legacy 资源模式或模拟身份接线，启动/开放检查，则拒绝并输出安全原因 |
| FE-12-A04 | 给定合法及篡改/过期 IM 回调，处理后则仅合法绑定身份能受理，重复合法事件仅一轮 Run |
| FE-12-A05 | 给定发送超时且提供方已接收，核查后则状态确定且不重复消息；Run 成功状态保持 |
| FE-12-A06 | 给定用户甲/乙不同 MCP 资源权限，执行同能力则按各自受信 Token 判权，发现权限不继承 |
| FE-12-A07 | 给定 Token 过期、吊销、缺映射，执行则安全失败，页面/日志/结果无 Token |
| FE-12-A08 | 给定未注册资源 resolver 或策略不可用，调用真实写工具则拒绝，不能回退 allows=true |
| FE-12-A09 | 给定审批后资源撤权，恢复执行则重新判权拒绝，用户同意不能覆盖撤权 |
| FE-12-A10 | 给定单 profile 已验证版本及旧 Session，灰度后则仅新准入使用新配置，旧冻结数据不重写 |
| FE-12-A11 | 给定长期记忆/RAG/会议室未获独立需求，页面交付后则这些能力保持当前边界，无假入口 |
| FE-12-A12 | 给定回退与在途 UNCERTAIN Delivery，处理则先核查、保留幂等/审计，禁止跨 sender 自动重放 |

单元/HTTP 层验证 gate、错误、身份映射和资源默认；MySQL 集成验证去重、Outbox、身份撤销和恢复。
真实提供方验收必须用指定环境/测试账号/可核查动作，记录最终 URL/协议状态/业务字段与回执证据。
DoD 按 gate 独立判断：设计与本地测试通过可记 IMPLEMENTED_CLOSED；真实依赖验证后才记 VALIDATED。
尚未指定 IM 提供方、企业身份源或业务授权来源时，对应生产 gate 保持关闭并明确责任前提。
本轮实现状态与验收记录：FE-12-A01、A02、A03 的 readiness/API、profile 准入、资源策略和模拟接线配置本轮测试通过：`DeploymentVerificationManifestReaderTest` 4/4、`HarnessRuntimeConfigurationTest` 3/3、`RuntimeResourcePolicyConfigurationTest` 4/4、readiness/controller 相关测试通过。profile/资源/Markdown 运行路径按 fail-closed 装配。源码核对确认当前没有真实 IM sender、企业 SSO subject/Token broker、业务资源 resolver 或 Vault 凭据解析器；真实提供方/身份/授权 gate 继续关闭，模型连接目录写入也未开放。A04–A09 的真实 IM、SSO/MCP、资源 resolver/业务授权和凭据撤销仍未实现/未验证；A10 只覆盖 profile 准入代码，生产灰度与旧 Session 冻结尚未做 MySQL/部署验证；A11/A12 保留现有能力边界，未做生产演练。HTTP 真服务器、真实 MySQL、浏览器和真实提供方结果分别记录于实施报告，不用历史数字替代本轮证据。
