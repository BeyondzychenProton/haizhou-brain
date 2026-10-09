# SPEC-08：历史会话、结果引用与反馈

状态：**历史分页/详情/引用与 F2 本地反馈事实代码已落地；本轮 Java/H2 定向测试、前端 35 项单测、类型检查与生产构建通过；真实 MySQL/Flyway、HTTP 和浏览器验收未执行**。编号：FE-08。基线日期：2026-10-09。优先级：P2。

全文校准由 [SPEC-02](SPEC-02-会话事件与完整结果.md) 承担，实时消息及状态由 [SPEC-03](SPEC-03-实时消息恢复与渲染.md) 承担；本规格增加历史查询入口、结果选择和反馈操作。公共约定见 [SPEC-00](SPEC-00-公共契约与实施约定.md)。

## 1. 范围与当前事实

- 补全部历史会话、历史 Run 选择、对应工具记录及完整结果查看，保留首页最近会话。
- 单员工会话也可引用历史结果；角色选择与结果选择分别判定是否显示。
- 先接现有数值评分提交；需刷新后可读反馈时，再落地独立本地反馈事实切片。
- 当前 `GET /api/v1/sessions` 默认20、最大100，按最近活动排序，只限量查询，**没有分页**。
- 当前 Run 列表默认20；页面自动选择活动/最近 Run，没有历史选择器。
- 已有完整结果、同会话可引用结果、Run事件与工具记录读取；引用选择框被 `roles.length > 1` 隐藏。
- 引用服务验证同 Session、同属主、USER 可见且完整；最多8项、累计正文不超过512 KiB。
- 已有评分 POST；`accepted=true` 仅表示异步导出队列接受，不表示 Langfuse 已保存；历史反馈读路径缺失。

依据：[SessionController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/SessionController.java)、[会话服务](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/session/SessionApplicationService.java)、[评分入口](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/RunEvaluationController.java)、[当前会话页](../../../haizhuo-brain-web/src/views/app/SessionView.vue)。

## 2. 导航及页面模型

首页“最近会话”保持现有查询与展示，新增“全部会话”进入 `/app/sessions`（拟新增）。
历史会话列表按创建时间排序，提供员工和状态筛选；不要将其排序文案写成“最近活动”。
会话工作台新增“运行记录”抽屉：创建时间、公开状态、执行者、固定版本、结果状态。
查看 Run 时加载对应事件、工具记录和结果；失败/取消 Run 无完整结果时显示相应事实，不合成成功答案。

必须分离以下状态，禁止复用一个 `run` 变量承担全部功能：

| 状态 | 所有者与用途 |
| --- | --- |
| `activeRunId` | 当前会话正在执行/排队/等待/取消/需核查的业务 Run；发送控制、引导、审批均据此判定 |
| `inspectedRunId` | 历史抽屉正在查看的 Run；仅驱动历史详情、工具记录、完整结果与反馈 |
| `nextRunSelection` | 下一条消息的 targetRoleId、referencedResultIds；不得反向修改既有 Run |
| `sessionGeneration` | 切换 Session 的读取代次，隔离迟到响应 |

查看历史 Run 不切换实时连接、不取消当前运行、不向旧 Run 发送引导。
历史工具审批记录只读；尚待审批的当前 Run 仍通过实时工作台入口处理，禁止在旧详情上重复决策。
当前 Run 完成时保留用户正在查看的历史详情；“回到当前运行”由用户显式操作。

## 3. 历史查询契约

| 状态 | 方法与路径 | 响应/限制 |
| --- | --- | --- |
| 已有 | `GET /api/v1/sessions?limit=` | 保持 Session 数组形状与最近活动语义 |
| 已有 | `GET /api/v1/sessions/{sessionId}/runs?limit=` | 保持 Run 数组，供旧前端使用 |
| 已有 | `GET /api/v1/sessions/runs/{runId}` | 单个公开 Run 状态/执行者读取 |
| 已有 | `GET /api/v1/sessions/runs/{runId}/events?after=&limit=&format=v2` | 按 Run sequence 补读，不用于跨 Run 实时续传 |
| 已有 | `GET /api/v1/sessions/runs/{runId}/tool-executions` | 属主工具记录；展示遵循安全字段策略 |
| 新增 | `GET /api/v1/sessions/page` | `limit,cursor,employeeId?,status?`；历史会话分页 |
| 新增 | `GET /api/v1/sessions/{sessionId}/runs/page` | `limit,cursor,state?`；历史 Run 分页 |
| 新增 | `GET /api/v1/sessions/{sessionId}/results/page` | `limit,cursor`；可引用结果元数据分页 |

三种新列表均为 `{items,nextCursor,hasMore}`，limit默认20最大100；cursor不透明并绑定属主、Session及筛选。
历史会话/Run/结果固定 `createdAt DESC,id DESC`，不靠可变 lastActiveAt 游标翻页。
Session item 沿用 `sessionId,employeeId,definitionVersionId,status,createdAt,lastActiveAt`；employeeName为可选展示字段。
Run item 沿用 `runId,sessionId,state,definitionVersionId,createdAt,queuePosition,executorRoleId,executorEmployeeId,executorDefinitionVersionId,mode`。
结果 item 沿用 `resultId,runId,kind,mediaType,bodySha256,byteSize,createdAt,executorRoleId,executorEmployeeId,executorDefinitionVersionId`。
结果列表仅返回可引用的完整 USER 结果，默认不返回正文；源 Run 必须成功，与当前参考资格一致。
所有查询先验证可信属主；不存在或非属主在新端点统一返回404 `RESOURCE_NOT_FOUND`，防止资源枚举。
cursor无效/筛选不符为400；没有更多记录为200空items，不能将查询失败显示为空历史。

## 4. 完整结果与引用操作

| 状态 | 方法与路径 | 行为 |
| --- | --- | --- |
| 已有 | `GET /api/v1/sessions/runs/{runId}/result` | 完整正文、哈希、字节数、legacySummary及执行者 |
| 已有 | `GET /api/v1/sessions/{sessionId}/results` | 最近可引用结果数组，现有服务最多50条 |
| 已有 | `POST /api/v1/sessions/{sessionId}/runs` | 保持 `clientRequestId,input,targetRoleId,mode,referencedResultIds` |

clientRequestId必填≤128；input必填≤4000；targetRoleId≤64；引用ID每项≤64；当前mode只开放DIRECT，不新增协作模式按钮。
完整结果读取、错误、缓存和校准复用 FE-02；历史查看不使用事件摘要充当正文。
`legacySummary=true` 明确展示“历史摘要，非完整正文”，禁止参与结果引用。
“引用已完成结果”独立于角色数量出现；无结果时显示空状态，多个角色时另外显示角色选择。
选择器显示来源时间、执行者、Run及正文大小；最多8项，前端按元数据累计字节提示512 KiB上限。
服务端在提交时重新验证属主、同Session、完整性、可见性和大小，前端验证不构成授权。
引用成功后新 Run 持久保存来源结果 ID/Run/哈希；后续同员工新发布不改变已经冻结的来源。
一次提交中的 clientRequestId 在超时重试时保持，输入、角色或引用改变后才生成新请求ID。
发送成功才清空本次引用；失败保留选择，已不可见项标记并要求用户移除，不静默改写输入。
用户切换 Session 时清空选择；结果元数据分页加载更多不丢已选项，正文只按需读取。

## 5. 评分提交切片 FE-08-F1

复用 `POST /api/v1/sessions/runs/{runId}/evaluations`，请求为 `{name,value,observationId?,comment?}`。
name匹配 `[A-Za-z0-9._-]{1,64}`；value为有限数值0–1；observationId最多64，comment最多1000。
响应202为 `{runId,traceId,name,accepted,queuedAt}`；accepted是提交到进程内导出队列的结果。
用户页面固定 name=`user.satisfaction`，不提供 observationId 输入；“满意”1、“不满意”0，可选原因。
仅在可读完整结果的成功 Run 上显示反馈入口；该 UI 条件不伪称旧API已有终态约束。
accepted=true提示“反馈已进入异步队列”；false提示“观测导出当前未接受，请稍后重试”，不宣称已保存。
当前接口没有requestId幂等或历史读；提交中禁用按钮，网络结果未知时不自动重发。
F1仅保留本页反馈提交状态，刷新后不显示虚构的已提交记录；本地历史完成前标明该限制。
FE-08-F2 接入后，本历史页改走feedback事实接口；既有evaluations路径仅保持兼容，不再由本页触发，避免双份评分。

## 6. 可恢复反馈切片 FE-08-F2（新增后端）

F2独立实施，不改变旧 evaluations 行为；平台拥有用户反馈事实，观测模块继续负责异步导出。
新增 `POST /api/v1/sessions/runs/{runId}/feedback`：`{clientRequestId,value,comment?}`，请求ID最长128，value范围0–1，comment最多1000。
返回201 `{feedbackId,runId,value,comment,createdAt,exportDisposition}`；同请求同内容重复提交返回200同事实。
同请求不同内容返回409 `STATE_CONFLICT`；服务端固定scoreName，不接受客户端提供userId或traceId。
requestDigest按规范化数值、原始comment和runId构造；同一数值1/1.0等价，禁止用客户端提供的摘要代替服务端计算。
新增 `GET /api/v1/sessions/runs/{runId}/feedback`：返回 `{items,nextCursor,hasMore}`，limit/cursor遵循公共规则。
两接口先验证Run属主；写入还要求成功且完整可见结果存在，状态冲突返回409，非属主/不存在返回404。
本地先事务保存反馈，再尝试现有观测队列；响应区分本地持久化与导出，导出故障不回滚反馈事实。
控制器在既有API边界依次调用平台保存、观测提交和平台状态记账；Platform不新增对Observability的依赖，不在保存事务内调用导出。
exportDisposition仅为 `QUEUE_STATUS_UNKNOWN / ACCEPTED_NOT_CONFIRMED / NOT_ACCEPTED`，永不凭队列接受标为“远端已保存”。
初始状态为QUEUE_STATUS_UNKNOWN；队列接受后宕机而未写回也保持此状态，不能把未知事实解释为尚未尝试。
本地提交后到导出状态写回之间宕机，重开显示“导出未确认”；同requestId不重复尝试导出，避免结果未知时盲目重发。
首版不承诺可靠远端补送；若后续需要可靠导出，另建持久导出意图、幂等提供方协议和Worker规格。
历史反馈按创建时刻显示；用户更改评价提交新事实，保留旧记录，不用覆盖写隐藏历史。
F2上线后UI只调用feedback路径，由服务端一次提交观测，禁止同时调用evaluations导致双份评分。

## 7. 模块与文件入口

| 模块 | 文件入口 | 工作 |
| --- | --- | --- |
| Web | [EmployeesView.vue](../../../haizhuo-brain-web/src/views/app/EmployeesView.vue)、[路由](../../../haizhuo-brain-web/src/router/index.ts) | 全部会话导航 |
| Web | `src/views/app/SessionHistoryView.vue`（拟新增） | 分页/筛选与失败状态 |
| Web | [SessionView.vue](../../../haizhuo-brain-web/src/views/app/SessionView.vue)、[app.ts](../../../haizhuo-brain-web/src/api/app.ts) | active/inspected分离、引用与反馈封装 |
| Web | `src/components/app/RunHistoryDrawer.vue`、`RunFeedbackPanel.vue`（拟新增） | 历史只读详情与反馈状态 |
| API/Platform | [SessionController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/SessionController.java)、[SessionApplicationService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/session/SessionApplicationService.java) | 新分页、安全投影与已有属主校验 |
| Infrastructure | [JdbcSessionRunStore](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/session/JdbcSessionRunStore.java)、[JdbcAgentResultRepository](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcAgentResultRepository.java) | 分页查询及索引 |
| API/Observability | [RunEvaluationController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/RunEvaluationController.java)、[LangfuseEvaluationService](../../../haizhuo-brain-observability/src/main/java/com/haizhuo/brain/observability/LangfuseEvaluationService.java) | F1契约；F2复用导出，不转移反馈所有权 |
| Platform | `haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunFeedbackService.java`、`RunFeedbackRepository.java`（同目录，拟新增） | F2持久事实、幂等和历史 |
| Infrastructure | `haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunFeedbackRepository.java`（拟新增） | F2保存、回读及状态记账 |
| API | `haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/RunFeedbackController.java`（拟新增） | F2读写端点 |

## 8. 顺序明确的实施清单

- [ ] FE-08-I01：按FE-02/FE-03模型完成activeRun与inspectedRun分离，实施前执行相关符号impact。
- [ ] FE-08-I02：补新历史分页端口、属主过滤和真实MySQL索引验证，保留旧数组。
- [ ] FE-08-I03：实现全部会话和Run历史抽屉，验证历史浏览不影响活动Run控制。
- [ ] FE-08-I04：独立结果引用入口、分页选择、8项/512 KiB校验及提交请求ID稳定。
- [ ] FE-08-I05：接F1现有评分，准确呈现accepted与未知结果，完成有限闭环验收。
- [ ] FE-08-I06：独立新增F2本地反馈迁移/端口/幂等读写，再切UI至单一路径，不将F1当作可恢复反馈完成。
- [ ] FE-08-I07：完成浏览器并发/跨属主/长结果验收，分别记录F1、F2及远端未验证边界。

## 9. 持久化、兼容与回退

历史分页只扩展查询；V41新增 `platform_agent_session(user_id,created_at,session_id)`、按Session/状态排序的Run索引和结果Run/可见性排序索引，最终仍需按真实MySQL查询计划确认。
F2新增 `platform_run_feedback`：feedback_id、run_id、user_id、client_request_id、request_digest、score_value、feedback_comment、created_at、export_disposition；当前源码迁移为V40，历史页索引为V41，均尚未应用到数据库。
唯一约束 `(user_id,run_id,client_request_id)`；历史索引 `(run_id,user_id,created_at,feedback_id)`；数值范围在服务和数据库约束。
不存密码、原始工具参数或完整答案；comment为用户输入，不入通用日志；反馈读取仅属主可见。
仅新增Flyway迁移；老数据无需伪造反馈，旧Run无完整正文按历史摘要处理。
新入口可关闭，旧会话/数组/评分路径继续可用；反馈表回退时保留数据，不删用户已提交事实。

## 10. Given / When / Then 验收

| 编号 | Given / When / Then |
| --- | --- |
| FE-08-A01 | Given 历史多于一页；When 翻页并同时新增会话/Run；Then 游标稳定、无重复，旧最近会话接口形状不变。 |
| FE-08-A02 | Given 当前Run执行中；When 查看旧Run工具/结果；Then 实时继续，取消、引导与审批仍指向当前Run。 |
| FE-08-A03 | Given 旧详情读取在途；When 切换Session或Run；Then 迟到响应不覆盖新详情，选中引用不跨Session。 |
| FE-08-A04 | Given 单角色会话有完整结果；When 选择引用发送；Then 入口可见，新Run持久保存引用来源与哈希。 |
| FE-08-A05 | Given 超过8项、超过512 KiB、旧摘要或跨属主结果；When 直接提交请求；Then 服务拒绝，前端不得扩大权限。 |
| FE-08-A06 | Given 提交超时；When 重试同输入和引用；Then 保持requestId且不重复创建Run；修改输入后才视为新提交。 |
| FE-08-A07 | Given F1队列接受/拒绝/超时；When 提交反馈；Then 三种提示准确，不显示远端已保存，不自动重发未知结果。 |
| FE-08-A08 | Given F2本地已保存反馈；When 刷新或重开；Then 读到相同事实，导出状态不冒充远端确认。 |
| FE-08-A09 | Given 同requestId重复或改内容；When 提交F2；Then 同内容同feedbackId，变更内容409，不重复导出。 |
| FE-08-A10 | Given 导出尝试之间宕机/观测关闭；When 重开反馈；Then 本地事实留存，准确区分队列状态未知/未接受/已接受且远端未确认。 |
| FE-08-A11 | Given 非属主访问历史/结果/反馈；When 伪造ID请求；Then 服务端拒绝，页面不暴露内部执行参数及私有信息。 |

## 11. 测试层级与完成标准

单元：控制目标隔离、请求代次、引用预算、反馈状态及幂等；API：旧响应兼容、新分页与属主拒绝。
真实MySQL：分页索引/稳定排序、反馈唯一键并发与迁移；浏览器：历史浏览时实时执行、单角色引用、反馈重开。
真实服务E2E：历史Run全文、引用后执行、观测启停；Langfuse远端保存需独立证据，不能用accepted替代。
F1完成不等于F2完成；全部FE-08完成需上述验收记录、相关模块测试与前端构建，并单列远端未验证项。
2026-10-09 首次实施进度记录：历史会话/Run/结果 keyset 查询、独立历史页面、安全工具摘要、引用选择、F2反馈迁移/平台事实存储/属主端点/幂等/历史读取及单路径前端接线已新增；当时 API/H2 与构建尚未运行。此状态已由本轮统一复验更新：`SessionHistoryControllerTest` 2/2、`SessionHistoryQueryServiceTest` 3/3、`JdbcSessionHistoryQueryTest` 3/3、Run feedback service 4/4、repository 2/2、controller 1/1 均通过；前端 35/35、类型检查及生产构建通过。真实 MySQL、HTTP 服务器与浏览器验收仍未执行；F1 accepted 状态仍不等于远端已保存。
