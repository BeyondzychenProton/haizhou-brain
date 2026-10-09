# SPEC-00 公共契约与实施约定

ID：FE-00。日期：2026-10-09。状态：**W0 公共实现基线已落实；本轮前端单元 35/35、类型检查通过，71 类 Java 定向套件 264 通过/1 跳过，另 AgentScopeRuntimeTest 10/10；分层验收边界继续适用于各切片**。优先级：所有切片的共同约束。
依据：[详细设计](../功能与界面闭环详细设计.md)、[功能覆盖基线](2026-10-09功能覆盖基线.md)。本 SPEC 不单独改变所有现有接口。

## 1. 实施前置与产物

- 从当前 HEAD/工作树开始复核；本文基线为 eda9c79bd438df9f84f4b2c86c320dc8a0f078ba，原有根 package-lock.json 未跟踪，保留。
- 修改符号前执行 GitNexus upstream impact；HIGH/CRITICAL 提示影响，UNKNOWN/空集补源码。本文不把异常图谱元数据作事实。
- 每个 SPEC 标清复用/新增/门槛未满足；新增文件路径只是实施建议，不声称当前存在。
- 只实施对应切片，不顺带重写框架、身份、布局或整个错误处理系统。
- 文档与业务行为同时更新，完成证据写入 docs/07-测试报告/ 的中文报告。

## 2. HTTP 与兼容

| 项目 | 约定 |
| --- | --- |
| 可信身份 | 普通接口从安全上下文取 userId；管理接口沿用 PLATFORM_ADMIN |
| 所有权 | 先校验 Session/Run/资源归属，再读具体正文或内容 |
| 既有响应 | 保持已有数组、limit/offset、布尔工具审批与 v1/v2 事件 |
| 新分页 | items、nextCursor、hasMore；默认 limit=20，1–100；资源明确稳定排序 |
| 时间 | ISO-8601 带时区；前端仅格式化展示，不据客户端时间判过期 |
| 标识 | 新资源使用不透明 ID；现有 long 用户/员工/版本字段保留原类型 |
| cursor | 不透明，绑定调用者、资源范围、筛选及排序；更改筛选回到首屏 |
| 并发写 | expectedRevision/rowVersion/fence 或对应既有守卫 |
| 重复写 | requestId 固定本次意图；同键同内容重放，同键异内容冲突 |
| 凭据 | 不进入 URL 查询、日志、持久事件、提示词或普通 DTO |

时间排序使用 createdAt + id；资产修订使用 reviewedAt + capabilityRevisionId。现有用户 offset 接口继续复用，不为了统一格式破坏它。

管理导航统一沿用 `/admin` 的受保护 AdminView，新增 `?panel=` 白名单深链接：capabilities/grants/mcp/definitions/users/channels/run-recovery/audits/operations/model-connections。渠道投递使用 channels 内部 tab。各 SPEC 的 `/admin/channels`、`/admin/run-recovery` 等入口可作为受保护别名重定向到对应 panel，不各自复制布局或认证守卫；默认 panel 保持 capabilities。URL 回退/刷新同步 tab，未知键回默认，切换表单遵守未保存提示。

新增分页示例（目标，不是现有全部接口形状）：
```json
{
  "items": [],
  "nextCursor": null,
  "hasMore": false
}
```

## 3. 错误契约

当前 ApiError 为 code/message：认证 AUTHENTICATION_FAILED(401)，无权限 ACCESS_DENIED(403)，参数 INVALID_REQUEST(400)，冲突 STATE_CONFLICT(409)，数据库不可用可返回 IDENTITY_STORE_UNAVAILABLE(503)。旧路径保持原映射；目前“结果不存在”通过 IllegalArgumentException 可能返回 400，前端不能假定所有旧查询均为 404。

新增端点使用类型化异常，可扩展以下安全错误码，不解析数据库异常正文：

| 场景 | 新契约 | 界面行为 |
| --- | --- | --- |
| 不存在/不属当前用户 | RESOURCE_NOT_FOUND，404 | 不泄露其他用户是否存在该资源 |
| 编辑修订过期 | STALE_REVISION，409 | 保留本地编辑；重读、比较、重新提交 |
| 状态/幂等键冲突 | STATE_CONFLICT，409 | 重新核对对象，不自动换键重试副作用 |
| 输入缺前置 | PRECONDITION_REQUIRED，409 | 展示明确缺失步骤 |
| 暂时不可用 | SERVICE_UNAVAILABLE，503 | 安全读退避；业务写保留同 requestId |
| 被门槛禁止 | FEATURE_NOT_AVAILABLE，409 | 显示原因，不以空成功响应伪装 |
| 展示续传窗口失效 | RENDER_CURSOR_EXPIRED 控制事件/视图字段 | 重读展示快照，不改变 Run |

普通业务接口 401 清除当前认证缓存并进入登录；激活和改密表单是显式局部例外：激活失败留在表单，改密失败先用 /me 校准会话，仅会话失效才退出，详见 FE-01。403 停止重连/轮询；网络/503 的只读重试有上限。未经查询确认不得自动重发创建、发布、批准、终止或渠道发送。

## 4. 前端异步与状态

每个页面/会话拥有 generation 标识。请求回写前确认 sessionId/resourceId/generation 仍匹配；切换或卸载取消请求、定时器、SSE。
结果请求可在同 Session 内为历史 Run 完成，不因 activeRun 切换丢弃；只有资源离开当前会话才取消/丢弃其视图回写。

统一提供加载、空、错误、重试、已过期、未开放状态。结果加载失败与业务 Run 失败分别呈现。表单冲突不覆盖编辑内容，待提交期间防重复点击不代替服务端幂等。

新增会话 composable 的职责建议：
- transport：连接、补读、退避、关闭；不写业务状态。
- projection：事实/增量归并和消息终态；不发网络请求。
- resultLoader：全文读取、缓存和同请求合并。
- interaction：既有工具审批和未来泛化选择分别处理。
- renderer：只消费经授权的 ViewModel，不猜测私有资源 URL。

## 5. 事件与游标

sessionCursor 是跨 Run 的持久续传位置；runSequence 是单 Run 顺序；streamOffset 是当次执行的文字顺序。三者不能替代。
FE-03 的 renderCursor 定位有界暂存批次；不放入 SSE 的持久 Last-Event-ID，不当作永久历史。

v1/v2 保持既有 eventId、type、messageId 和 schemaVersion；v3 客户端通过明确能力协商启用，旧页面不知道 v3 时继续工作。
前端不能用 visibility 字段承担鉴权；服务端只向普通用户发允许的事件/DTO。

## 6. 模块与数据规则

| 层 | 职责 |
| --- | --- |
| runtime-api / runtime-agentscope | 原生事件与扩展点；保留运行来源；不依赖 Vue |
| platform | 业务查询/命令、属主、状态守卫、公开投影和不可变规格 |
| infrastructure | SQL/存储、事务、分页索引、暂存与审计实现 |
| api | DTO、参数约束、安全异常、HTTP/SSE |
| bootstrap | 运行装配、开关和提供方实现 |
| web | 页面、契约封装、消息投影、状态交互与渲染 |

迁移仅新增，编号执行时分配；不更改已执行文件。新查询优先读取已有事实，新投影标注可重建范围和缺失状态。文件/制品写入不假定数据库与文件系统有同一事务。

## 7. 安全展示

普通用户禁止读取原生 inbox、思考链、原始工具参数中的凭据、私有专家正文、内部工作区路径、fence 和停止证据原文。
管理员也使用显式字段白名单，审计只显示脱敏摘要与证据引用。文件下载每次重新判权，不能只靠页面隐藏或可猜测 ID。

## 8. 验证及完成标准

| 层级 | 证据 | 不证明的内容 |
| --- | --- | --- |
| 源码/契约静态核对 | 字段、路径、边界和链接 | 实际运行成功 |
| 构建/类型检查 | Maven 编译、Vue build | 浏览器、MySQL、真实提供方 |
| 定向单元/契约 | FSM、冲突、隔离、重放等场景 | 生产依赖 |
| 真实 MySQL | 新迁移、事务、锁和索引 | UI 和远端业务 |
| 浏览器契约 | 可控响应/事件下的界面行为 | 后端真实执行 |
| 真实服务 E2E | 实际登录、页面/API/数据库闭环 | 未使用的真实 MCP/IM |
| 提供方验收 | 验签、授权、调用、送达/回执 | 其他未验收提供方 |

现有验证入口：
```powershell
mvn -ntp -pl haizhuo-brain-platform -am test
mvn -ntp -pl haizhuo-brain-bootstrap -am test
npm --prefix haizhuo-brain-web run build
```

实施前 `haizhuo-brain-web/package.json` 没有 `test:unit`/`test:e2e`。W0 新增不引入依赖的 `test:unit`（Node 内置测试运行器）与 TypeScript 源码导入 helper；当前尚无浏览器 E2E 脚本或浏览器契约执行结果。

W0 原生探针位于 `haizhuo-brain-runtime-agentscope/src/test/java/com/haizhuo/brain/runtime/agentscope/event/NativeTextBlockIdentityProbeTest.java`，使用仓库锁定的 AgentScope Java 2.0.3 和可控模型。已观察到同一原生 reply 的两个文本增量共享 replyId 和 blockId=`text`；AgentResultEvent 翻译成正式完成事件时没有 replyId/blockId。因此 reply/block 只作为草稿关联提示，不能用它生成 ROOT_FINAL 身份；v2/v3 正式根消息仍统一使用 `runId-assistant`。FE-03 的 `AgentScopeRuntimeTest` 另以固定版本运行外部工具挂起/结果恢复和新 attempt/root 身份场景，最终 10/10 通过。它仍不验证泛化用户选择 C、真实 provider、MySQL 持久恢复或多实例浏览器行为。

每个切片 DoD：代码/持久化/API/页面/失败路径齐全；对应验收 ID 留存证据；权限与旧链路回归通过；剩余真实环境门槛明确；文档状态只随实际证据更新。

2026-10-09 统一收尾复验：`npm --prefix haizhuo-brain-web run type-check` 通过，`npm --prefix haizhuo-brain-web run test:unit` 35/35 通过；Vite 生产构建在验证进程内对 realpath EPERM 使用兼容处理后通过。JDK 17 bootstrap reactor 定向套件汇总 265 项、264 通过、1 跳过、0 失败/错误。该批验证不包含真实 HTTP 服务、真实 MySQL/Flyway、浏览器 E2E 或真实提供方；详见[实施报告](../../07-测试报告/2026-10-09功能闭环实施报告.md)。

## 9. 公共验收

| ID | Given / When | Then |
| --- | --- | --- |
| FE-00-A01 | 旧客户端访问旧数组或 v2 | 响应形状和编号保持兼容 |
| FE-00-A02 | 普通用户读取另一用户 Session/结果/制品 | 服务端拒绝，页面拿不到正文 |
| FE-00-A03 | 切换会话后旧请求才返回 | 不回写新会话，不残留定时器 |
| FE-00-A04 | 同 requestId 重发已成功写请求 | 同意图重放；不同意图冲突 |
| FE-00-A05 | 配置编辑期间发生并发发布 | 原编辑保留，服务端拒绝过期写 |
| FE-00-A06 | 权限失效后流断开 | 停止重连，不产生无限请求 |

来源：[ApiError](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/error/ApiError.java)、[GlobalExceptionHandler](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/error/GlobalExceptionHandler.java)、[package.json](../../../haizhuo-brain-web/package.json)、[StreamEvent](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/session/StreamEvent.java)。
