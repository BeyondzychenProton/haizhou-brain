# 工具与 MCP 模拟闭环功能验收报告

日期：2026-09-28。范围：本轮 MCP 连接治理、数字员工发布、逐用户 Run 视图、调用判权、异常与模拟 Server。设计入口见[概要设计](../03-Agent与能力/工具MCP实施/工具与MCP实施概要设计.md)和[详细设计及 SPEC](../03-Agent与能力/工具MCP实施/工具与MCP实施详细设计.md)。本报告的“通过”只指明测试环境和场景，不替代生产接入验收。

## 环境与执行

- Windows、JDK 17.0.9、Maven 3.9.14、Docker Desktop、Testcontainers MySQL 8.4；MCP 客户端为 AgentScope Java 2.0.3 锁定的 MCP Java SDK 0.17.2。
- 全模块后端回归：`mvn -gs maven-test-settings.xml -s maven-test-settings.xml -o -ntp -q -pl haizhuo-brain-bootstrap -am test`。临时 settings 只为指向本机已有 Maven 缓存，收尾时删除；使用者应按本机 Maven 配置运行常规 `mvn -ntp -pl haizhuo-brain-bootstrap -am test`。
- 定向功能回归：`mvn -gs maven-test-settings.xml -s maven-test-settings.xml -o -ntp -q -pl haizhuo-brain-infrastructure -am '-Dtest=McpFunctionalMysqlTest,McpCatalogMysqlTest,McpSimulatorProtocolTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`。
- 前端：`npm --prefix haizhuo-brain-web run build`，包含 Vue 类型检查与 Vite 生产构建。
- 首次全模块回归结果：50 个测试套件、211 项测试，0 失败、0 错误、0 跳过；前端构建成功。Flyway 提示当前版本尚未正式覆盖 MySQL 8.4（最新声明支持至 8.1），实际 V1–V18 迁移在测试容器中成功；Vite 有既有的大包体积警告，不影响构建通过。

## 实际覆盖

| 场景 | 证据与结论 |
| --- | --- |
| MySQL 迁移与治理 | 临时 MySQL 8.4 实际运行 Flyway V1–V18；登记、发现快照、逐工具批准、能力修订、模型名冲突、连接启停均有 JDBC 集成断言。 |
| MCP 协议 | 本机 HTTP 模拟 Server 与真实 MCP Java SDK 完成 `initialize`、分页 `tools/list`、`tools/call`；两名用户列表分别为 3/1 项。 |
| 身份和资源 | 功能测试通过 JDBC 平台用户仓库取得归一化手机号，由模拟凭证提供者签发短效 HMAC Token；无 Token、签名篡改、过期、错受众均被 Server 拒绝。当前用户可读自己的便笺，跨用户目标资源被逐次拒绝；SDK 对模拟器的初始化认证错误识别为登录失效。 |
| 管理至执行闭环 | 登记→发现→批准→员工绑定→发布→两名用户分别创建 Run→冻结不同工具集合→读取与服务端拒绝，均在 MySQL + HTTP 模拟 Server 上实跑（不含模型推理）。 |
| 写与恢复 | 未确认写工具须确认；功能测试注入已批准的审批事实后，模拟“写入已落地但响应丢失”，平台返回待核查，原操作键可查询到已保存结果；重复写操作键不重复副作用。审批记录本身的 JDBC 状态流由既有后端回归覆盖，未进行浏览器审批操作。 |
| 收窄与漂移 | 远端输入 Schema 改变后旧 Run 调用失败、新 Run 不再展示旧工具；连接停用后准备和执行均失败；再启用不恢复旧绑定。重新发现、审核新工具修订并发布新版员工后，新 Run 可恢复使用。只读工具不得被虚假标记为读，发布包/Run 修订受约束。 |
| API 与前端 | Spring Boot 测试上下文启动；匿名访问 MCP 管理接口受保护。管理员页面具备连接登记、发现、Schema 审核、逐工具批准和启停操作；员工页有拟发布工具契约预览；Vue 类型检查与生产构建通过。 |

## 首次验收时未覆盖与联调门槛（增量状态见文末）

1. **真实凭证**：当前非模拟模式故意拒绝提供 MCP 用户 Token；终端用户凭证的取得、续期、跨平台信任和产品提示须与真实身份系统对接后验收。
2. **真实 Server**：尚未验证真实 MCP Server 的分页、用户过滤、资源级强校验、401/403 业务错误体、写操作效果契约。SDK 不可靠传播部分原始 HTTP 状态；接入时必须针对目标 Server 做协议探针和错误映射，不得把未知写结果当成无副作用失败。
3. **生产运行**：未对既有 MySQL 数据库执行升级，未运行真实模型推理和浏览器人工点击流程。MySQL 8.4 测试容器迁移成功不等于生产升级无风险；前端构建成功不等于交互验收完成。
4. **能力扩展**：资源/知识库、跨 Server 自动同步和任意 Server 通用适配不在本轮范围；Server 语义变更无法仅凭 Schema 自动证明安全，仍需要可信变更契约与人工复核。
5. **管理体验**：现有“发现工具”可验证连接并展示当前管理员所见候选，但尚无独立健康检测、跨快照差异视图和完整的员工发布差异预览；这些不能计为已交付。

结论：模拟环境内的纵向功能切片达到代码级和集成级验收；生产 MCP 接入仍以真实身份及目标 Server 的专门联调为门槛。

## 2026-09-28 增量验收

本节记录首次报告后可独立完成的补测，不改写上方首次验收事实。全模块重新运行 `mvn -gs maven-test-settings.xml -s maven-test-settings.xml -o -ntp -q -pl haizhuo-brain-bootstrap -am test`，使用本机缓存及 Docker Desktop 临时 MySQL 8.4，最终结果为 **55 个测试套件、219 项测试，0 失败、0 错误、0 跳过**。`npm --prefix haizhuo-brain-web run build` 的 Vue 类型检查及构建通过。临时 Maven settings 只用于本机验证，未作为项目配置交付。

- 确定性假模型经真实 AgentScope Harness 注册已发布 Schema，产生外部工具挂起；真实 MCP Java SDK 通过本机 HTTP 调用模拟 Server 读取当前用户便笺，再将返回内容作为工具结果恢复模型，模型接收该内容并完成。轻量测试使用内存 AgentStateStore，直接驱动执行器并送回 ToolResult。假模型须同时提供参数 Map 和原始 JSON，才能通过 AgentScope 2.0.3 的参数校验。
- 独立的临时 MySQL 集成测试在 JDBC AgentStateStore 上实际驱动 `RunExecutionService → ToolExecutionWorker → RunExecutionService`：先落库 `WAITING_TOOL/REQUESTED`，MCP 读取成功后变为 `QUEUED/SUCCEEDED/READY`，恢复后 `SUCCEEDED/DELIVERED`；最终完成事件与模型收到的便笺内容均有断言。这覆盖单进程、真实持久库上的 Worker 交接，但未进行跨进程重启，也未调用真实模型服务。
- 取用户 Token 抛错、返回 `null` 或空白时，网关现在记录 `CREDENTIAL_UNAVAILABLE`，且 MCP 远端未被请求；写调用已经发出而结果不明时，仍记录需核查的 `TOOL_RESULT_UNKNOWN`。网关级定向测试 2 项通过。
- 已新增管理员的持久发现差异视图，按同一连接和当前管理员取最近两次发现；旧快照不能批准，新快照仍可连续批准多个工具。MySQL、API 与前端验证细节见[差异视图增量报告](MCP持久发现差异视图验收报告.md)。
- 使用带既有员工、草稿、发布版本和用户授权数据的临时 MySQL 8.4，从 Flyway V16 升级至 V18；存量数据保持，工具名 claim 正确回填；跨能力重名时 V18 明确失败且历史数据保留。升级演练 2 项通过。**未连接或升级用户现有业务数据库**。

当前仍需外部条件或后续独立切片：真实 MCP Server 与它认可的终端用户 Token 颁发/续期机制（本轮按用户要求暂停）、目标 Server 错误契约及安全行为、真实模型调用、跨进程重启恢复、浏览器人工交互，以及现有业务数据库的升级窗口与备份验证。模拟签名 Token 仅用于测试，非模拟模式继续凭证失败即拒绝调用。Flyway 对 MySQL 8.4 的版本支持警告仍存在，容器演练不消除生产升级风险。

## 2026-09-28 本机演示交互补验

本节记录上述验收后的新增事实，不改写历史结论。为避免触碰原开发库，将数据复制到本机独立 MySQL 8.4 容器：原库仍为 Flyway V16，演示副本迁移至 V18；后端使用演示副本和独立 Redis DB 1。Vue、后端和模拟 MCP Server 分别监听本机 5173、8080、8765 端口。

- 浏览器管理流程实际完成：登记 `demo-mcp`，以当前管理员身份发现 3 个候选工具，仅批准只读的 `private_note_read`，加入“通用助手”草稿、校验通过并发布 v6。写入与写入状态工具未批准。管理页在登记前确实为空，说明启动 Server 不等于完成平台配置。
- 新建独立会话，输入读取本人模拟便笺 `note-a` 的明确请求；当前配置模型实际调用 `demo_private_note_read`，页面展示模拟 Server 返回的 `writer's private note`。持久库中的 Run 为 `SUCCEEDED` 且使用 v6，工具执行为 `SUCCEEDED/DELIVERED`、无错误码。这覆盖浏览器交互和当前配置模型的本机端到端调用，但不等于真实第三方 MCP Server 联调。
- MCP 模拟器支持本机启动时将演示库有效管理员映射为测试写入者，默认固定身份仍用于自动化测试；新增定向协议测试在可配置身份下验证列表差异与跨用户拒绝，3 项通过。模拟 Token 仍只限本机测试。

仍未验证：真实 Server 的身份与错误契约、终端用户凭证取得和续期、跨进程重启恢复、生产数据库升级。本次浏览器操作属于自动交互验收，不冒充用户人工验收。已有 Run 的发布版本保持冻结，新 Run 创建时读取最新发布版本。

## 2026-09-28 会话历史展示缺陷回归

演示副本中会话 `503215c8-db2f-4648-bc12-4be5628131b4` 有 3 个 Run、3 条 `USER_INPUT` 和 3 条 `RUN_COMPLETED`，原页面只显示 2 个气泡。原因是时间线接口将 `RunId` 序列化为 `{value: ...}`，而前端按字符串拼接消息键，导致不同 Run 的消息键碰撞。接口现显式输出字符串 `runId`；前端在旧后端尚运行时兼容旧形状。定向 API 测试 1 项通过，前端类型检查与构建通过；刷新仍连接旧后端的演示页面后，3 轮共 6 条消息均可见。演示后端未在本次重启，新接口契约的进程级 HTTP 验证待下一次启动。当前时间线接口仍有默认最近 100 条事件的窗口，上述回归只覆盖窗口内的多轮消息，不代表无限历史分页已交付。
