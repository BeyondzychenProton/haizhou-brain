# SPEC-02 会话事件与完整结果

ID：FE-02。日期：2026-10-09。状态：**前端闭环已编码；本轮前端单元 35/35、类型检查与 Vite 生产构建通过，全文/状态边界有定向 Java 覆盖；HTTP、真实服务和浏览器验收未执行**。优先级：P0。
前置：[公共契约](SPEC-00-公共契约与实施约定.md)。范围：盘点 F07/F08/F11/F12 的结果与消息状态闭环；生成中补读升级见 [FE-03](SPEC-03-实时消息恢复与渲染.md)。

## 1. 目标与当前缺口

一个成功 Run 在实时完成、刷新页面、历史查看、事件补读及快照恢复后，显示同一份完整正式答案。事件摘要只用于提示和兼容历史展示。

| 已有事实 | 当前证据 | 缺口 |
| --- | --- | --- |
| 完整根结果、终态和完成事件在同一事务提交 | [JdbcRunExecutionStore](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunExecutionStore.java) 的 completeFinal | 前端未按结果校准 |
| 数据库存储的完成事件正文最多 4,000 个 Java UTF-16 单元 | [JdbcRunEventAppender](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcRunEventAppender.java) 的 summary | 摘要本身不能充当长答案全文 |
| ROOT_FINAL 不可变，正文上限 1 MiB UTF-8 | [JdbcAgentResultRepository](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/run/JdbcAgentResultRepository.java) | UI 必须区分完整结果与 legacySummary |
| 全文 API 与前端 helper 已存在 | [SessionRecoveryController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/SessionRecoveryController.java)、[app.ts](../../../haizhuo-brain-web/src/api/app.ts) | getRunResult 未被会话正文调用 |
| presenter 将 RUN_COMPLETED 整体替换为事件 content | [runEventPresenter](../../../haizhuo-brain-web/src/presenters/runEventPresenter.ts) | 未区分补入的全文、摘要和legacy来源；存在fallback与乱序风险 |
| 失败/取消事件仅追加提示；页面保留 pending 消息 | [SessionView](../../../haizhuo-brain-web/src/views/app/SessionView.vue) | 部分正文可能持续显示生成光标 |

补充校准：[JdbcSessionRunStore](../../../haizhuo-brain-infrastructure/src/main/java/com/haizhuo/brain/infrastructure/session/JdbcSessionRunStore.java) 的 findEvents/findSessionEvents/findTimeline/snapshot 使用 COALESCE(result.body,event.content)，结果关联成功时已经把全文回填到事件读取。因此不能声称当前正常SSE/刷新必然只显示4,000单元，也未复现所有路径截断。本切片建立明确全文来源、legacy标记与竞态校准，兼容已有回填优化。上述为源码确认，不是本轮服务或浏览器验收结果。

## 2. 页面与消息模型

正式回答键固定为 `runId-assistant`，沿用旧 v2 的根回答身份。新增字段：sessionId、runId、phase、bodySource、resultId、bodySha256、resultLoadState、executorRoleId。现有 key/role/text 可通过兼容适配保留。
执行者来自冻结 executionTarget/结果元数据；用户直接选择专家时，该专家仍是此 Run 的根回答。

| 触发 | phase / bodySource | 行为 |
| --- | --- | --- |
| QUEUED / RUNNING | queued / generating，draft | 接受同 Run 增量，呈现受理/生成 |
| WAITING_TOOL / WAITING_CONFIRMATION | waiting-tool / waiting-confirmation | 保留正文，结束连续打字光标，展示对应等待卡 |
| WAITING_INPUT（仅FE-11 C实施后新增） | waiting-input | 显示持久选择等待；决定已记录不代表恢复，新增attempt才续写 |
| 完成事件或查询到 SUCCEEDED | finalizing，保留原 bodySource | 立即触发全文读取；不以摘要替换较长草稿 |
| 全文读取成功且 legacySummary=false | final / canonical-result | 原子替换完整正文；封存，不接受迟到 delta |
| legacySummary=true | final / legacy-summary | 显示“历史摘要”；明确无法还原未保存正文 |
| FAILED/CANCELLED/EXPIRED/TERMINATED | partial-stopped / draft | 关闭光标，保留已收到正文及状态 |
| CANCELLING 或 RUN_CANCEL_REQUESTED | 保留当前正文，显示取消中 | 只有业务终态才能认定停止 |
| RECOVERY_REQUIRED | recovery-pending | 标示待核查；不自动重开推理 |
| 全文读取失败 | finalizing + failed | “运行已完成，完整结果读取失败”；保留正文并提供只读重试 |

已经成功加载 canonical-result 的消息不能因快照、旧事件、失败查询或切换 activeRun 降级为摘要。
同一 Session 的历史 Run 全文请求可以完成；其结果只更新自己的消息，不能改变当前运行控制目标。

## 3. API 复用契约

| 方法/路径 | 状态与用途 |
| --- | --- |
| GET `/api/v1/sessions/runs/{runId}/result` | 已有；读取根正式结果 |
| GET `/api/v1/sessions/{sessionId}/results` | 已有数组；读取可引用结果元数据 |
| GET `/api/v1/sessions/{sessionId}/snapshot?format=v2&limit=200` | 已有；带 snapshotCursor/cursorFloor 的有限事件窗口 |
| GET `/api/v1/sessions/{sessionId}/stream?format=v2&after={sessionCursor}` | 已有；持久事实补读与瞬时文字 |
| 现有 Session/Run、取消、引导、工具批准接口 | 复用；不因正文加载失败重复执行 |

全文响应字段：resultId、runId、mediaType、body、bodySha256、byteSize、schemaVersion、createdAt、legacySummary、executorRoleId、executorEmployeeId、executorDefinitionVersionId。
先由后端验证 Run 属主，再读取根结果。前端校验响应 runId，以及已知完成事件的 resultId；不匹配则拒绝写入并记录不含正文的诊断。
bodySha256 用于缓存版本识别；不把客户端哈希检查当作服务端授权。

显示安全边界：`RunResultLoader` 和正式结果 presenter 保留已属主校验的完整 canonical body 与源 `bodySha256`；用户界面在 Markdown/纯文本渲染边界生成脱敏展示文本。脱敏只作用于展示投影，不改持久 `ROOT_FINAL`、源 hash 或源 `byteSize`，所以展示字符串可能不再与源 hash 相等。v1/v2 实时根增量由 FE-03 在发布前做同一规则的脱敏；子执行者和未知归属增量不进入普通用户流。不要用展示投影回写事件、结果或正式结果缓存身份。

现有“结果不存在”可能是 400 INVALID_REQUEST；401/403、400 与网络/503 分开处理，不假设旧 API 的 404 约定。
正常 SUCCEEDED 的完成事件与结果同事务，收到完成后“不存在”应作为不一致错误暴露；不能无限等待或用摘要假装读取成功。
mediaType=text/markdown 用现有净化 Markdown；不识别的类型以安全纯文本显示，禁止直接插入 HTML。

## 4. 全文加载算法

拟新增 `useRunResultLoader`，只负责读操作：

1. 以 sessionId/runId/resultId（未知 resultId 时以 Run）合并在途请求；完成事件、状态轮询、快照和历史打开均可调用 ensureResult。
2. 活动 Run 进入 SUCCEEDED 即加载；初次历史页面对可见成功 Run 懒加载，最多 4 个并发，避免一次加载所有历史正文。
3. 先保留草稿，进入 finalizing；读取成功后一次性更新正文、来源、哈希和 phase。
4. 缓存只保留当前可信用户、Session 的已授权结果，建议最多 50 条且总计 25 MiB；达到上限按 LRU 淘汰。退出、401 或属主变化清空。
5. 网络/503 自动重试最多 2 次，退避 0.5 秒、1.5 秒；400、401、403及结果身份不匹配不自动重试。手动按钮仅重读全文。
6. 请求回写检查页面 generation、sessionId、runId；同 Session 内切换 activeRun 不作取消条件。
7. 生命周期封存后丢弃迟到增量；先成功读全文、后收到完成摘要也只补事件元数据。

全文读取不调用 createRun、不订阅 HarnessAgent.streamEvents，不触发模型、工具或渠道发送。
前端请求超时不代表 Run 创建失败：一次用户发送保留同一 clientRequestId 与冻结提交内容，重复点击/重试使用原键，成功受理才结束该意图。不得每次 helper 调用都生成新键。

## 5. 首屏、补读与控制目标

首屏仍可复用现有 Session/timeline/runs 加载，归并持久事件后为可见成功 Run 加载全文。快照只有有限事件窗口，不承诺恢复全部历史；历史分页由 FE-08 提供。
v2 的 sessionCursor、已有去重和重连保留；本切片不宣称瞬时 delta 在多实例或断线后已可恢复。

分别维护：
- activeRunId：后端确认占槽的当前运行，控制取消、引导、审批和队列提示。
- inspectedRunId：用户打开的详情/历史运行，不覆盖 activeRunId。
- 每条消息所属 runId：全文与事件只能归并到这个对象。

等待工具恢复后可以回到 generating；terminal 状态优先于到达顺序。旧 Run 查询晚到不能把新 Run 标为完成。
取消后部分消息保留“已停止，内容未完成”；没有收到任何正文时只显示状态，不生成空成功气泡。
ROOT_FINAL 与子结果分开，普通用户不能因存在结果 ID 就读取私有专家正文。

## 6. 模块改动与任务

| 入口 | 本轮交付/当前边界 |
| --- | --- |
| web/presenters/runEventPresenter.ts | 事件归并、全文注入和终态收尾已实现；canonical ROOT_FINAL 优先 |
| web/views/app/SessionView.vue | 接入 resultLoader；active/inspected Run 分离；不让摘要覆盖正式正文 |
| web/api/app.ts | 复用 getRunResult；提交 helper 保留同一次意图的 clientRequestId |
| web/composables/useRunResultLoader.ts | 请求合并、受限并发、缓存、退避和 generation 守卫已实现 |
| MarkdownMessage.vue | 脱敏渲染已接入；批量 Markdown/滚动性能由 FE-03 承担 |
| platform/api/infrastructure | 保留现有全文与属主校验；正常路径未新增结果表或结果写入算法 |

实施清单：
- [x] 定位并 impact presenter、SessionView、helper 受影响符号，核对既有终态事件名。
- [x] 为当前前端配置锁定版本的单元测试入口；受控浏览器契约入口仍待补。
- [x] 实现消息归并纯函数与全文加载器，并覆盖来源优先级、重试、请求合并、缓存失效、并发上限和迟到响应。
- [x] 接入实时终态、状态轮询、首屏时间线、游标过期快照和可见历史消息的全文读取。
- [x] 完成取消、失败、等待、恢复提示及 activeRunId/inspectedRunId 隔离。
- [ ] 执行下表验收、旧发送/工具批准回归及前端构建。

## 7. 验收矩阵

| ID | Given / When / Then |
| --- | --- |
| FE-02-A01 | 给定 8,000 字符以上含 emoji/代码表格的正式结果，完成事件仅有摘要；实时结束后正文、复制和刷新均等于完整 body |
| FE-02-A02 | 给定全文先返回、完成事件或 delta 后到；canonical 正文与终态保持不变 |
| FE-02-A03 | 给定完成事件丢失而状态查询为 SUCCEEDED；仍读全文并结束光标 |
| FE-02-A04 | 给定失败/取消/过期/终止前有部分正文；保留部分、明确状态，不保留生成光标或成功标签 |
| FE-02-A05 | 给定取消请求已受理而 Run 仍 CANCELLING；展示取消中，不提前断言外部动作停止 |
| FE-02-A06 | 给定 WAITING_TOOL/WAITING_CONFIRMATION 后恢复；等待阶段无光标，恢复后续写属于同 Run |
| FE-02-A07 | 给定 legacySummary 结果；标历史摘要，不承诺完整正文 |
| FE-02-A08 | 给定全文读网络失败/400/401/403；有界退避或停止，手动重读不创建 Run |
| FE-02-A09 | 给定跨 Session 迟到结果及错误 runId/resultId；不覆盖当前会话或其他消息 |
| FE-02-A10 | 给定查看旧 Run 时新 Run 活动；取消/引导/批准只作用于服务端确认的当前目标 |
| FE-02-A11 | 给定同一完成被实时、轮询、快照同时观察；只创建一个全文在途请求，正式气泡唯一 |
| FE-02-A12 | 给定提交超时但后端已受理；同 clientRequestId 重试只产生一个 Run |
| FE-02-A13 | 给定他人/私有结果；服务端拒绝，正文不进入 UI 缓存或日志 |
| FE-02-A14 | 给定 ROOT_FINAL 含 Authorization/Bearer、凭据 key-value 及 Windows/Unix 内部路径；完整源 body/hash保持不变，Markdown/纯文本展示均脱敏 |

## 8. 验证、兼容与回退

单元覆盖状态归并、结果优先级、迟到请求及请求合并；浏览器契约覆盖长 Markdown、失败重试、多个 Run 与复制全文。
后端 HTTP 契约覆盖属主、legacySummary、正文大小及根结果选择；若后端未改，仅运行受影响现有契约，不补镜像式测试。
真实服务验收至少一次登录→发送→长正文完成→刷新→历史查看；模型未使用时标为受控响应验收。

不需要为本切片新增 Flyway。v1/v2 形状保持；后续 FE-03 回退仍保留本切片的全文校准和终态修复。
DoD：全部 FE-02 场景有证据，Vue 构建通过，所有成功回答入口读全文；没有将事件摘要写成正式答案。

## 9. 本轮实施与验收记录（2026-10-09）

- 代码：前端新增只读全文加载器、v2 根消息归并、完整结果优先级、终态/等待/取消/失败呈现；提交超时保留同一 `clientRequestId` 与冻结输入/角色/引用。正式结果不会被迟到 delta、摘要、状态轮询或快照重建降级；Run 控制按 `activeRunId`，检查器按 `inspectedRunId`。
- 本轮通过：`npm --prefix haizhuo-brain-web run test:unit`（17/17）；`npm --prefix haizhuo-brain-web run build`（`vue-tsc` 与 Vite 通过）。前端生产构建报告既有动态路由导入及超过 500 KiB bundle 警告。
- 单元证据覆盖：A02、A04（失败及无正文终态）、A05、A06、A07、A08 的重试/不重试分支、A09 错误 Run/resultId 拒绝、A11 请求合并/单一正式身份。A01 以 8k+ Unicode/Markdown 正文纯函数校准覆盖，尚未做浏览器复制/刷新；A03/A10/A12/A13 仅静态接线核对，未做 API/服务/浏览器验收。
- 源码校准：结果端点已先校验 Run 属主再返回 ROOT_FINAL 正文；`JdbcSessionRunStore` 的事件、timeline 与 snapshot 查询已在有结果时回填完整 body，因此未将 4,000 UTF-16 摘要上限写成所有现有读取必然截断的事实。此次没有修改后端与 Flyway。
- GitNexus：`mergeConversationEvent`/`loadTimeline` 等影响分别为 HIGH/CRITICAL，修改前已回看调用链；对返回 UNKNOWN 的 `getRunResult`、Vue SFC 和 `send` 处理器使用源码搜索确认了使用点。实施中保持既有接口和 owner-check，不扩写后端共享状态算法。
- 2026-10-09 FE-03 安全显示补充（早期记录）：canonical loader/presenter 未变；普通会话、v3、历史结果及协作结果显示接入 UI-only 脱敏，新增脱敏测试当时为 2/2；此后本轮最终复跑的 Vue 类型检查、35 项 Node 单元和 Vite 构建均通过，浏览器验收仍未执行。源码正式结果与 hash保持原样，见 FE-03 安全显示边界。
- 未执行：HTTP/权限隔离、真实 MySQL、受控浏览器/真实浏览器刷新与复制、模型端到端、旧工具审批回归。故本切片还未达到全部验收 DoD；不得将以上代码级通过视为真实服务验收。
