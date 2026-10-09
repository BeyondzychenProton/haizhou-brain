# SPEC-03 实时消息恢复与渲染

ID：FE-03。日期：2026-10-09。状态：**消息恢复与渲染代码已落盘；本轮 root-only、归并、Sanitizer、持久视图、API 定向 Java 测试及 `AgentScopeRuntimeTest` 通过；Vue 类型/35项单测/生产构建通过；MySQL、多实例、HTTP/SSE 与浏览器 DoD 未完成；v3 功能开关保持关闭**。优先级：P1。
前置：[FE-00](SPEC-00-公共契约与实施约定.md)、[FE-02](SPEC-02-会话事件与完整结果.md)。覆盖 F08/F10 的生成连续性、计划呈现及消息渲染；不接管智能体调度或恢复推理。

## 1. 当前证据与目标

当前 Session SSE 已跨 Run 补读持久事件，sessionCursor 可恢复长期事实。文字增量经单实例 multicast/directBestEffort hub 发送，无回放缓存；另一 API 实例、慢消费者和断线可能漏文字。
translator 已描述原生 replyId/blockId，当前平台 publicTextDelta 将它们压为 runId-assistant/text；前端仅追加字符串并按 eventId 有限去重，没有 attempt/offset 缺口恢复。
MarkdownMessage 对变动后的全文重新解析，页面尚无成体系的批量更新、长历史虚拟化与自动滚动规则。

### 2026-10-09 实施基线与影响核对

实施起点：分支 `refactor/session-runtime-identity`，HEAD `eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`；工作树已有 FE-02/04/05 等未提交修改，均保留。首次核对时迁移目录最新为 V36，本切片获分配 V37；随后其他并行切片新增 V38-V41。起点的 `AgentScopeRuntime.execute()` 对每个业务执行订阅创建独立 translator；`RunExecutionService.drive()` 是现有唯一业务订阅和文本 delta 投递点；普通会话 SSE 仅查持久事件并合并进程内 best-effort hub。起点没有展示批次持久化、renderCursor 或 view API。

### 当前落盘状态（2026-10-09；尚未完成验收）

- 已新增 V37 展示表与 `JdbcSessionRenderStore`：按 Session 行锁分配已提交 `renderCursor`，记录 attempt/fence、文字 offset、受控正文和短期批次；实现 owner 校验、重复区间幂等/异内容拒绝、旧 fence 拒写、窗口过期和预算降级。V37 不改已有迁移。
- 已新增只读 `GET /api/v1/sessions/{sessionId}/view` 和 `GET /api/v1/sessions/{sessionId}/render-events?after=&renderAfter=`。SSE 从持久 Session 事实与 JDBC 批次补读；连接/恢复不调用 `AgentRuntime.execute()`，未改旧 `SessionStreamController`。后端开关 `haizhuo.brain.session-render-v3.enabled` 默认 `false`。
- 已在 `RunExecutionService.drive()` 现有的单次 `runtime.execute()` 观察链增加 ROOT 展示批次写入；通过 100ms/最多64事件的聚合和 8 KiB UTF-8 安全分块，不增加第二个模型订阅。写入失败仅标记展示降级，不回写 Run 终态。该同步持久写路径的真实负载/慢消费者表现仍未验证。
- 已新增前端 v3 API、只读恢复 transport、纯 presenter 和 `SessionRenderTimeline.vue`，并在 `SessionView.vue` 留有 gated 集成；`VITE_SESSION_RENDER_V3` 必须显式为 `true` 才启用。正式结果沿用 FE-02 loader，正式消息身份固定 `runId-assistant`；滚动跟随受近底部条件约束，历史分页保留锚点。
- 首轮定向重跑曾遇 AgentScope WebFetchTool 的 loopback 权限错误，且早期工具等待断言未通过；该历史结果已由末尾“最终复验补记”取代。当前 `AgentScopeRuntimeTest` 为 10/10、扩展后的 `JdbcSessionRenderStoreTest` 为 6/6。H2 新增验证覆盖两个独立 store 对象读取同一持久批次、慢读者按有界页续读、替换 attempt 后旧 fence 无法写入，以及窗口过期边界；真实多进程/MySQL、提交竞态、HTTP/SSE 和浏览器仍未验收，DoD 未完成，v3 保持关闭。

GitNexus repo 注册名为 `haizhou-brain`。本轮 impact（upstream）仍将以下现有入口判为 CRITICAL；统计可能随索引/当前工作树变更，根任务先前提供的 translator/stream 计数与本轮复查不同，但不得以 `riskSharedAxes` 降级：

| 符号 | 本轮 impact | 高风险边界与编辑策略 |
| --- | --- | --- |
| `RunExecutionService` | CRITICAL，99 symbols / 105 processes / 20 modules | 执行状态主链；只在 `drive()` 已有唯一订阅处透传 ROOT 文字描述与 attempt offset，不新增订阅/推理循环。 |
| `RunRealtimeEventPublisher` | CRITICAL，60 / 98 / 20，lower-bound | 接口动态分派有两个实现未能静态追踪；保留旧抽象方法及函数式兼容，新增 default 投递入口并用独立适配器组合旧 hub 与展示存储。 |
| `SessionStreamController` | CRITICAL，60 / 104 / 20 | 旧 v1/v2 协议和持久游标路径；不编辑，新增独立 v3 展示流端点，读取 JDBC 批次，不调用执行 API。 |
| `AgentScopeEventTranslator` | CRITICAL，411 / 105 / 20 | 原生事件边界；不改 translator，以 descriptor 的现有 ROOT/reply/block/attempt/fence 字段建立展示映射。 |
| `JdbcRunEventAppender` | CRITICAL，91 / 105 / 20 | Run 事实与 Session 游标事务核心；不改 appender，不把 renderCursor 当作 sessionCursor。 |

### 普通用户文字发布与显示安全补充（2026-10-09；实现记录）

- `RunExecutionService.drive()` 对每个 `AgentTextDeltaEvent` 仍先推进 attempt 内 `streamOffset`；只有 descriptor 明确为 ROOT 的增量才经 `SessionRenderTextSanitizer` 同时进入 v1/v2 实时发布与 v3 展示批次。CHILD、UNKNOWN、null descriptor 的文本不会进入普通用户实时发布或展示存储；既有内部消息事实仍按原机制独立记录。隐藏事件仍消耗 offset，公开偏移可能有间隔；v3 批次的文字 offset 以脱敏后的 code point 计数。
- display sanitizer 只生成展示文本，不参与 `AgentRunCompletedEvent` 的结果落定，不改不可变 `ROOT_FINAL` 正文、持久 `bodySha256` 或 source `byteSize`。FE-02 完整结果校准仍保留原文与源身份；普通用户会话消息、v3 timeline、历史结果、协作结果和批准参数在 UI 渲染端使用相同凭据/内部路径脱敏。Markdown 与纯文本分别覆盖。
- 覆盖样本包括 `Authorization: Bearer ...`、`Proxy-Authorization`、自由文本 Bearer、`api_key`/`clientSecret` 等 key-value、含空格的 Windows 用户路径及 `/home/...` Unix 路径。安全显示不替代既有 visibility/owner 过滤，也不允许 CHILD/UNKNOWN 内容因脱敏而进入展示。
- GitNexus impact（repo `haizhou-brain`，worktree `refactor/session-runtime-identity`，索引 commit 与 HEAD 同为 `eda9c79bd438df9f84f4b2c86c320dc8a0f078ba`）：`RunExecutionService` CRITICAL（99个受影响符号、105 processes、20 modules；本轮重新查询为99/105/20；只改现有唯一订阅的 delta 发布分支）；`RunRealtimeEventPublisher` CRITICAL（动态接口分派 lower-bound；未改接口）；`RunExecutionServiceTest` LOW。`AgentTextDeltaEvent`、`SessionRenderTextSanitizer`、`MarkdownMessage.vue`、`SessionView.vue`、`SessionRenderTimeline.vue`、`SessionHistoryView.vue`、`RunProgressCard.vue` 对 GitNexus 返回 UNKNOWN/未索引；源码核对了事件声明与 translator/运行时消费点，以及各 Vue 模板的所有用户可见正文绑定；新 UI helper 的调用点以 `rg` 逐一确认。没有通过 `riskSharedAxes` 降级 CRITICAL。
- 新增 `SessionRenderTextSanitizerTest` 和 `RunExecutionServiceTest.onlyRootSafeTextIsPublishedAndCanonicalResultRemainsUnchanged`。早期记录中 Java/Maven 未执行、前端 sanitizer 2/2 已过；本轮最终复验结果和仍未执行的环境层级见本文第12节，不把旧记录作为当前结论。

方法级 impact 对 `RunExecutionService.drive`、`RunExecutionService.publishTextDelta`、`AgentScopeEventTranslator.translate`、`JdbcRunEventAppender.append` 均返回 UNKNOWN（GitNexus 未解析这些方法名），按源码搜索补充的调用证据如下：`RunExecutionService.executeNext()` 第125行调用私有 `drive()` 第156行；第174-176行只在同一 `runtime.execute(request)` 的 `doOnNext` 内观察 `AgentTextDeltaEvent` 并调用 `publishTextDelta()`，其实现第466-469行转发到实时端口。`AgentScopeRuntime` 第102-105行对该次原生事件流使用 `executionTranslator.translate()`；translator 第83行实现并由运行时测试及固定版本探针直接调用。`JdbcRunEventAppender.append()` 第46、49-51行锁 Session/Run 并创建持久事实；Run 执行存储、工具审批、恢复等已有写入点显式调用 `events.append()`，`JdbcRunRecoveryStore` 与 `JdbcToolApprovalRepository` 也复用其 Run 锁。源码核验确认后续只新增展示写端，不编辑 UNKNOWN 的 translator/appender 方法。

前端 `SessionView` 的 GitNexus impact 返回 UNKNOWN（图中未解析该动态路由组件）。源码搜索 `rg -n "SessionView|views/app/SessionView" haizhuo-brain-web/src/router/index.ts haizhuo-brain-web/src` 仅确认 `router/index.ts` 的 `/app/sessions/:sessionId` 懒加载接入；此次只在 `SessionView.vue` 增加开关隔离的展示分支，旧 v1/v2 分支保留。UNKNOWN 不作为无调用者的安全证据。

W0 身份探针仍只覆盖一轮回复中的两个 text delta；AgentScope Java 2.0.3 对它们返回相同 replyId、blockId=`text`。FE-03 已补充驱动 schema-only 工具等待与恢复的探针代码，但尚未执行，不能把它当成多轮等待的运行证据。临时 view/SSE 均为只读；即使刷新或重连，也不调用 `AgentRuntime.execute()`。`haizhuo.brain.session-render-v3.enabled` 在代码和部署配置中的默认值固定为 false；测试可显式开启，生产接入需满足本 SPEC DoD 后另行变更。

目标分为四项：
- 同一 Run 的原生回复/内容块保持稳定身份，用户最终结果仍由 ROOT_FINAL 校准。
- 活动执行期间刷新/重连恢复已提交草稿，后续批次不重复、不串 attempt。
- Worker 故障展示已提交部分和待核查状态；不保证未提交的最后一小批文字。
- 不同实例从同一 JDBC 展示资料补读；浏览器断连不取消或重复执行 Run。

## 2. 固定版本复用与前置探针

已静态阅读本地 AgentScope Java 2.0.3 source JAR：HarnessAgent.streamEvents 委托 wrapped/delegate，ReActAgent.streamEvents 使用 buildAgentStream，AgentResultEvent 仅包含 result、没有 replyId。
TextBlockDelta 的 reply/block 描述经现有 translator 可读，思考、CHILD 与 UNKNOWN 保持过滤。本 SPEC 不将内部 source/sessionId 返回用户。

W0 的无网络契约探针 `NativeTextBlockIdentityProbeTest` 使用固定版本与可控模型，确认根执行产生两个文本块增量，翻译后均为 ROOT，replyId 和 blockId 原样透传，AgentResultEvent 翻译后没有 replyId/blockId。观测到两个文本块增量共享同一个 replyId 和 blockId=`text`；该结果说明不能将 blockId 当作每个文本片段的唯一 ID。历史 W0 曾记录一次 11 项通过，但不是本轮 FE-03 扩展后的测试结果，不能据此宣称当前代码通过。

FE-03 已新增 `AgentScopeRuntimeTest.fixedVersionProbePreservesRootRepliesAcrossExternalToolWaitAndNewAttempt` 探针，使用固定版本与可控模型/工具驱动工具等待和恢复；当前 10/10 通过，覆盖等待前后 ROOT delta、attempt/fence、reply 边界与无原生 ID 的 formal result。完整探针仍须确认：
1. 多次模型回复、多块文本、工具等待和恢复时的 replyId/blockId 边界。
2. 根/子/未知来源、思考和文本分离；直接专家执行仍为 ROOT。
3. 一个业务驱动订阅执行一次；展示订阅关闭不会再次执行模型或取消业务执行。
4. 原生 final 无回复 ID 时，ROOT_FINAL 独立归并，不能猜“最后一个 reply”。
探针失败先调整薄适配契约；不得通过重写循环或暴露私有消息解决。

## 3. v3 消息模型

保留 v1/v2 协议；新客户端显式选 format=v3，默认开关关闭，验收后逐环境启用。
普通用户消息模型：

| 字段 | 语义 |
| --- | --- |
| messageId/runId/sessionId | 平台稳定 ID 与业务归属；ROOT_FINAL 固定 runId-assistant，与FE-02/v2相同 |
| kind | USER_INPUT / ASSISTANT_DRAFT / ROOT_FINAL / PUBLIC_STATUS |
| executorRoleId | 冻结执行目标的公开角色 |
| attemptId | 本次尝试的不透明公开关联值；不含 fence、路径 |
| phase/bodySource | FE-02 的状态和来源 |
| blocks | blockId、type、text/data、安全类型字段 |
| lastAppliedOffset | 此 attempt 已提交的根文字顺序 |
| resultId | ROOT_FINAL 的不可变引用；草稿不可借此推断授权 |
| generation | 前端视图代次，不传成平台业务版本 |
| partial/degradedReasonCode | 草稿不完整或展示降级的受控提示 |

原生 reply/block ID 转成持久平台 messageId/blockId；内部映射只保存必要散列或受限键，不公开 native session/source。
缺少原生标识时使用本 attempt 的明确 fallback identity，并标 identityQuality=FALLBACK；不假装多回复可精确区分。
每个原生回复形成草稿区条目；同一块追加文本，多块按出现顺序。ROOT_FINAL 独立消息，完成后草稿合并为默认折叠过程区，正式答案只有一个主气泡，不靠删除最后草稿猜测。
v2 runId-assistant 保持原意义，由兼容 presenter 接收；不在旧协议悄悄改 ID。

## 4. 新一致性视图与传输

已新增：
- GET `/api/v1/sessions/{sessionId}/view?limit=20&cursor=...`
- GET `/api/v1/sessions/{sessionId}/render-events?after={sessionCursor}&renderAfter={renderCursor}`；旧 SSE 协议未扩写，v3 使用独立只读端点。

view 首屏在一个只读一致性事务中读取 Session、Run、公开等待摘要、消息投影、结果引用，以及 snapshotCursor/cursorFloor、renderCursor/renderCursorFloor。
响应含 schemaVersion=3、sessionId、runs、resultRefs、snapshotCursor、cursorFloor、renderCursor、renderCursorFloor、draftRecoveryStatus，以及顶层 items:MessageView[]、nextCursor、hasMore；不再另设messages数组。
items 为按消息顺序的逻辑条目；limit 默认20/max100，不按 token 分页。历史页只加载更早消息，不回退当前实时游标。
稳定分页排序键为runCreatedAt、runId、kindOrder、messageOrdinal、messageId：同Run内USER_INPUT=0、ASSISTANT_DRAFT=1、PUBLIC_STATUS=2、ROOT_FINAL=3，草稿ordinal首次映射时固定，状态ordinal为runSequence。查询倒序取更早页，显示时升序；cursor绑定属主/Session及首次C0/R0边界，历史页不纳入界面接流后才新增的条目。当前Run等待卡/控制区独立加载，不因首屏20条窗口隐藏当前审批。
USER_INPUT、ROOT_FINAL及公开状态从既有持久事件/结果/Run事实在同一REPEATABLE_READ视图内组装，约束事件cursor<=C0；不依赖异步更新的消息投影。只有ASSISTANT_DRAFT文字前缀来自展示表，phase再按本视图Run状态校准。这样C0之前已提交的创建/完成不会因展示投影落后被接流跳过；完整正文仍由FE-02读取。
未迁移的旧消息由既有 USER 事件及结果引用适配，legacySummary 明示；不回填不存在的旧文字。完整 body 懒读 FE-02，不在 view 重复装载所有结果。
读取先验证 Session 属主；消息、工具与结果引用均服务端过滤。PRIVATE、思考和原始工具参数永不进入 view。

v3 延续已有持久 envelope，补充公开 attempt 关联；新增批次形状示例：
```json
{
  "schemaVersion": 3,
  "eventId": "session:s1:render:107",
  "sessionId": "s1",
  "sessionCursor": null,
  "renderCursor": 107,
  "runId": "r1",
  "attemptId": "a1",
  "runSequence": null,
  "streamOffset": 14,
  "type": "message.text.batch",
  "visibility": "USER",
  "durability": "transient",
  "occurredAt": "2026-10-09T03:00:00Z",
  "payload": {
    "messageId": null,
    "blockId": null,
    "delta": null,
    "text": null,
    "metadata": {
      "fromOffset": 11,
      "toOffset": 14,
      "segments": [
        {"messageId": "m1", "blockId": "b1", "fromOffset": 11, "toOffset": 14, "delta": "本批正文"}
      ]
    }
  }
}
```

renderCursor 是Session内按提交顺序递增的批次位置；不能直接使用全局AUTO_INCREMENT充当提交顺序，也不能用renderCursor+1判断文字缺失。文字缺口按同attempt的fromOffset/toOffset判定。
持久事件仍用 sessionCursor 作为 SSE id；批次不设置 SSE id，不将 renderCursor 写成 Last-Event-ID。v3 保留两种 durability，批次标 transient，即使短期存于数据库也不是永久业务事实。
同一批次按原顺序排列 segments，只合并相邻同块片段；不得把不同回复文字串成同块。一次批次覆盖一个 attempt 连续文字区间。

## 5. 展示投影与有界暂存

V37 已新增五张展示表：

| 表 | 关键字段与约束 |
| --- | --- |
| platform_session_message | message_id PK，session_id/run_id/attempt_id，kind，executor_role_id，phase，result_id，ordinal，last_render_cursor，partial；Session+ordinal 索引 |
| platform_session_message_block | message_id+block_id PK，type，safe_body，last_applied_offset，byte_size，body_hash；FK 或同等完整性检查 |
| platform_session_render_state | session_id PK，next_render_cursor、committed_render_cursor、render_cursor_floor；初始化0/1边界，事务行锁分配 |
| platform_session_render_attempt | session_id+run_id+attempt_id PK，fence_token、last_text_offset、phase；FK 到原生执行 attempt |
| platform_session_render_batch | session_id+render_cursor PK，run_id/attempt_id，内部claim generation/fence，from_offset/to_offset，safe_segments_json，committed_at/expires_at；Run+attempt+from_offset唯一 |

这些表只承载用户可见展示，不承载推理快照、调度、业务授权或 Run 成功状态。
同一批次事务：按统一顺序锁Run/claim相关行→锁此Session的render_state行→校验有效claim/fence和允许写入状态→核对本attempt已提交offset→在锁内分配cursor→写批次→更新消息/块前缀、last_render_cursor和committed_render_cursor→提交。重复区间同内容幂等；同键异内容拒绝，旧fence不写。
render_state锁持有至提交，后一个同Session事务不能先提交更大cursor；回滚同时回滚分配。建立状态行使用唯一session_id的幂等初始化。现有业务写入若触及展示行也必须采用同一Run→render_state→message/block顺序；执行前核对既有Run/attempt锁顺序，禁止反向获取。清理锁render_state且不再获取Run，避免反序。不同Session不互锁。
view 读取恰好位于已提交批次边界，保证前缀与 renderCursor 同一事实；客户端不必截取批次里的半段字符串。
每次成功运行完成仍以既有 completeFinal 事务为准；展示投影可通过公开事实更新 phase，不能倒过来驱动业务终态。

根可见文字的 offset 在过滤后、同 attempt 内递增。当前实现以 `bufferTimeout(64, 100ms)` 聚合既有事件观察流，并以 attempt-local accumulator 按不超过 8 KiB UTF-8 分块；生命周期/工具等待和流结束处 flush。该实现不另建模型订阅或无界展示队列。
当前写入沿既有事件消费链同步调用持久 store；写入异常/offset 缺口记录展示降级并保留最后已提交前缀，不把丢片段后的 offset 伪装成完整。由于未做压力、慢消费者和运行实例故障验证，仍需确认该同步写路径是否满足生产延迟目标。
新 v3 只发已提交批次；旧 v2 可保留现有即时发布用于兼容。SSE 先查 JDBC，两种游标分别补读；本机 notice 仅唤醒查询，不能替代数据或鉴权。
初始补读轮询建议 250ms，有数据连续 drain；无数据保持心跳。不同实例不依赖同一个 JVM hub，不强制为首版增加 Redis 总线。

暂存批次窗口初值 10 分钟，可配置；消息累计草稿建议每 Run 2 MiB，正式结果仍受现有 1 MiB 上限约束。
超过草稿预算显示“过程展示达到上限”；正式答案继续完整加载。截断标记是展示事实，不能把正式全文截断为成功正文。
活动 Run 的已提交前缀保留到结束；终态后草稿的长期保留期独立配置并经业务确认，不承诺永久保存所有 token。
清理只删除已过期批次/按策略终态草稿，不能删 ROOT_FINAL、持久业务事件或审批。清理与 snapshot floor 更新使用同一约束，不能形成假可恢复位置。

## 6. 首屏、重连和顺序算法

1. 获取一致性 view，应用持久事实边界 C0、批次边界 R0、消息前缀和当前 attempt；为可见成功 Run 调用 ensureResult。
2. 打开同一条 Session SSE，after=C0、renderAfter=R0。服务端从 JDBC drain 包含 view 返回至订阅之间的提交，避免首屏接流缺口。
3. 持久事件按 sessionCursor 去重/排序；批次按 renderCursor 去重且在同 attempt 内核对 offset。两个流之间不假定绝对交错顺序。
4. 批次完全落在已应用区间则丢弃；正常下一段追加；非边界重叠、缺口或未知 attempt 暂停该草稿应用，合并一次 view 重读。
5. 新 attempt 只允许写入自己的草稿；等待恢复前的旧 attempt 批次不可追加到新 attempt。attempt 边界从 view 和 v3 持久状态元数据确认。
6. Run 终态到达即收尾，SUCCEEDED 调全文；即便最后批次晚到，也不能覆盖 canonical-result。其他终态保留已提交部分，不伪装完整。
7. 普通断线保持正文，按 1/2/4/8/10 秒退避；重连带最后已应用的 C/R。401/403 停止，网络故障不改变业务 Run 状态。
8. RENDER_CURSOR_EXPIRED 控制事件包含可用 floor，重读 view 替换草稿前缀，再从新 R0 接流；SESSION_CURSOR_EXPIRED 另按持久快照处理。
9. Worker 故障可能丢未提交的约 100ms 批次；显示提交部分及 RECOVERY_REQUIRED，不从草稿重放推理。核查由 FE-06 承担。

如果 EventSource 无法提供错误状态，先用属主视图读取确认认证/权限后再安排重连，禁止无限未知权限重试。
所有异步回写遵守 generation；切 Session 关闭 SSE、查询、渲染定时器，保留同用户明确允许的受限全文缓存。
没有新持久事实时仍可发生文字批次；更新 renderCursor 不推进 sessionCursor。没有文字时计划/审批仍正常更新。
完整结果缓存、结果加载失败提示沿用 FE-02；重新读 view 不把已知 canonical 正文降级。

## 7. 计划、工具和审批呈现

PLAN_SNAPSHOT 使用当前 Run 最新持久快照，渲染为只读步骤/状态；保留安全文本 fallback，旧不识别 schema 不直接执行内容。
工具卡由既有受保护查询和持久通知刷新；进度不得显示带密钥的原参数。工具卡、根消息和计划可以同时存在，不挤成一条正文。
业务工具批准仍沿用布尔决定接口，绑定当前 Run/等待点；提交成功以持久状态校准，按钮 pending 不等于已批准。
协作卡消费 FE-09 白名单查询；不把 INTERNAL 原文改 visibility 发用户。
泛化 USER_SELECTION 和媒体制品由 FE-11 独立实施，不能因为 v3 DTO 有 metadata 就宣称已有业务生产者。

## 8. 渲染与滚动规则

transport/projection/resultLoader 分离；文字先进入投影缓存，使用 requestAnimationFrame 合批，建议 50ms 更新一次，最大 20 次/秒。终态和全文结果立即 flush。
保留稳定 message/block DOM key，不因新 token 重建整个会话。Markdown 只解析变动块，未闭合代码围栏允许安全暂态；净化每个增量后的最终 HTML。
距底部 80px 内且用户未主动向上阅读才跟随滚动；离底后显示新内容计数/“回到最新”。全文替换保留视口锚点和文本选择。
历史达到约 200 条消息时启用窗口化，向前分页保持锚点；不为优化删除业务历史。
性能验收记录设备/浏览器，使用 8k、100k 字符回答及 1,000 条历史；测更新频率、主线程长任务、滚动与复制。目标为批量频率不超过20次/秒、无持续滚动跳动和漏字；性能数值以实际测量记录，不编造已达标。

## 9. 实施入口与顺序

| 模块 | 已落盘入口（验收状态见下文） |
| --- | --- |
| runtime-agentscope | `AgentScopeRuntimeTest` 增加固定版本工具等待/恢复探针；translator 保持不变 |
| platform | `RunExecutionService` 既有单执行观察链；新增 `SessionRenderStore`、view DTO 与有界文本 accumulator |
| infrastructure | `JdbcSessionRenderStore` 与 V37；一致性 view、claim/fence 检查、cursor 分配和批次清理 |
| api | 新增独立只读 `SessionRenderViewController`、`SessionRenderStreamController`；旧 `SessionStreamController` 不改 |
| web | 新增 `sessionRender.ts`、`useSessionRenderTransport`、纯 presenter 与 `SessionRenderTimeline.vue`；`SessionView.vue` gated 接入并沿用 FE-02 loader |

- [x] 完成既有符号 impact 与 UNKNOWN 源码核验；实现多轮工具等待探针代码。探针尚未在当前修复后运行，ROOT/CHILD 泄露和并发执行结果未获运行证据。
- [x] 新增兼容表/索引、事务批次写入和 view；对应单元/存储测试已添加但尚未运行。
- [x] 接入根事件唯一观察链，避免二次执行订阅；加入有界聚合和降级。
- [x] 新增 v3 SSE JDBC 补读；另一实例接收提交文字尚未验证。
- [x] 新增纯归并、双游标、attempt 防串、首屏交接和过期恢复逻辑；边界测试尚未运行。
- [x] 完成块渲染、分页与滚动逻辑；兼容/故障/浏览器验收尚未执行，v3 继续关闭。

## 10. 验收矩阵

| ID | Given / When / Then |
| --- | --- |
| FE-03-A01 | 多原生回复/文本块和根 final；草稿身份稳定，正式答案唯一且不猜最后回复 |
| FE-03-A02 | CHILD/UNKNOWN/思考携带秘密样本；view、v3、日志均不输出该内容 |
| FE-03-A03 | view 返回后立即生成新批次、再订阅；数据库补读不漏交接段 |
| FE-03-A04 | 断线时产生批次和持久事件；带 C/R 重连后正文无重复、状态收敛 |
| FE-03-A05 | 批次重复、缺段或重叠；去重/重读前缀，不能静默漏字 |
| FE-03-A06 | 工具恢复产生新 attempt 且旧批次迟到；新旧文字不串写 |
| FE-03-A07 | 暂存过期但活动前缀可读；只重读 view，不重新执行 Run |
| FE-03-A08 | Worker 在未 flush 时停止；已提交部分可见，进入核查而非假成功 |
| FE-03-A09 | 执行实例 A、SSE 实例 B；B 从 JDBC 收到提交批次，断连无二次执行 |
| FE-03-A10 | 旧 fence/重复区间异内容/事务回滚；不写脏前缀或孤立批次 |
| FE-03-A11 | 展示仓库失败、队列满或草稿预算耗尽；降级明确，Run 业务完成和全文保持独立 |
| FE-03-A12 | 慢消费者和完成先于最后批次；最终 canonical 一致，晚批次不能覆盖 |
| FE-03-A13 | 旧 v1/v2 客户端、v3关闭；旧流仍可用，FE-02全文修复仍保留 |
| FE-03-A14 | 长代码/表格/恶意 HTML、上翻阅读、1,000条历史；净化、批量、锚点与复制符合规则 |
| FE-03-A15 | 另一用户 Session 或认证失效；服务端拒绝，重连停止且缓存清理 |
| FE-03-A16 | 一条 Session 流跨多个排队 Run；控制目标正确，完成不会混入其他 Run |
| FE-03-A17 | 同Session低cursor事务暂停、高cursor写请求并发；高位置不能先提交被订阅读走，低位置不会永久漏读 |
| FE-03-A18 | 创建/完成事务与view首屏并发、草稿投影尚未更新；C0内用户输入/正式结果可见，全文加载不产生第二个ROOT_FINAL气泡 |

## 11. 迁移、回退与证据

迁移只增新表/索引；旧事件、结果、v1/v2编号保留。不回填不可恢复的历史 delta。
关闭 v3 后恢复 v2+FE-02；保留新增展示资料供核查，清理任务不得依赖新页面存在。
本切片不改变 Run FSM，不迁移 AgentState/KV，不增加第二套事件/消息执行总线。

验证顺序：原生契约→纯归并/投影单元→MySQL原子读写/fence/清理→HTTP/SSE→双实例→浏览器→真实长回答。
DoD：18项验收有证据、构建通过、v1/v2回归、泄露样本过滤、多实例和故障路径实际验证。下方首次实施记录保留了当时的失败/未执行结果；当前最新统一复验以本文末尾“最终复验补记”和总[实施报告](../../07-测试报告/2026-10-09功能闭环实施报告.md)为准。“代码/测试已添加”均不表示验收通过：

| 验收项 | 当前证据状态 |
| --- | --- |
| FE-03-A01、A06 | 原生工具挂起/ToolResult 恢复、ROOT 回复跨 attempt 不合并的运行探针 `AgentScopeRuntimeTest` 本轮 10/10 通过；MySQL/JDBC 重启和浏览器继续未验收。 |
| FE-03-A02、A15 | owner 校验、服务端筛选、失败状态处理代码已添加；v1/v2 与 v3 增量均按 ROOT descriptor 发布且共用 sanitizer，UI 多版式统一脱敏；前端秘密样本单测及 Java `SessionRenderTextSanitizerTest` 通过，秘密样本 HTTP/SSE 与跨用户浏览器验证未执行。 |
| FE-03-A03、A04、A17、A18 | view 与 JDBC 双游标补读/事务实现已添加；首屏交接、断线 C/R 交错、同 Session 并发提交顺序和创建/完成首屏竞态未运行验证。 |
| FE-03-A05、A07、A09、A10、A11、A12 | H2 `JdbcSessionRenderStoreTest` 6/6 通过，含过期窗口 floor、独立 store 对象的有界续读、替换 attempt 后旧 fence 拒写及 offset gap；真实跨进程/MySQL fence、事务异常回滚、慢网络消费者、完成先于末批的竞态仍未运行。 |
| FE-03-A08 | Worker 停止后的展示状态映射已实现；真实进程故障/未 flush 场景未验证。 |
| FE-03-A13 | v1/v2 形状与 API 保留，root-only 的安全增量发布已实现；后端默认开关与前端环境门均关闭；本轮 Java 定向兼容测试与 Vue 生产构建通过，服务端 SSE/浏览器回归仍未执行。 |
| FE-03-A14 | 批量 presenter、Markdown 块、分页与滚动锚点代码已添加；浏览器、恶意 HTML、长回答和 1,000 条历史性能未验证。 |
| FE-03-A16 | 多 Run 持久事件视图逻辑已添加；跨排队 Run 的实际流验证未执行。 |

首次实施验证记录（保留历史）：

- 静态核对：PowerShell `Select-String` 对 `AgentScopeRuntimeTest.java` 的断言调用与 JUnit 静态导入进行比对；新增缺失的 `assertFalse` 后，未限定调用 `assertEquals/assertFalse/assertInstanceOf/assertNull/assertTrue` 均有导入，`assertNotSame` 为全限定调用，`assertClosed` 为本地辅助方法。
- Java 构建/单元：执行 `mvn -ntp -pl haizhuo-brain-runtime-agentscope,haizhuo-brain-platform,haizhuo-brain-infrastructure,haizhuo-brain-api -am -Dtest=AgentScopeRuntimeTest,NativeTextBlockIdentityProbeTest,RootTextBatchAccumulatorTest,JdbcSessionRenderStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`（PowerShell 参数加引号，命令进程前置 JDK 17 bin）。平台 `RootTextBatchAccumulatorTest` 2/2 pass，W0 `NativeTextBlockIdentityProbeTest` 1/1 pass；`AgentScopeRuntimeTest` 11项中2 failure、5 error，WebFetchTool 初始化遇 loopback `Permission denied: connect`，外部工具等待断言也未得到预期暂停事件。Maven 构建在 runtime-agentscope 模块停止，`JdbcSessionRenderStoreTest` 未运行，infrastructure/API 被跳过。另有 Maven `settings.xml` 77行格式警告和启动期一条 `Access is denied`，均未阻止 Maven 进入测试阶段；最终构建失败，不能引用部分通过为整组通过。
- HTTP/SSE、MySQL、双实例、浏览器/性能、真实模型提供方：均未执行；真实模型/生产凭据不属于本轮本地探针前提。
- Vue：主协调者本轮统一验证 `npm test:unit` 29/29、`npm type-check` 通过；Vite 在原生 `fs.realpathSync.native(src/main.ts)` 遇本机 EPERM，改用只作用于验证进程、返回现有可读真实路径的兼容 shim 后，同一 Vite build 通过（1775 modules，保留既有大 chunk warning）。该 shim 没有仓库代码改动。浏览器交互/长回答性能仍未执行。

因此 FE-03 没有任何完整验收项标记为“已通过”：部分单元/W0 探针有通过证据，但多轮等待探针与 JDBC/API 恢复层未通过或未执行，v3 不可开启。
来源：[translator](../../../haizhuo-brain-runtime-agentscope/src/main/java/com/haizhuo/brain/runtime/agentscope/event/AgentScopeEventTranslator.java)、[RunExecutionService](../../../haizhuo-brain-platform/src/main/java/com/haizhuo/brain/platform/run/RunExecutionService.java)、[SessionStreamController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/SessionStreamController.java)、[runStream](../../../haizhuo-brain-web/src/api/runStream.ts)、[MarkdownMessage](../../../haizhuo-brain-web/src/components/conversation/MarkdownMessage.vue)。

## 12. 本轮最终复验补记（2026-10-09）

- 统一 Java 选择集内：`NativeTextBlockIdentityProbeTest` 1/1、`RootTextBatchAccumulatorTest` 2/2、扩展前的 `JdbcSessionRenderStoreTest` 4/4、`RunRealtimeEventHubTest` 2/2、`SessionRenderTextSanitizerTest` 2/2、Run/Session stream/controller 相关定向类通过。随后单独重跑扩展后的 `JdbcSessionRenderStoreTest` 6/6：H2 上两个独立 store 对象共享持久批次、有界慢读者续读、旧 attempt fence 拒写及过期窗口均通过。前端 `type-check` 通过、单元 35/35，Vite 生产构建经验证进程 EPERM 兼容处理通过。
- 大范围 71 类选择集未包含 `AgentScopeRuntimeTest`，随后单独复验该类 10/10 通过，覆盖 schema-only 工具挂起、ToolResult 恢复、前/后工具根回复、attempt/fence 变化与正式结果身份。首次复验发现两个测试期望值对调（不是运行时失败）；仅修正测试预期后复跑通过，未改 AgentScope 运行时实现。
- GitNexus 索引仍对应起始 HEAD；分析器刷新受本机 realpath EPERM/数据库锁阻断。测试类 UID impact 返回 UNKNOWN/无调用，已用源码搜索确认修改仅在 JUnit 测试文件，无生产调用路径。未将旧索引的未解析方法名 CRITICAL 结果作为可靠影响面。
- 真实 MySQL/Flyway、HTTP/SSE、两个进程/实例之间的提交顺序与 fence、慢网络消费者、浏览器刷新/断线和真实模型均未执行。H2 测试只验证独立 store 对象共享数据库时的恢复契约与旧 fence 过滤，不等同多实例部署验收。v3 仍保持关闭。
