# SPEC-11 交互内容块与成果物

ID：FE-11。日期：2026-10-09。状态：**B-MD 受控 Markdown 成果物及有界孤立 blob 清理已编码并接入历史运行详情；本轮 platform/H2/BlobStore/API 定向测试通过，符号链接安全用例因环境限制跳过；真实工作区生产者尚缺，A 内容块未实施，C 泛化等待保持关闭**。优先级：P2。
前置：[FE-00](SPEC-00-公共契约与实施约定.md)、[FE-02](SPEC-02-会话事件与完整结果.md)；实时块接入依赖 [FE-03](SPEC-03-实时消息恢复与渲染.md)。覆盖 F40，三个切片独立交付。

## 1. 当前事实与范围

[contentBlockRegistry](../../../haizhuo-brain-web/src/renderers/contentBlockRegistry.ts) 已有 text/image/audio/video/document/tool/artifact/interaction 类型和注册查询；[runStream](../../../haizhuo-brain-web/src/api/runStream.ts) 有 interaction/contentBlocks 可选 metadata。
这些仍是类型/扩展骨架，尚无通用等待点、白名单块业务生产和完整 renderer 闭环。B-MD 已补受控 Markdown 导出、私有目录存储、成果物索引、属主校验下载和独立页面组件；没有接入工作区/模型路径，也没有接入任意文件输出工具。
现有 Markdown 图片/代码/表格渲染继续保留，既有工具批准已经有独立业务状态和决定端点。

本轮只交付 B-MD 子集：用户用 requestId 请求导出时，服务端再次验证 Run 属主、SUCCEEDED、持久 ROOT_FINAL、USER 可见性、非 legacySummary、正文长度与 SHA-256；仅将正式正文转换成 `text/markdown` 附件。普通用户请求不接受存储路径、文件名、MIME、原始字节或生产开关。输出不在浏览器内联渲染。它是平台基于真实正式结果的导出生产者，不等于 AgentScope 工作区/工具文件生产者或真实提供方 E2E。

| 切片 | 目标 | 必须同时完成 |
| --- | --- | --- |
| A 安全内容块 | 同一消息按稳定块显示文本、只读状态和已授权媒体引用 | 后端白名单 DTO + registry实际组件 + fallback |
| B 成果物 | 可信运行输出可查看/下载，不向用户暴露 workspace 路径 | 输出端口 + 私有存储/索引 + 每次授权 + UI |
| C 用户选择 | 多选/单选决定后恢复同一受控等待点 | 原生探针 + 等待/决定事务 + 唯一恢复 + UI |

B 可先以受保护下载卡交付，不等待 C；C 未通过契约时返回 FEATURE_NOT_AVAILABLE，不显示假的“继续”按钮。
本专题不默认开放任意文件浏览、远程 URL 抓取、HTML 执行或给模型添加可写工具。

## 2. 内容块契约

服务端拟新增 PublicContentBlockMapper，消费者只收到白名单字段：

| 类型 | 字段与处理 |
| --- | --- |
| text | blockId、type、text、format=plain/markdown；沿用净化 |
| image/audio/video/document | blockId、type、artifactId、title、mediaType、byteSize；不传磁盘路径/凭据 URL |
| artifact | artifactId、title、mediaType、byteSize、availability |
| tool | executionId、displayName、state、safeSummary；详情通过既有受保护查询 |
| interaction | interactionId、interactionType、title、message、options、state、expectedVersion、expiresAt |
| 不认识类型 | 安全文案“暂不支持此内容”；允许受控标题，拒绝输出原始 data/HTML |

id 在消息内稳定；所有块增加revision正整数，text增量走FE-03 blockId，非文本块只接受较新revision全量替换，不对任意JSON做客户端merge。
contentBlocks 是展示 DTO，不是传入工具的新参数或能力授权。后端必须先核对 Run 来源/可见性再映射；child/private 内容不能通过另一个块通道泄露。
运行正式答案仍为 ROOT_FINAL 正文；结构化块/制品附于独立公开输出 manifest，不更改旧 text/markdown body 语义。
拟新增 manifest 查询 GET `/api/v1/sessions/runs/{runId}/content`，返回 schemaVersion=1、runId、version、blocks、artifactRefs；属主先验证，空态表示确实无公开输出。
既有 v2 无 manifest 字段时继续纯文本；v3 收到公开内容失效通知后读取 manifest，不信任客户端提供的 artifactId 授权。
拟新增持久USER通知RUN_CONTENT_UPDATED，payload仅含runId、manifestVersion、schemaVersion=1；manifest变化与通知同事务。通知不携带制品路径/正文。重复通知合并一次查询，旧version不覆盖新manifest。

## 3. 成果物索引、存储与读取

通用目标接口如下；B-MD 已实现其中 Markdown 受控子集，并增加显式导出请求：
- POST `/api/v1/sessions/runs/{runId}/artifacts/markdown`：从当前 owner 的 SUCCEEDED ROOT_FINAL 生成 Markdown，requestId 幂等。
- GET `/api/v1/sessions/runs/{runId}/artifacts`：items/nextCursor/hasMore，createdAt+artifactId 排序。
- GET `/api/v1/artifacts/{artifactId}/content`：每次验证用户/Run/visibility，Markdown 附件支持单段合法 Range/206，错误 range 返回416。
- 删除/公开私有专家文件不属于本切片；普通页面没有任意路径下载参数。

通用目标 `platform_run_artifact` 字段：artifact_id、run_id、result_id(nullable)、owner_user_id、visibility、title、media_type、byte_size、sha256、blob_ref、state、created_at。V42 B-MD 表把 result_id 设为 NOT NULL，并强制关联同 Run 的 USER ROOT_FINAL；后续如需支持独立受控输出再通过新迁移扩展。
blob_ref 仅服务端可见；普通 DTO 不含内部目录、存储桶密钥、native session。state=STAGED/AVAILABLE/UNAVAILABLE；只有 AVAILABLE 可以下载。
公开仅限通过平台校验的 ROOT 输出，或已由独立明确发布契约允许的输出；PRIVATE子结果/工作区文件不能因扫描得到就自动开放。

拟新增 ArtifactOutputPort，由受信平台工具/适配器提交内容句柄和安全元数据：
1. 使用本 Run 可信身份/允许输出来源验证，不接受模型直接指定任意已有磁盘路径。
2. 写不可变私有 blob，计算 hash/大小；初版以配置私有目录存储，统一 BlobStore 端口允许以后替换对象存储。
3. 数据库事务注册索引与 manifest/公开失效通知；若关联结果，验证同 Run 已提交结果，不影响既有结果完成事务。
4. blob写成功、DB失败留下孤立对象，由有界清理任务按未引用且超过安全期限回收；不假装文件系统与DB同事务。
5. 记录缺失 blob 为 UNAVAILABLE，下载返回安全错误，页面保留卡片“文件暂不可用”。业务 Run 可完成，制品失败单独说明。

首版必须接入至少一个真实、受控业务输出生产者并做端到端验证；仅前端手造卡片/保存 metadata 不算成果物交付。
普通用户上传、病毒扫描流程、任意外部文件导入另立需求，不在本次顺带扩展。

### B-MD 当前实现

- 新增 V42 `platform_run_artifact`，保存 owner/run/result、请求幂等摘要、hash/大小、状态和仅服务端读取的 blob_ref；所有读取按 artifactId + owner 过滤。V42 是新迁移，V1–V41 未改。
- `POST /api/v1/sessions/runs/{runId}/artifacts/markdown` 只接受 requestId。同一 Run/owner/requestId 重放返回原成果物；同键内容摘要冲突返回 409。
- `GET /api/v1/sessions/runs/{runId}/artifacts` 按 createdAt + artifactId 做有界 keyset 分页；`GET /api/v1/artifacts/{artifactId}/content` 每次重验 owner，提供单段合法 Range/206、非法 Range/416、`Content-Disposition: attachment`、`nosniff` 和 `private, no-store`。
- 写入顺序是先提交 STAGED 索引、再以服务端 UUID 写 blob、最后转 AVAILABLE。DB 更新失败会留下可幂等修复的 STAGED 行；只有 AVAILABLE 能下载。发现 blob 缺失/校验不符时转 UNAVAILABLE 并返回 410。V45 已新增 ≥7日、受规范 UUID `.blob` 限制、避开符号链接并按持久引用查询的有界孤立 blob 清理；此实现仍需在真实 MySQL/部署环境验证。
- `haizhuo.brain.artifacts.markdown.enabled` 缺省 false；缺少服务端私有目录配置时同样关闭。目录仅从服务端配置读取，存储键只能是服务端生成的规范 UUID，API/DTO 不返回路径或 blob_ref。
- 新增独立 `RunArtifactPanel.vue`，支持列表、导出同 requestId 重试、附件下载及 object URL 释放；现由 `SessionHistoryView.vue` 的历史运行详情接入，仅对选中的 SUCCEEDED Run 显示。成果物列表和既有附件下载不受“新导出”门控影响；只有同 Run 的完整结果已读且 `legacySummary=false` 时才启用导出。切换 Run 时完整结果先清空，失败、尚未读取或历史摘要均关闭新导出；属主仍由列表/下载 owner API 最终复验。

允许类型初值：纯文本/Markdown、PDF、PNG/JPEG/WebP、经配置允许的常见音视频；单文件建议上限20 MiB，可按部署收紧。
验证文件签名、实际媒体类型与声明一致；拒绝 HTML、可执行文件、含脚本的 SVG，标题作为纯文本。下载使用 nosniff、受控 Content-Disposition；文档预览放受限容器。
初版用同源 Cookie 鉴权二进制流，不使用可永久转发的公开 URL。前端 blob URL 在替换/卸载时 revoke；网络失败允许只读重试。
存储配额失败明确提示，不截断文件后宣称已生成。审计只记引用/类型/大小/状态，不存正文。

## 4. 工具批准与泛化选择的分界

现有 Boolean 工具决定端点继续复用；TOOL_APPROVAL 映射现有持久工具等待事实，不能凭新的 interactionType 扩大权限。
USER_SELECTION 仅代表业务选项决定，例如选择输出格式；不自动批准外部工具、资源或私有结果公开。

C 的实施前置：固定 Java2.0.3 原生用户输入/确认暂停恢复最小探针。记录扩展点、保存的等待状态、恢复输入、取消语义、重复恢复守卫和进程重启行为。
通过后薄适配为 PlatformInteractionDecisionProvider；若锁定版本无法满足可持久恢复，C 保持禁用，提交具体缺口证据和最小补充决策，不能让 UI 自行重跑模型。
不重写框架暂停/推理循环，不从普通文字“同意”抽取批准。

### AgentScope 2.0.3 原生能力核对（C 保持关闭）

- 根 `pom.xml` 锁定 `agentscope-core` 2.0.3。本机对应 sources jar 中 `UserInputBase` 已标记 deprecated（since 2.0），`StreamUserInput` 通过阻塞 stdin `readLine()` 收集输入，不是可由 Web 页面恢复的持久等待契约。
- `RequireUserConfirmEvent` / `ConfirmResult` 针对当前 ASKING 的 ToolUseBlock，决定会将特定工具调用设为 ALLOWED/DENIED；它是工具权限确认，不是通用业务选项，不能拿来实现 USER_SELECTION。
- Java 2.0.3 的外部工具挂起/ToolResultBlock 恢复是现有的框架扩展点；本轮 `AgentScopeRuntimeTest` 10/10 通过，覆盖新 attempt 下继续 AgentScope 外部工具结果恢复；[JDBC 全进程重建验证源码](../../../haizhuo-brain-runtime-agentscope/src/test/java/com/haizhuo/brain/runtime/agentscope/harness/JdbcAgentStateStoreVerificationTest.java)未在本轮运行，不能记为重启恢复验收通过。将 ToolResultBlock 当作用户选择的载体也不等于原生通用用户交互契约。
- [W0 文本块身份探针](../../../haizhuo-brain-runtime-agentscope/src/test/java/com/haizhuo/brain/runtime/agentscope/event/NativeTextBlockIdentityProbeTest.java)只验证两个文本增量共享 replyId/blockId，不覆盖工具等待、多轮恢复或用户选择。
- 结论：本轮没有新增 WAITING_INPUT、决定表、恢复消费或“继续”按钮；C 保持禁用。外部工具恢复的局部原生运行契约已通过 `AgentScopeRuntimeTest`，但 JDBC 重启恢复和泛化用户选择契约仍未验证。

## 5. 泛化等待与决定契约（C，待新增）

| 方法/路径 | 请求/响应 |
| --- | --- |
| GET `/api/v1/sessions/runs/{runId}/interactions` | 安全等待分页：schemaVersion、items、nextCursor、hasMore、asOfSessionCursor；limit默认20/max100，createdAt+interactionId排序 |
| POST `/api/v1/sessions/runs/{runId}/interactions/{interactionId}/decision` | expectedVersion、requestId、selectedOptionIds、comment(optional)；返回持久决定状态/version |

安全 interaction：interactionId、runId、type、title、message、options[{id,label,description?}]、selectionMode、minSelections、maxSelections、state、expectedVersion、expiresAt。
title/message/options 经过受信模板或明确允许输出映射，不包含原始工具参数、思考链或私有子消息；comment 纯文本限长，后端重新验证，不作为授权。
state=PENDING/DECIDED/APPLIED/EXPIRED/CANCELLED；与现有 stream 类型的 SUBMITTED 等旧可选字段通过适配处理，不悄悄重命名既有工具状态。

拟新增 platform_run_interaction 与 platform_run_interaction_decision：
- 等待点：ID、Run、attempt关联、native waitingRef 的受限引用、type、safe schema、version、expiresAt、state。
- 决定：interactionId、ownerUserId、requestId、requestHash、selectedIds、createdAt；唯一 interactionId + 决定，及用户意图幂等键。
- 等待与公开通知在同一事务；决定与唯一恢复意图在同一平台事务。原生状态写入不与平台DB承诺跨存储 exactly-once。
- 通知名RUN_INTERACTION_UPDATED，持久USER，payload仅含runId、interactionId、version、schemaVersion=1；前端按属主查询刷新，不能用通知直接推断已执行。

通过原生探针后，才新增 WAITING_INPUT 和 USER_DECISION_READY：
- WAITING_INPUT 占 Session 槽，不持 Worker执行租约，与现有 WAITING_*一致。
- 消费者领取既有受控恢复队列，校验 Run/等待点/版本/有效期/决定，恢复原生持久等待位置。
- 恢复以同决定重放幂等；过期 claim/fence 和已消费决定拒绝第二次执行。
- 原生恢复成功才标 APPLIED；未知副作用/状态进入 RECOVERY_REQUIRED，不盲目恢复。

完整状态流程：RUNNING→持久等待快照就绪→WAITING_INPUT（interaction=PENDING）→决定原子记录（DECIDED、Run仍占槽）→既有claim边界领取USER_DECISION_READY→新attempt/RUNNING→原生确认消费后APPLIED。取消在任何等待/已决定未领取阶段转CANCELLED；到期按已验证清理转EXPIRED或RECOVERY_REQUIRED，不允许再领决定。
实施必须逐项接线：RunState.active与全部占槽查询、JdbcRunExecutionStore.claimNext/过期回收、JdbcSessionRunStore.cancel的WAITING_INPUT/DECIDED分支、租约释放与等待快照、ExecutionResumeReason映射、RunExecutionService恢复输入分派、后台停止/旧fence拒绝、SessionApplicationService准入、前端ACTIVE_STATES及waiting-input消息phase。不能把USER_DECISION_READY落入INITIAL_PROMPT分支再提交原输入。
泛化决定队列是既有Run恢复意图的一个原因，不建第二个任务队列；不能只给enum加值却遗漏SQL/取消/恢复消费。UI等待阶段无光标，决定已记录只显示等待恢复，新attempt才续写。

POST先验可信属主与interaction归属，再查幂等回执；同requestId同内容直接返回原决定，即使已经DECIDED/APPLIED/后来过期也不再次执行。只有首次决定才检查当前等待Run、PENDING、未过期、expectedVersion及选项范围，并在事务锁内再次检查回执。
同键异内容、首次请求但已被另一决定处理/过期/非等待状态返回409。不要自动换requestId重新批准；跨属主不能借幂等回执读取别人的决定。
若原生端口未就绪返回409 FEATURE_NOT_AVAILABLE；不写已决定记录。401/403依公共规则停止。
过期时停止接收决定，按已验证原生取消/等待清理边界处置；无法证明清理安全进入待核查，不宣称外部动作已停止。
Run取消与决定并发由同Run锁/版本守卫线性化；已取消不得恢复，已经开始外部动作按现有取消/核查规则处理。

## 6. 前端与失败状态

块组件通过 registry 实际注册，父组件使用 messageId/blockId key。未知块/缺字段安全降级，不破坏同消息其他正文。
成果卡支持加载/可用/缺失/无权/过大/下载失败，媒体使用受保护内容端点；失败不标为 Run失败。
选择卡显示待选择→提交中→决定已记录→正在恢复→已应用；POST成功不等于已完成执行。
用户修改选项后再次提交必须是新明确意图，原pending提交不得改hash；服务端已接受则表单锁定并校准持久状态。
切换页面后重读等待事实，不从本地按钮disabled推断已处理；取消、过期卡保留说明，停用选项。
既有工具卡继续归属原服务；generic选择卡不取代原批准组件，也不能绕过 ResourcePolicy。

## 7. 实施入口、迁移与回退

| 模块 | 待新增/修改 |
| --- | --- |
| runtime-api/runtime-agentscope | C原生探针及已证实的等待适配端口；保持原生状态所有者 |
| platform | PublicContentBlockMapper、RunContentQueryService、ArtifactOutputPort、RunInteractionService/Store |
| infrastructure | BlobStore、制品索引/manifest仓库、等待与决定仓库 |
| api | RunContentController、ArtifactController、RunInteractionController |
| web | contentBlockRegistry、MessageBlocks、ArtifactCard、UserSelectionCard，既有工具卡独立 |
| bootstrap | 私有存储路径/配额，generic交互默认关闭，已验证端口装配 |

- [ ] A先定义白名单schema与公开manifest，接真实生产者，再注册renderer。
- [ ] B通用实际制品生产者与内容类型白名单尚未接入；需各自验证权限、配额与失败恢复。
- [x] B-MD 子集：从已持久化完整 ROOT_FINAL 创建 Markdown 附件，受保护索引/下载和独立页面组件已编码。平台 service 4/4、H2 index 2/2、私有 BlobStore 1/1 定向测试通过；API 首次测试发现合法 Range 状态构造缺陷，已修复并增加边界/后缀断言，但最新重跑在 API 测试前被并发 `AuditQueryController.java` 编译错误阻断。18 个 FE-11 文件存在性与尾随空白扫描通过，`git diff --check` 无错误（仅有其他并发改动的 LF/CRLF 提示）；Vue 统一检查见本节末。
- [ ] C先原生探针，结果入报告；通过才推进状态、持久化、恢复消费者和页面。
- [ ] 三个切片分别做属主/秘密标记、失效和浏览器验收，不用静态卡片冒充业务闭环。

B-MD 使用新迁移 V42；后续 A/C 如需迁移，按当时仓库最新编号新增，不改已执行迁移。A/B可关闭页面/输出生产，不删除既有正式结果。
C新等待尚未结束时，禁止回退到不识别 WAITING_INPUT 的旧程序；先停止新等待受理、处置存量，再回退。
历史无manifest显示原纯文本；旧客户端忽略新增通知仍可读取正式答案。

## 8. 验收与 DoD

| ID | Given / When / Then |
| --- | --- |
| FE-11-A01 | 已授权多块消息及未知类型；稳定渲染/安全fallback，其他正文不丢失 |
| FE-11-A02 | CHILD/工具参数/私有路径带秘密标记；manifest、SSE、普通日志无泄漏 |
| FE-11-A03 | 可信生产者输出真实文件；索引、查看、下载、hash/大小一致。B-MD 使用已持久化 ROOT_FINAL 受控生成 Markdown；service 4/4 与 H2 index 2/2 通过，API 下载用例尚待重跑；不是运行时工作区文件生产者 E2E |
| FE-11-A04 | 他人artifactId/私有文件/伪造路径；服务端拒绝，不因可猜ID下载。service 4/4、H2 index owner 过滤 2/2 通过；没有私有子结果 producer |
| FE-11-A05 | blob写成功DB回滚、DB索引存在blob缺失；孤立清理/缺失提示正确，不假装跨存储原子。STAGED→blob→AVAILABLE、缺失转 UNAVAILABLE、V45 有界孤立清理已编码；BlobStore/H2定向测试通过，符号链接测试1项因环境能力跳过；真实MySQL未验证 |
| FE-11-A06 | 不允许MIME/超配额/Range非法；拒绝且安全错误，内容不执行。Markdown 附件和1MiB上限；合法起始、末字节、超界截断、后缀 Range 的 API 控制器测试本轮1/1通过；不涵盖 PDF/媒体签名 |
| FE-11-A07 | 同决定重复及同键异内容；恢复意图唯一，冲突不能重开执行 |
| FE-11-A08 | 等待过期/取消与决定并发；只有一个有效状态转移，已取消不能恢复 |
| FE-11-A09 | 决定已记但Worker停止、旧fence重试；恢复可核查，不双执行，必要时RECOVERY_REQUIRED |
| FE-11-A10 | 原生交互探针未通过或端口缺失；C关闭，现有工具Boolean批准仍可用。本轮 `AgentScopeRuntimeTest` 验证了外部工具等待/ToolResult 恢复，但不构成泛化用户选择契约；C 无服务/页面/恢复入口，Boolean 工具决定未改，仍保持关闭 |
| FE-11-A11 | 刷新、切Session、网络失败；卡片按持久状态恢复，blob URL及时释放。组件含 runId/generation 迟到响应守卫与 object URL 延迟释放；已接入 `SessionHistoryView` 历史成功 Run 详情，并保持既有成果物读取/下载不依赖新导出资格；本次策略单测通过，专门浏览器验收未执行 |
| FE-11-A12 | 旧纯文本/v2客户端与C回退；正式答案可读，存量新等待不被旧程序错误接管。本切片未改旧文本/v2或 Run 等待状态；兼容运行验收未执行 |
| FE-11-A13 | 新WAITING_INPUT占槽、决定后尚未领取时取消/过期、随后claim并发；无双Run占槽/错误INITIAL_PROMPT，旧决定与fence不可恢复 |

验证：映射与状态单元→属主HTTP→MySQL决定/恢复并发→BlobStore失败→原生等待/重启契约→浏览器→受控真实输出。
本轮统一 Maven 复验包含 `RunArtifactServiceTest`（4/4）、`JdbcRunArtifactIndexTest`（2/2）、`JdbcRunArtifactBlobReferenceQueryTest`（3/3）、`PrivateDirectoryRunArtifactBlobStoreTest`（6项中5通过、1项符号链接权限测试跳过）和 `RunArtifactControllerTest`（1/1）。API Range 修复已通过最终测试。V45 迁移尚未在真实 MySQL/Flyway 执行；浏览器、下载权限 HTTP 和工作区/媒体生产者 E2E 未执行。

GitNexus 绑定 `haizhou-brain`，路径为 `D:\code project\working\haizhuo-brain\haizhuo-brain`，索引 commit `eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`；本轮只新增符号/文件，没有改既有代码方法。`impact(findRoot)` 与 `impact(getRun)` 对旧索引返回 UNKNOWN/未找到符号；对应源码已核对：`JdbcAgentResultRepository.findRoot` 按 runId + run.user_id + ROOT_FINAL + USER 查询，`SessionApplicationService.getRun` 调用 owner-scoped `store.findRun`。UNKNOWN 不作为无调用证明，也不宣称本轮修改了这些符号。

历史详情接入验收（2026-10-09）：`npm --prefix .\haizhuo-brain-web run test:unit` 本次 32/32 通过，其中 `runArtifactPolicy.test.mjs` 覆盖非成功 Run、结果未读、Run 身份不匹配、legacy summary 与正式结果门控。`npm --prefix .\haizhuo-brain-web run type-check` 已执行但未通过：唯一诊断为未修改的 `src/components/conversation/RunProgressCard.vue:57` 引用不存在的 `canOpenResult`（现有类型建议 `openResult`）；本次触及的 Vue 文件未出现在诊断中。按切片约束未运行 Vite/build，也未执行浏览器验收。GitNexus 对 `SessionHistoryView` 与未索引的 `RunArtifactPanel.canExport` 返回 UNKNOWN/未找到；源码搜索补证为历史页仅由 `/app/sessions` 路由动态导入，成果物面板此前无调用点，之后只新增此历史详情调用。服务端 Run/Artifact owner API 未修改，仍是最终授权边界；若全局 Markdown artifact feature flag 关闭，当前服务端也会拒绝列表/下载，本轮没有改变该配置行为。

本轮 FE-11 验收包含在统一 Java 选择集内；本轮全前端 `test:unit` 35/35、`type-check` 通过，Vite 在验证进程 EPERM 兼容处理后构建通过（1790 modules）。这不等于真实浏览器验收。HTTP 真服务器、真实 MySQL/Flyway、浏览器、模型提供方和生产 E2E 尚未执行。B-MD 是受控 Markdown 子集，A 内容块和 C 泛化等待未交付，不能据 B-MD 宣称 FE-11 整体完成。
来源：[现有 registry](../../../haizhuo-brain-web/src/renderers/contentBlockRegistry.ts)、[RunState](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunState.java)、[ExecutionResumeReason](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/ExecutionResumeReason.java)、[会话页面](../../../haizhuo-brain-web/src/views/app/SessionView.vue)。
