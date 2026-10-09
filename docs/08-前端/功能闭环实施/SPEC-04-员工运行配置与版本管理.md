# SPEC-04 员工运行配置与版本管理

状态：**运行配置、版本/生命周期主体及精确连接绑定已编码；定向 Java、前端类型/单元/生产构建通过；真实 MySQL、HTTP 与浏览器并发流程未验收**。编号：FE-04。日期：2026-10-09。
基线：`eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`，AgentScope Java `2.0.3`。
前置：[公共契约](SPEC-00-公共契约与实施约定.md)、[详细设计](../功能与界面闭环详细设计.md)。

## 1. 目标与范围

管理员能完整回读、编辑、校验和发布员工运行配置，选择固定成员的精确发布版本，并查看、比较历史版本。
恢复历史内容必须先复制到草稿，再重新校验、发布一个新版本；已有 Session 继续使用冻结版本。
本 spec 同时定义在线创建、展示未发布员工、启停员工的独立切片，解决当前只能编辑种子员工的生命周期缺口。
不提供物理删除，不提供每轮 `COLLABORATIVE/AUTONOMOUS` 按钮，不启用可写长期记忆。
定义管理和版本查询完成，不构成新 profile 的开放授权；开放要求见 [SPEC-12](SPEC-12-开放门槛与生产接入.md)。
基础配置/版本切片独立完成；模型连接引用是依赖 [SPEC-10](SPEC-10-审计观测与运维连接.md) 的后续扩展，SPEC-10 不反向依赖此扩展。

## 2. 当前事实与证据

| 当前事实 | 当前源码入口 | 本次补齐 |
| --- | --- | --- |
| 草稿 HTTP 已接受 configuration，省略时保留旧值 | [Controller](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/agentdefinition/AgentDefinitionManagementController.java)、[Service](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/AgentDefinitionManagementService.java) | 表单读写完整配置，保留兼容行为 |
| 配置包含 profile、预算、角色及固定成员 | [EmployeeRuntimeConfiguration](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/EmployeeRuntimeConfiguration.java) | 对应结构化编辑器 |
| 默认仅开放 LEGACY_STABLE | [AgentCapabilityConfiguration](../../../haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/configuration/AgentCapabilityConfiguration.java) | 后端只读 runtime-options，前端据此提示 |
| 前端忽略 configuration，指令限 4000 字符 | [DefinitionPanel](../../../haizhuo-brain-web/src/views/admin/DefinitionPanel.vue) | 回读配置；指令限制与后端 12000 对齐 |
| 版本持久化、草稿锁和发布请求幂等已有 | [JdbcAgentDefinitionRepository](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/employee/JdbcAgentDefinitionRepository.java) | 管理查询、历史复制、重试键保持 |
| 员工 BIGINT 主键没有 AUTO_INCREMENT，已有 row_version | [V2](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V2__agent_capability_dynamic_configuration.sql) | 并发安全 ID 分配与元数据并发控制 |
| 普通员工列表仅返回启用且已发布员工 | 同上 Repository 的 listEnabled | 新增管理员列表，不复用普通列表做管理选择 |

这些是静态代码事实。本轮没有运行功能测试；历史测试报告不作为本 spec 的通过证据。

## 3. 页面与状态

沿用管理中心“员工定义编排”，增加员工列表、基本信息、运行配置、固定成员、校验结果、发布版本六个区域。
管理员列表显示停用、未发布员工；搜索 employeeCode/displayName，按 enabled/published 筛选。
未发布员工显示“草稿，普通用户不可见”；发布与启用分别操作，发布成功不得自动启用停用员工。
版本抽屉显示版本号、hash、profile、发布人、时间、指令、能力修订和固定成员；正文仅管理员可见。
版本比较由两份不可变详情做字段级比较，不把 publishedAt、版本号、hash 当成业务内容差异。
“恢复为草稿”预览将替换的字段、成员版本和能力修订，要求理由；完成后仍需校验与发布。

```text
加载中 → 已保存草稿 → 本地修改 → 保存中 → 已保存草稿 → 校验中 → 可发布/不可发布
可发布 → 发布中 → 已发布；本地修改立即使旧校验失效
保存冲突 → 保留本地编辑 → 查看最新草稿 → 人工重新编辑/重新应用
```

切换员工或离开页面时如有未保存编辑，提供保存、放弃、继续编辑；加载序号防止旧响应覆盖新员工。
运行配置切换会影响 team/members/预算：先展示差异再清理不适用字段，不静默删除管理员编辑内容。
已有草稿使用关闭 profile 时仍允许查看和保存草稿，显示不可发布原因；不自动降级到 LEGACY_STABLE。

## 4. 配置规则

后端校验为最终依据；runtime-options 和表单共用后端常量来源，前端校验只用于即时反馈。

| 字段 | 约束 |
| --- | --- |
| schemaVersion | LEGACY_STABLE 为 1，其余 profile 为 2 |
| profile | LEGACY_STABLE、SINGLE_SKILLED、TEAM_READONLY、TEAM_AUTONOMOUS_READONLY |
| maxIterations | 1–20 |
| maxParallelDelegations | 0–2 |
| maxExpertInvocationsPerRun | 0–4；Team profile 至少为 1 |
| syncTimeoutSeconds | 1–120 |
| memoryEnabled | 固定 false，不提供可启用开关 |
| instructions | 1–12000 字符；沿用服务端 trim 规则 |
| modelProvider/modelName | 供应商 openai/openai-compatible/dashscope；名称 1–128 字符 |
| capabilities | 唯一且修订存在；仅 LEGACY_STABLE 强制至少一项，且不可选择技能/知识资产 |

非 Team profile 的 team 必须为 null，members 必须为空。
Team 至少一个固定成员；TEAM_READONLY 最多 8 个，TEAM_AUTONOMOUS_READONLY 最多 2 个。
成员字段：roleId、employeeId、definitionVersionId、steps；steps 为 1–8。
roleId 匹配 `[a-z][a-z0-9_-]{0,31}`，不可为保留值 coordinator；roleId 和 employeeId 各自唯一。
禁止自身引用；成员必须是同租户启用员工，其精确发布版本 profile 只能为 LEGACY_STABLE/SINGLE_SKILLED。
defaultRoleId 必须是固定成员，userSelectableRoles 必须是成员角色且不可重复。
当前配置校验允许来源为coordinator或成员、目标仅固定成员；禁止同角色、自引用和重复边。运行工厂的只读叶子关闭继续委派，因此当前首个可执行UI只开放coordinator作为委派来源；读取旧成员来源关系要提示“未支持执行”，不能伪称已可运行。发布校验补DELEGATION_SOURCE_NOT_SUPPORTED，而不改写旧冻结版本。
UI 显示“管理员声明的允许委派关系”，不在页面实现调度或推断自动运行顺序。
固定成员只从版本列表选择精确版本；更新成员当前版本后，已有父版本不得跟随变化。
steps仍按当前契约允许1–8，但只读叶子工厂还有4步上限；页面同时显示“声明步数”和min(4,steps,冻结运行配置maxIterations)的有效上限，不暗示8步必然执行。

### 4.1 通用/动态专家目标配置独立切片

当前服务仅接受固定委派目标，而[HarnessAgentFactory](../../../haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/factory/HarnessAgentFactory.java)已将general-purpose/dynamic-expert解释为原生工厂启用目标；两者存在发布接线缺口，不属于当前可发布事实。
保留allowedDelegations的fromRoleId/toRoleId字符串：仅TEAM_READONLY目标校验新增保留名单general-purpose、dynamic-expert，来源首版限coordinator。固定成员禁止coordinator/general-purpose/dynamic-expert及dyn-*命名，避免重复注册或冒充动态来源。
runtime-options新增reservedDelegationTargets：kind、targetRoleId、readonly、supportedProfiles、profileGate；UI作为“通用只读/动态只读工厂”独立选项，没有employeeId/version，不进入defaultRoleId/userSelectableRoles。至少一个固定成员门槛保留。
编译继续冻结配置字符串并纳入既有hash；manifest.members只含固定成员，allowedDelegations可含保留目标。通用/动态继承父Run可信身份、冻结连接与只读权限上界，不新增第二个专家工厂/调度器，动态dyn-*实例不可用户直接选择。
新增目标配置不默认开放TEAM_READONLY，须FE-12门槛通过；TEAM_AUTONOMOUS_READONLY本切片不开放这些保留目标，其运行契约独立验证。

## 5. HTTP 契约

以下路径均要求 PLATFORM_ADMIN、可信操作者和 CSRF。现有身份没有 tenantId；当前范围使用服务端固定租户 1。
新增接口不接受客户端 tenantId，不能把当前固定租户称为企业多租户隔离已经完成。
新增列表统一 `{items,nextCursor,hasMore}`，limit 默认 20、最大 100；规则见 SPEC-00。

| 状态 | 方法与路径 | 请求/响应要点 |
| --- | --- | --- |
| 已有 | GET `/api/admin/v1/agents/{employeeId}/draft` | 返回完整草稿、draftRevision、configuration、updatedAt |
| 已有 | PUT 同上 | expectedDraftRevision、instructions、modelProvider、modelName、capabilities、configuration、reason |
| 已有 | POST `/api/admin/v1/agents/{employeeId}/validate` | publishable、issues(code/severity/fieldPath/message/relatedId)、draftRevision、previewHash |
| 已有 | POST `/api/admin/v1/agents/{employeeId}/publish` | expectedDraftRevision、requestId、reason；201 PublishedEmployee |
| 拟新增 | GET `/api/admin/v1/agents/runtime-options` | profiles、字段范围、schemaVersion、memberLimits、supportedMemberProfiles、instructionMaxLength、optionsVersion |
| 拟新增 | GET `/api/admin/v1/agents` | query、enabled、published、cursor、limit；员工管理摘要列表 |
| 拟新增 | POST `/api/admin/v1/agents` | employeeCode、displayName、requestId、reason；201 员工摘要及初始草稿 |
| 拟新增 | PATCH `/api/admin/v1/agents/{employeeId}` | expectedRowVersion、displayName、requestId、reason；更新后摘要 |
| 拟新增 | PUT `/api/admin/v1/agents/{employeeId}/status` | expectedRowVersion、enabled、requestId、reason；更新后摘要 |
| 拟新增 | GET `/api/admin/v1/agents/{employeeId}/versions` | cursor、limit；版本摘要列表 |
| 拟新增 | GET `/api/admin/v1/agents/{employeeId}/versions/{versionId}` | 不可变版本详情；员工与版本归属校验 |
| 拟新增 | POST `/api/admin/v1/agents/{employeeId}/draft/restore` | sourceVersionId、expectedDraftRevision、requestId、reason；返回新草稿 |

runtime-options的每项profile返回`{profile,schemaVersion,configuredEnabled,publishEnabled,disabledReasonCode,memberLimit}`。
configuredEnabled只反映实际enabled-profiles；publishEnabled为服务端当前有效准入，复用FE-12的RuntimeProfileAdmissionPolicy。FE-12尚未接线时据既有配置，并标admissionEvidenceStatus=NOT_EVALUATED；接线后配置开启但本部署证据不匹配必须publishEnabled=false并给出原因。页面不以configuredEnabled代替可发布状态，不新增浏览器可写开放接口，也不返回凭据/Worker内部配置。
客户端选择关闭 profile 可编辑设计草稿，发布按钮禁用；后端仍返回 PROFILE_DISABLED 校验问题。
员工摘要：employeeId、employeeCode、displayName、enabled、published、currentPublishedVersionId、rowVersion、createdAt。
employeeCode 长度 1–64，创建时唯一且不可修改；displayName 长度 1–128。
版本摘要：versionId、employeeId、versionNo、contentHash、profile、publishedBy、publishedAt、current。
版本详情增加 instructions、modelProvider、modelName、capabilities、configuration、冻结成员的可读名称与 hash。
管理查询能读取停用员工的历史；发布/启用和固定成员可用性继续执行当前严格校验。
版本列表以 publishedAt+versionId 稳定倒序，createdAt 语义为 publishedAt；员工列表以 createdAt+employeeId 稳定倒序。
恢复接口只复制业务内容，不复制旧 versionNo/hash/发布人或直接改当前发布指针。
首次创建使用初始 LEGACY_STABLE 配置，员工 enabled=false、无已发布版本；用户补指令/模型/能力后才能发布。
FE-10 就绪后，草稿可扩展可选 `modelConnectionRef={connectionId,revision}`，缺省保持既有 provider/model 选择。
该扩展校验连接与 modelProvider 一致，精确 revision 冻结到不可变版本/Run 绑定；endpoint、秘密正文及 Vault 版本不进入运行包。
恢复历史连接引用仍重新验证可用性；连接切片未实施时不得丢弃已保存的未知引用或伪造默认连接。

## 6. 事务、并发与失败

新增员工 ID 由拟新增 `platform_id_allocator(scope_key,next_id)` 的 digital_employee 行加锁分配。
Flyway 新迁移一次性以现有最大 employeeId 初始化 next_id；运行时用 SELECT FOR UPDATE 分配，不执行无锁 MAX+1。
创建员工、初始草稿、管理审计和 requestId 回执在同一事务；回滚后不留下半个员工。
新增员工 created_at；旧行以迁移时间回填，同一时间按 id 稳定排序；不伪造历史创建时间。
元数据修改与启停采用 row_version 条件更新；发布继续在员工锁下增加 row_version。
启用要求已有完整可运行发布包、profile 已开放和依赖可用；未发布返回 409 EMPLOYEE_NOT_PUBLISHED。
停用阻止新 Session/新 Run；已执行 Run 不自动取消，现有 Session 冻结版本保留，下一轮按当前可用性拒绝。
恢复草稿与保存使用同一草稿行锁；旧修订返回 409，不自动覆盖；恢复成功 draftRevision 加一。
恢复只完成草稿写入，下一次发布重新验证能力启用、成员租户/版本/状态、配置门槛并重新编译运行包。
新操作 requestId 回执绑定操作者、员工、动作和规范化请求 hash；相同键不同请求返回 409 IDEMPOTENCY_CONFLICT。
同一次发布失败/超时重试复用 requestId；编辑内容后再发布生成新键，不能每次点击都换键。
发布按钮只针对已保存、与当前校验 draftRevision 相同的草稿；后端事务仍校验锁定修订。
校验错误 fieldPath 定位对应表单；422 DEFINITION_NOT_PUBLISHABLE 保留现有 validation 响应。
跨员工版本、不可见资源返回 404；503 时保留草稿；校验响应过期丢弃，不显示“可发布”。

## 7. 模块与文件入口

| 模块 | 现有入口或拟新增文件 | 职责 |
| --- | --- | --- |
| api | AgentDefinitionManagementController；拟新增 EmployeeAdministrationController/AgentDefinitionVersionController | 参数、权限、DTO，不在控制器复制发布事务 |
| platform | AgentDefinitionManagementService/Repository；拟新增 EmployeeAdministrationService、AgentDefinitionQueryService | 生命周期、配置选项、版本读取、恢复语义 |
| infrastructure | JdbcAgentDefinitionRepository；拟新增 JdbcEmployeeAdministrationStore/命令回执存储 | 查询、乐观锁、ID 分配、审计事务 |
| bootstrap | AgentCapabilityConfiguration | 同一 enabledProfiles 注入校验和 options，避免两份开关 |
| web | DefinitionPanel.vue、admin.ts；拟新增 EmployeeListPanel、RuntimeConfigurationForm、DefinitionVersionPanel | 表单、字段定位、版本比较、请求状态 |
| platform/infrastructure | HarnessDefinitionPublisher、TransactionalHarnessDefinitionPublisher 既有发布链路 | 复用包编译和事务，不新增 Agent 循环或协作调度 |

## 8. 实施步骤与兼容回退

1. 核对 HEAD、现有发布事务和新增 API 的命名；对拟编辑符号做 GitNexus impact，记录 UNKNOWN/HIGH 风险。
2. 提取只读 runtime-options DTO，共用当前校验范围；保留旧草稿省略 configuration 的保留语义。
3. 实现管理员员工/版本查询，增加停用与未发布员工的查询测试；版本详情不返回凭据。
4. 新增迁移和生命周期事务，完成并发创建、命令回执、rowVersion 测试；不修改旧 Flyway。
5. 增加恢复草稿命令；复用当前 saveDraft 和发布编译边界，验证版本冻结与重新校验。
6. 扩展 DefinitionPanel，全量回读 configuration；按 profile 修正零工具与 12000 字符限制。
7. 加入版本抽屉、差异预览、恢复操作和持久发布 requestId；再完成真实浏览器纵向场景。
8. 独立补4.1的保留委派目标校验/选项/编译一致性；验证原生固定与内置工厂不重复注册、只读/预算/来源，不以运行时已存在推断发布链已完成。

查询和新生命周期可分别上线；新表单先受前端发布开关控制，但服务端 profile 门槛始终有效。
旧 API、普通员工数组列表和 Session 冻结逻辑保留；不把历史 configuration 重写成默认值。
回退前端可回到基础表单；回退新后端时保留新增表/列与审计，不删除已产生员工或版本。
关闭某 profile 只停止新增发布/运行准入，已执行任务按既有控制与恢复流程结束；不修改历史结果。

## 9. 验收与完成定义

| 编号 | Given / When / Then |
| --- | --- |
| FE-04-A01 | 给定带 Team 配置草稿，打开并保存基础字段，则预算、角色、固定版本完整回读且不丢失 |
| FE-04-A02 | 给定默认仅 LEGACY_STABLE，选择关闭 profile 并发布，则 UI 提示且后端 PROFILE_DISABLED，未产生版本 |
| FE-04-A03 | 给定合法 SINGLE_SKILLED 的受控测试环境，零工具、12000 字符指令校验通过；12001 字符拒绝 |
| FE-04-A04 | 给定重复员工、跨租户/停用成员、自身或错误版本，校验定位准确 fieldPath，发布失败 |
| FE-04-A05 | 给定两管理员同一 draftRevision，先后保存，则后一请求冲突且本地编辑仍可查看 |
| FE-04-A06 | 给定发布响应丢失，同 requestId 重试，则只新增一个版本且返回原发布结果 |
| FE-04-A07 | 给定 v1 Session，恢复 v1 内容并发布 v3，则旧 Session 仍 v1，新 Session 使用 v3，v2 不被改写 |
| FE-04-A08 | 给定旧版本能力/成员已停用，恢复草稿后发布，则重新校验失败且当前发布版本不变 |
| FE-04-A09 | 给定并发创建不同员工与重复请求，创建完成则 ID 唯一、重复请求仅一个员工、无残留半草稿 |
| FE-04-A10 | 给定未发布或停用员工，管理员可查看；普通用户列表不可选，未经门槛校验不能启用 |
| FE-04-A11 | 给定两次员工列表翻页及同时插入新员工，则已有页边界稳定；错用过滤 cursor 被拒绝 |
| FE-04-A12 | 给定普通用户或跨员工 versionId，请求管理接口/详情，则分别 403/404，正文不泄露 |
| FE-04-A13 | 给定已验收TEAM_READONLY环境，固定成员+保留工厂目标可发布运行；未知目标/保留角色冲突/成员继续委派拒绝且原生工厂不重复注册 |
| FE-04-A14 | 给定steps=8和通用/动态专家，实际步数受冻结4步/迭代上限约束，继承父身份/连接且无写工具/继续委派，sourceKind、预算和旧Session hash边界正确 |

单元层覆盖配置 DTO、比较规则、发布键生命周期和页面冲突；HTTP 层覆盖权限、字段和兼容调用。
MySQL 集成覆盖创建/发布/恢复并发、事务回滚、ID 初始化与 Session 冻结；H2 不替代这些验收。
浏览器层覆盖编辑、定位错误、关闭 profile、版本恢复和响应丢失重试；记录环境和截图/结果。
DoD：全部验收有实际证据、迁移兼容通过、npm 构建通过、定向 Java 测试通过、文档与开关一致。
未具备某新 profile 的生产依赖时，该 profile 保持关闭；FE-04 可完成配置管理而不宣称运行方式已开放。

## 10. 本轮实施与验证记录

实施基于当前工作树校准：保留既有草稿/发布链路和旧接口，在其上新增管理员生命周期与不可变版本查询闭环。

- [x] 后端 runtime-options 共用 profile 设置；管理员分页、创建、改名、启停、版本详情/列表、历史复制为草稿 API。
- [x] V35 新增 created_at 回填、稳定员工列表索引、锁行 ID allocator 和管理员命令回执；没有修改历史迁移。
- [x] 启用写入在事务内按 employee ID 升序锁员工及固定成员，再按 capabilityCode 升序锁能力；基于锁定的版本、Profile、运行包、能力和成员状态复核。
- [x] 版本恢复复用现有草稿保存与发布校验；requestId 同时写入命令回执和管理审计。
- [x] 管理页支持未发布/停用员工、运行配置编辑、精确成员版本、字段定位、独立启停、版本比较与历史复制；成员版本读取失败可重试，超过首批 2000 项继续分页，缺失历史选项不会清除冻结引用。
- [x] 保留通用/动态只读委派目标的配置与校验；没有开启未验收 Profile。
- [x] FE-04 platform、API、infrastructure 定向测试和 Maven reactor compile：19 项测试全部通过；执行 `-DforkCount=0` 以规避 Windows Surefire fork attach 启动挂起。
- [x] 当前前端修改后的最终 `npm --prefix haizhuo-brain-web run build`：exit 0，`vue-tsc --noEmit` 通过、Vite 构建成功（1757 modules transformed）；仅有 router mixed import 与主 JS chunk 超过 500 KB 的警告。
- [x] 前端单元测试 `npm --prefix haizhuo-brain-web run test:unit`：21 passed / 0 failed。
- [ ] MySQL 迁移/并发集成、浏览器纵向场景和真实模型提供方：本轮尚未执行；H2/编译结果不能代替这些证据。
- [x] 静态空白检查：`git diff --check` exit 0；FE-04 新增文件尾随空白搜索无命中（工作树仅有 CRLF 转换提示）。

### 验收编号与当前证据

| 验收编号 | 实施/本轮证据状态 |
| --- | --- |
| FE-04-A01 | 配置编辑/精确成员版本代码已接入；platform 定向测试通过，浏览器往返未测。 |
| FE-04-A02 | 既有 `disabledRuntimeProfileIsNotPublishableAndHasDeterministicPreviewHash` 通过；未开放 Profile 仍保持关闭。 |
| FE-04-A03 | UI/服务端指令上限已对齐；本轮没有执行 12000/12001 字符边界测试；关闭 Profile 不开放运行。 |
| FE-04-A04 | 既有跨租户成员 fieldPath、精确版本校验测试通过；完整 HTTP/跨租户安全验收未做。 |
| FE-04-A05 | 新增 row_version 冲突 H2 测试通过；草稿 revision 锁依赖既有保存实现。 |
| FE-04-A06 | 发布键前端保持逻辑已实现；发布幂等/响应丢失浏览器场景未执行。 |
| FE-04-A07 | 恢复复制到草稿及 requestId 审计测试通过；Session 冻结需数据库/运行链路验证。 |
| FE-04-A08 | 恢复后复用原校验/发布入口；停用依赖再发布和历史指针 MySQL 场景未执行。 |
| FE-04-A09 | 创建、allocator 推进、回执 replay/conflict、事务失败回滚 H2 测试通过；真正并发创建和迁移升级待 MySQL 验证。 |
| FE-04-A10 | 新增服务 gate 与事务内 Profile/能力门槛 H2 测试通过；未跑实时生产会话可用性验证。 |
| FE-04-A11 | H2 keyset 插入边界与过滤游标拒绝测试通过；MySQL 排序计划未测。 |
| FE-04-A12 | Controller 可信操作者、员工/版本路径归属与缺失版本 404 单元测试通过；真实 HTTP 403/CSRF 未测。 |
| FE-04-A13 | 保留目标 allow/reject platform 测试通过；Profile 未开启，端到端需 FE-12/生产接入前提。 |
| FE-04-A14 | 页面步数提示代码已实现；身份、预算、sourceKind/hash 和旧 Session 运行边界未端到端验证。 |

本轮静态源码检查确认 `JdbcEmployeeAdministrationStore` 与 `loadVersions` 属于新增/未被当时 GitNexus 索引收录的符号，GitNexus 返回 UNKNOWN；以限定源文件的调用点/构造点检索补证。共享 `AgentDefinitionManagementService` 的类级 impact 为 CRITICAL，改动限于保留旧签名的 `saveDraft` 委派重载和配置校验，不改变旧草稿保存/发布接口；新增保留目标测试覆盖允许的 coordinator→工厂目标及未知目标、固定保留角色和其他来源拒绝。`AgentCapabilityConfiguration` 的 bean 装配 impact 为 UNKNOWN，源码确认该 bean 装配入口只有此处。定向 Maven 测试、Vue 类型检查与生产构建、前端单测均已在本轮通过；MySQL 迁移执行、HTTP 安全集成、浏览器纵向场景及真实模型提供方仍未验证。
