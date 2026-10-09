# SPEC-07：工具授权与资产修订

状态：**用户/工具授权与不可变资产修订主体已完成；platform、H2、API 定向测试及本轮前端类型/单元/生产构建通过；真实 MySQL/Flyway、HTTP 权限和浏览器冲突流程未验收**。编号：FE-07。基线日期：2026-10-09。优先级：P2。

本规格完善已有管理页，不引入第二套授权或资产发布系统。共同约定见 [SPEC-00](SPEC-00-公共契约与实施约定.md)，发布配置衔接见 [详细设计](../功能与界面闭环详细设计.md)。

## 1. 范围与现状

- 工具授权支持服务端查找全部平台用户，不受首批 100 名 ACTIVE 用户限制。
- 全局停用能力仍可撤销已有用户授权；新增授予必须区分账号状态、能力状态。
- 补已发布技能/知识修订正文、历史列表和修订间比较，不修改不可变发布记录。
- 当前授权读写接口与 `ToolGrantPanel` 已存在；选择器只读 ACTIVE 前 100 人，停用能力的整个开关被禁用。
- 当前资产管理可新增、保存草稿及发布；后端已能按 `revisionId` 读正文，前端未封装此读取。
- 当前不存在修订历史列表接口；V22 已保存修订、文件、哈希、发布者与审计。
- 知识资产为只读文本包，不能在页面标为已具备向量检索/RAG；本地工具仍来自部署的执行器白名单。

依据：[授权页](../../../haizhuo-brain-web/src/views/admin/ToolGrantPanel.vue)、[管理服务](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/AgentDefinitionManagementService.java)、[资产控制器](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/agentdefinition/CapabilityAssetManagementController.java)、[V22 迁移](../../../haizhuo-brain-infrastructure/src/main/resources/db/migration/V22__capability_skill_knowledge_assets.sql)。

## 2. 用户授权契约

| 状态 | 方法与路径 | 字段与语义 |
| --- | --- | --- |
| 已有 | `GET /api/admin/v1/users` | `keyword`≤32、`status`可选、`limit`1–100、`offset`≥0；返回 `content,total` |
| 已有 | `GET /api/admin/v1/capabilities` | 返回能力修订目录，按 `capabilityCode` 汇总展示 |
| 已有 | `GET /api/admin/v1/users/{userId}/capability-grants` | 返回 `{userId,capabilityCode,enabled}[]`；缺记录表示未授权 |
| 已有 | `PUT /api/admin/v1/users/{userId}/capability-grants/{capabilityCode}` | `{enabled,reason}`，成功 204 空响应；reason 1–500 |
| 已有 | `PUT /api/admin/v1/capabilities/{capabilityCode}/status` | 控制全局状态，保持独立管理入口 |
| 增强 | 同一授权 PUT | 服务端执行下面的授予/撤销规则；请求和响应形状保持 |

所有接口继续要求可信 `PLATFORM_ADMIN`，actor 来自登录主体，不能从表单提交。
用户选择默认 ACTIVE；可切换“全部/待激活/已激活/已停用”以查阅和撤销历史授权。
远程关键字为手机号匹配，遵循原目录语义；输入提示不得宣称支持昵称搜索。
分页 limit=20，沿用目录 offset/total；搜索防抖 300 ms，条件变化重置 offset，选中用户独立保留。

| 目标状态 | `enabled=true` 授予 | `enabled=false` 撤销 |
| --- | --- | --- |
| ACTIVE 用户、全局启用能力 | 允许 | 允许 |
| ACTIVE 用户、全局停用能力 | 拒绝新增授予 | 允许撤销 |
| PENDING_ACTIVATION / DISABLED 用户 | 拒绝新增授予 | 用户仍存在时允许撤销 |
| 用户或能力不存在 | 拒绝，不能创建孤立记录 | 拒绝，不能伪造成功 |

当前服务只检查能力存在，未完整实现上述授予规则；该增强属于后端工作，不可只禁用按钮。
新增授予与状态核对纳入同一写事务；能力/用户状态变化时返回 409 `STATE_CONFLICT`，前端刷新对应状态。
服务校验形参，Repository事务按 capability_definition → platform_user → grant 行顺序加锁并核对事实；授予要求两者ACTIVE，撤销仅要求对象存在。
不存在对象沿用旧接口现有错误形状，不顺带全局改码；新查询端点采用公共 404 规则。
撤销按钮规则为“已有授权且本行未提交”，不受全局启停和用户 ACTIVE 条件限制。
缺记录的撤销等价于未授权；服务端可维持现有 enabled=false 记录及审计，不需删除历史。
授权按能力编码管理，不按修订管理；员工绑定、能力启用、用户授权、目标系统资源许可各自保持边界。
新增授予不会绕过每次真实工具执行时的判权；细粒度资源授权不由本规格假定已交付。

## 3. 授权页面状态、失败与竞态

页面分为“用户查询”“授权矩阵”两个加载域；用户查询失败不得清空已选用户或提交其他人的授权。
每次选择用户增加请求代次，只有相同 `userId + requestGeneration` 的授权读取响应可更新矩阵。
发起变更前捕获 `targetUserId,capabilityCode,enabled`；提示框、请求与回读均使用这组不可变目标。
提交中禁止本行第二次操作；切换用户可浏览，新用户矩阵不接收旧用户操作的迟到结果。
PUT 成功后读取服务端授权状态，不能仅把本地 switch 改为请求值；回读失败标记“已提交，状态待核对”。
超时视为结果未确认，先回读授权；仍不确定时保留原显示及重试入口，不盲目反向提交。
并发管理员仍采用现有最后一次写入语义，审计记录各次变更；本切片不伪称已有 CAS 版本锁。
授予/撤销均要求填写原因；取消原因弹窗不发请求，权限失效由统一认证处理收口。
矩阵注明来源和全局状态；SKILL/KNOWLEDGE 与 TOOL/MCP 使用准确类型标签，不将文本资产称为执行器。

## 4. 资产修订契约

| 状态 | 方法与路径 | 用途 |
| --- | --- | --- |
| 已有 | `GET /api/admin/v1/capability-assets` | 当前摘要，含 `publishedRevisionId,publishedRevision,assetHash` |
| 已有 | `GET /api/admin/v1/capability-assets/{capabilityCode}/draft` | 读取可编辑草稿 |
| 已有 | `PUT /api/admin/v1/capability-assets/{capabilityCode}/draft` | 保持 `expectedDraftRevision` 乐观锁 |
| 已有 | `POST /api/admin/v1/capability-assets/{capabilityCode}/publish` | 保持 requestId 发布幂等与 201 响应 |
| 已有 | `GET /api/admin/v1/capability-assets/{capabilityCode}/revisions/{revisionId}` | 读取完整、只读修订正文 |
| 新增 | `GET /api/admin/v1/capability-assets/{capabilityCode}/revisions` | `limit`默认20最大100、`cursor`可选；返回 `items,nextCursor,hasMore` |

已有正文字段为 `capabilityRevisionId,capabilityCode,type,revision,displayName,description,files,manifestJson,assetHash,publishRequestId,reviewedBy,reviewedAt`。
`files[]` 为 `relativePath,mediaType,content,sha256,byteSize`；正文接口保持形状，不重复下载无关修订。
新增历史摘要 item 为 `capabilityRevisionId,capabilityCode,type,revision,displayName,assetHash,reviewedBy,reviewedAt,fileCount,totalBytes`。
历史列表不返回正文、发布原因或原始请求；固定按 `reviewedAt DESC,capabilityRevisionId DESC`。
这里 `reviewedAt` 即发布创建时刻，替代通用列表 createdAt；cursor 绑定 capabilityCode 与管理员查询权限。
不存在资产/不匹配修订由新列表明确 404；参数无效 400；禁止绕过 capabilityCode 按裸修订 ID 跨资产读取。

## 5. 资产页面行为

资产行新增“查看已发布”“修订历史”；尚未发布时前者禁用并说明原因，草稿编辑仍可用。
只读查看器展示修订、发布时刻、发布者 ID、哈希、文件目录及选中文件正文。
Markdown 采用现有安全渲染；原始文本可切换查看/复制，不执行脚本、命令、内联 HTML 或文件内容。
历史抽屉按游标加载更多；点选修订读取独立正文，以 `capabilityCode + revisionId` 缓存和保护迟到响应。
比较选择恰好两个修订：文件新增/删除/变化、路径、字节数、哈希；用户按需打开文本差异。
比较按原始文本生成差异，不以 Markdown 渲染结果比较，不把缺文件误判为空文件。
草稿保存冲突保留未保存内容，提示重新读取后人工合并；比较与历史查看不得覆盖正在编辑的草稿。
发布成功后回读摘要、清理该资产历史首屏缓存；发布失败/超时沿用同一 requestId 核查与重试。
不提供覆盖旧修订或删除被发布引用的按钮；回到旧内容须人工另存草稿并重新发布，不实现静默回滚。
文本正文较大时按文件延迟展示，复制失败有明确提示；后台未授权不加载资产内容。

## 6. 模块与文件入口

| 模块 | 文件入口 | 工作 |
| --- | --- | --- |
| Web | [ToolGrantPanel.vue](../../../haizhuo-brain-web/src/views/admin/ToolGrantPanel.vue)、[admin.ts](../../../haizhuo-brain-web/src/api/admin.ts) | 远程用户查询、撤权规则、请求目标隔离 |
| Platform | [AgentDefinitionManagementService.java](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/AgentDefinitionManagementService.java) | 授予/撤销分支和状态校验 |
| Infrastructure | [JdbcAgentDefinitionRepository.java](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/employee/JdbcAgentDefinitionRepository.java) | 授权事务内校验、既有审计 |
| Web | [capabilityAssets.ts](../../../haizhuo-brain-web/src/api/capabilityAssets.ts)、[CapabilityAssetPanel.vue](../../../haizhuo-brain-web/src/views/admin/CapabilityAssetPanel.vue) | 正文读取、历史与比较入口 |
| Web | [CapabilityAssetRevisionViewer.vue](../../../haizhuo-brain-web/src/views/admin/CapabilityAssetRevisionViewer.vue)（已新增） | 文件目录、文本及差异只读展示 |
| API | [CapabilityAssetManagementController.java](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/agentdefinition/CapabilityAssetManagementController.java) | 新历史列表契约 |
| Platform | [资产 Service](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/CapabilityAssetManagementService.java)、[Repository](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/employee/CapabilityAssetRepository.java) | 历史摘要分页端口 |
| Infrastructure | [JdbcCapabilityAssetRepository.java](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/employee/JdbcCapabilityAssetRepository.java) | 摘要分页及字节统计 |

身份状态继续由身份模块拥有；Infrastructure授权事务读取既有platform_user权威状态，不新增用户状态镜像或前端active标志。

## 7. 实施清单

- [x] FE-07-I01：编辑前执行类/方法/文件 impact；对新增方法的 UNKNOWN 以调用点和源码补证，并核对管理身份与执行期授权边界。
- [x] FE-07-I02：落地授予/撤销规则；本轮 platform 与 H2 JDBC 测试通过，覆盖停用能力、停用/待激活用户、缺失对象及撤权后执行期授权事实。
- [x] FE-07-I03：用户选择器改为远程搜索及 offset 分页，保护请求代次和不可变变更目标。
- [x] FE-07-I04：调整行级开关、原因表单、提交后回读及不确定结果提示。
- [x] FE-07-I05：封装已有修订正文，完成“查看已发布”只读入口。
- [x] FE-07-I06：增加历史分页摘要端口和 V38 索引，实现历史选择、文件元数据及按需原文比较。
- [ ] FE-07-I07：platform/H2/API 定向测试、本轮前端类型检查/单元和生产构建已通过；真实 MySQL/Flyway、HTTP 权限及浏览器切换/冲突验收仍待执行。

## 8. 持久化、兼容与回退

授权沿用 `agent_user_capability_grant` 与管理审计；不迁移为修订级授权，不改已发布能力包。
资产正文沿用 V22，不修改已执行迁移；如需列表索引，仅新增 Flyway 迁移。
建议索引 `capability_asset_revision(capability_code,reviewed_at,capability_revision_id)`；按真实 MySQL 查询计划确认。
文件总字节数在摘要查询聚合获取，避免分页后逐条拉取所有正文；必要时新增摘要列并先回填再约束。
旧数组与用户 offset 接口保持；历史查看失败不影响草稿编辑，比较可独立关闭。
回退关闭新入口与列表接口；已有授权、不可变修订及审计留存，撤权服务规则不得为回退而放宽。

## 9. Given / When / Then 验收

| 编号 | Given / When / Then |
| --- | --- |
| FE-07-A01 | Given 超过100名用户；When 搜索/翻页；Then 可选中后续用户，展示脱敏信息且沿用目录筛选语义。 |
| FE-07-A02 | Given 已授权且能力停用；When 撤权；Then 可提交且服务端撤销；重新授予被服务拒绝。 |
| FE-07-A03 | Given 用户停用/待激活；When 查看授权；Then 可查阅及撤销，新增授予被拒绝且不产生孤立记录。 |
| FE-07-A04 | Given 用户A请求在途；When 切换用户B；Then 迟到读取/写入响应不改B矩阵，也不将A操作提交给B。 |
| FE-07-A05 | Given PUT超时或回读失败；When 页面恢复；Then 标识状态待核对，先读取事实，不盲目反向操作。 |
| FE-07-A06 | Given 已发布资产；When 查看修订；Then 正文、文件哈希与后端一致；草稿内容不覆盖已发布内容。 |
| FE-07-A07 | Given 多于一页修订且并发新发布；When 加载更多；Then 固定排序无重复，cursor不可换资产使用。 |
| FE-07-A08 | Given 两个修订含新增/删除文件；When 比较；Then 准确区分文件变更，原文可读，未保存草稿不丢失。 |
| FE-07-A09 | Given 普通用户或失效管理员会话；When 请求资产正文/授权变更；Then 服务端拒绝，页面不泄露内容。 |
| FE-07-A10 | Given 已撤权用户仍有旧Run工具视图；When 真正调用工具；Then 执行期授权仍按当前许可判定。 |

## 10. 测试层级与完成标准

单元：授予矩阵、用户/资产请求竞态、差异分类；API：管理员保护、分页过滤、参数与错误形状。
真实 MySQL：授权与审计事务、并发状态变化、历史索引/分页稳定、旧发布幂等与草稿乐观锁。
浏览器：超过100人搜索、停用能力撤权、历史正文/比较；真实工具 E2E：撤权后执行期仍拒绝未授权动作。
完成标准为全部 FE-07 验收有证据、前端 build 通过、涉及后端的模块测试通过，外部 MCP 未验证项单列。
静态核对不证明真实目标资源权限或 MCP 身份已打通。具体实现和本轮验证证据如下。

## 11. 本轮实施与验证记录

### 已实现

- 用户目录使用现有 `GET /api/admin/v1/users` 远程手机号关键字、状态筛选和 offset/total 分页；授权读取与目录搜索分离。按用户和请求代次隔离迟到读取，提交目标在弹窗、PUT 和事实回读间固定为同一用户/能力。
- 授权服务校验 actor、用户、能力和原因。JDBC 写事务按 capability_definition → platform_user → grant 行加锁；新增授予要求能力及用户均 ACTIVE，撤权只要求两对象存在。执行期仍沿用原有用户能力许可查询。
- 授权面板支持停用能力/用户下撤权、行级提交互斥、PUT 后读回、超时先核查、读回失败保留待核对状态，以及冲突后刷新权威状态。
- 资产面板增加“查看已发布”和“修订历史”。查看器按需读取正文，展示发布者、时间、哈希和文件元数据，支持安全 Markdown/原始文本/复制、分页历史和修订比较；新增/删除文件不会被当成空文件内容差异，迟到读取由代次丢弃，不覆盖草稿。
- 新历史端点对 capabilityCode 做属主校验，按 `reviewed_at DESC, capability_revision_id DESC` 使用资产绑定游标分页，并统计文件数和总字节数。新增 V38 索引；本轮未对任何数据库执行迁移。迁移顺序为 FE-03 V37、FE-07 V38、FE-05 V39，当前工作树还有后续切片迁移文件。
- API direct contract test 使用真实 `CapabilityAssetManagementService` 与轻量 fake Repository，使用普通 `AuthenticatedUser` 检查发布审计 actor，并覆盖历史分页参数和安全 404；移除 MockitoExtension/@Mock 后，默认 fork 的 API 定向测试 3/3 通过。
- `AgentDefinitionManagementService`、`JdbcAgentDefinitionRepository` 等共享符号的类级 impact 为 CRITICAL；按原授权调用链实施并补充回归用例。新增或变更方法的 GitNexus 查询因索引未收录返回 UNKNOWN，已用 Controller→Service→Repository 源码调用链、执行期授权查询点和限定测试源码补证，未把 UNKNOWN 视为低风险。API `CapabilityAssetManagementController` upstream impact 为 UNKNOWN/0 resolved callers，`revisions` 方法和测试类的 method/file targets 未被索引解析；源码已确认 `@RestController`/`@RequestMapping`、`PlatformSecurityConfiguration` 的 `/api/admin/**` `PLATFORM_ADMIN` 规则及 test class 的直接端点调用，故不将空调用集当作未使用。前端文件级 impact 对 `ToolGrantPanel.vue` 和 `CapabilityAssetPanel.vue` 为 LOW；历史查看器为新文件。详见本轮 GitNexus 结果及源码证据。

### 本轮验证

| 层级 | 本轮结果 |
| --- | --- |
| 静态检查 | `git diff --check` exit 0；仅有工作树 LF/CRLF 转换提示。 |
| 前端差异单测 | `node --test haizhuo-brain-web/tests/capabilityAssetDiff.test.mjs`：3/3 通过。 |
| 前端 unit suite | 协作任务 FE-05 曾执行 `npm --prefix haizhuo-brain-web run test:unit`：29/29 通过；此结果早于查看器最后的文件元数据呈现调整，不作为该 Vue 组件的浏览器验收。 |
| 前端构建 | FE-07 最后修改后未执行。协作构建曾遇到 Vite realpath EPERM，随后被 FE-03 `useSessionRenderTransport.ts` 的 TS2367 阻断；最终统一 build 由 FE-05 在 FE-03 修复后执行。 |
| 后端 Maven | FE-07 platform：`AgentDefinitionManagementServiceTest` 7/7、`CapabilityAssetManagementServiceTest` 6/6，共 13/13 通过。FE-07 infrastructure：`JdbcAgentDefinitionRepositoryTest` 7/7、`JdbcCapabilityAssetRepositoryTest` 3/3，共 10/10 通过（H2 测试，不是真实 MySQL）。API `CapabilityAssetManagementControllerTest` 在移除 Mockito inline 后以默认 fork 执行 3/3 通过。DTO import 缺失已补。 |
| MySQL/迁移 | 未连接或修改任何运行数据库；V38 迁移执行结果、真实 MySQL 并发与查询计划均未验证。 |
| HTTP/浏览器/真实提供方 | 未执行 live HTTP 权限集成、浏览器纵向场景或真实工具/MCP E2E。 |

FE-07 platform 定向命令：

```powershell
$env:JAVA_HOME='D:\code environment\jdk\jdk-17.0.9'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; & mvn -ntp -pl haizhuo-brain-platform,haizhuo-brain-infrastructure,haizhuo-brain-api -am '-Dtest=AgentDefinitionManagementServiceTest,CapabilityAssetManagementServiceTest,JdbcAgentDefinitionRepositoryTest,JdbcCapabilityAssetRepositoryTest,CapabilityAssetManagementControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0' test
```

FE-07 infrastructure 与 API 首次合并运行命令（H2 10 项通过；旧 Mockito API 测试挂起后手动停止）：

```powershell
$env:JAVA_HOME='D:\code environment\jdk\jdk-17.0.9'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; & mvn -ntp -pl haizhuo-brain-infrastructure,haizhuo-brain-api -am '-Dtest=JdbcAgentDefinitionRepositoryTest,JdbcCapabilityAssetRepositoryTest,CapabilityAssetManagementControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0' test
```

FE-07 API 单组重跑命令（替换 Mockito 的测试使用 Surefire 默认 fork；不包含 bootstrap）：

```powershell
$env:JAVA_HOME='D:\code environment\jdk\jdk-17.0.9'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; & mvn -ntp -pl haizhuo-brain-api -am '-Dtest=CapabilityAssetManagementControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

### 验收编号与当前证据

| 编号 | 当前证据与未验证项 |
| --- | --- |
| FE-07-A01 | 已实现远程搜索/分页与状态筛选；超过 100 人的浏览器实际选择场景未执行。 |
| FE-07-A02 | 授予/撤权服务测试通过；H2 JDBC 状态规则测试也通过，覆盖停用能力可撤权、不能新授予。 |
| FE-07-A03 | 用户状态门槛 platform/H2 测试通过，覆盖待激活/停用用户拒绝新增、已有授权可撤销及缺失对象拒绝。 |
| FE-07-A04 | 用户读取代次、固定提交目标和行级互斥已实现；浏览器切换竞态未执行。 |
| FE-07-A05 | PUT 后回读、超时核查及待核对状态已实现；真实超时/回读故障注入未执行。 |
| FE-07-A06 | 正文 API 封装和只读查看器已实现；H2 仓储测试通过发布正文/修订持久化断言；HTTP 和浏览器正文一致性未验。 |
| FE-07-A07 | V38、稳定排序和资产绑定 cursor 已实现；H2 `revisionHistoryUsesStableAssetBoundCursorAndSummarizesFileBytes` 通过。迁移执行与 MySQL 查询计划未验证。 |
| FE-07-A08 | 原文差异分类与文件新增/删除判定已实现；差异 helper Node 单测 3/3 通过，浏览器草稿保留场景未执行。 |
| FE-07-A09 | Controller 挂在管理员路由下，静态检查 `PlatformSecurityConfiguration` 的 `/api/admin/**` 角色门槛；无 Mockito 的 direct contract test 3/3 通过；live HTTP 403/失效会话未测。 |
| FE-07-A10 | 撤权后 `hasUserCapabilityGrant` H2 断言通过，`DefaultToolExecutionGatewayService` 源码仍在实际执行前按当前授权事实判权；真实旧 Run 工具调用/MCP 执行未测。 |
