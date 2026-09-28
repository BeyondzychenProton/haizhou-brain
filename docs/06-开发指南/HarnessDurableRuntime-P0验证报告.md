# Harness Durable Runtime P0 验证报告

| 项目 | 结果 |
| --- | --- |
| 验证日期 | 2026-09-27（H2 补验）、2026-09-28（真机 MySQL 补验 + 启动故障修复） |
| AgentScope 版本 | 2.0.3 |
| 测试代码 | `haizhuo-brain-runtime-agentscope/src/test/java/com/haizhuo/brain/runtime/agentscope/harness/HarnessP0IsolationTest.java`、`.../harness/JdbcAgentStateStoreVerificationTest.java`、`haizhuo-brain-bootstrap/src/test/java/com/haizhuo/brain/bootstrap/AgentStateStoreSchemaMigrationTest.java`、`.../MysqlMigrationProbe.java`（真机，手工触发） |
| 执行命令 | `mvn -ntp -pl haizhuo-brain-runtime-agentscope -am test`；真机 `mvn -o -pl haizhuo-brain-bootstrap test -Dtest=MysqlMigrationProbe -Dmysql.probe.password=<密码>` |
| 本轮结果 | P0 隔离探针 7 个通过；JDBC 状态存储验证 2 个通过（H2）；**真机 MySQL 8.0.45 验证 2 个通过**（含"挂起→重建→恢复"P0 准入条件，详见文末章节）；`agentscope_sessions` 建表 DDL 已入 Flyway V13 |

## 已验证事实

| P0 项目 | 输入与存储 | 输出 | 结论 |
| --- | --- | --- | --- |
| 多用户、多 Session 隔离 | 同一 `HarnessAgent` 并发调用 `user-a/hs-a` 与 `user-b/hs-a`，使用 `InMemoryAgentStateStore` | 后续 `user-a/hs-a` 请求含自己的历史，不含 `user-b` 文本 | 通过。框架状态寻址至少包含 user 与 session。 |
| 版本状态隔离 | 同一用户调用 `hs-v1`、`hs-v2` | `hs-v2` 模型输入不含 `hs-v1` 历史 | 通过。平台必须以版本作用域的 `harnessSessionKey` 选择状态槽。 |
| Runtime Namespace 隔离 | `InMemoryStore` + `RemoteFilesystemSpec`，`workspaceRuntimeKey=ws-a/ws-b` | `ws-a` 写入的文件无法由 `ws-b` 读取 | 通过。AgentState 与 Workspace Runtime Namespace 可以独立隔离。 |
| 调用级 Tool View | 静态 Schema `A/B/C`，同一 Harness 的两次调用分别传入 `A/B`、`A/C` | 模型两次只看到对应 Schema 集合 | 通过。调用级 Middleware 可构造 Tool View，不需要重建 Harness。 |
| External Tool 挂起 | SchemaOnly `meeting.reserve`，确定性模型发出带参数的 ToolUse | 收到 `RequireExternalExecutionEvent`，含 `toolUseId=tool-use-1`、工具名和 `{room:A-101}` | 通过。企业动作可以离开 Harness 执行。 |
| 文件状态恢复 | 第一个 Harness 使用 `JsonFileAgentStateStore` 挂起，关闭后以同一 store、新 Harness、相同 user/session 提交 `ToolResultBlock` | 正常得到最终回答 | 通过，但这只是本地文件 Store 验证。 |
| 重复恢复 | 同一 ToolResultBlock 重复提交 | 模型输入累计出现 3 个相同结果块 | 框架不会消除重复交付；P1 必须实现 `ExternalToolResumeGuard`。 |

## 安全与边界结论

1. P0 统一关闭 Memory hooks、Memory tools、Filesystem tools、Shell、Workspace context、Subagent 和 Transcript，避免默认能力扩大验证范围。
2. `harnessSessionKey` 只能承载 AgentState 槽位；`workspaceRuntimeKey` 是独立的文件命名空间键。
3. `SchemaOnlyTool` 只产生外部执行请求，不在 Harness 内执行企业副作用。
4. 重复 ToolResult 会污染模型上下文，因此 Platform 在恢复前需要按 `(runId, toolUseId)` 做一次性投递保护，并持久化投递状态。

## JDBC 状态存储验证（2026-09-27 补验，已通过）

`JdbcAgentStateStore` 的"挂起 → 关闭 → 重建 → ToolResult 恢复"链路已在
`haizhuo-brain-runtime-agentscope/src/test/java/com/haizhuo/brain/runtime/agentscope/harness/JdbcAgentStateStoreVerificationTest.java`
补验通过（2 个用例）：

| 验证项 | 方法 | 结果 |
| --- | --- | --- |
| 跨实例恢复 | H2 + 官方 `H2Dialect`（与 `MysqlDialect` 同源于 extensions-jdbc 方言体系），首个 Harness 挂起并 close 后，以**全新 Store 实例 + 全新 Harness** 在同一库提交 ToolResultBlock | 通过。恢复后直接得到最终回答，且首次模型调用未重放（模型请求计数=2）。 |
| 会话槽位隔离 | 同一 JDBC Store 内分别写入 `user-a/hs-a` | 通过。`exists(user-a, hs-b)`、`exists(user-b, hs-a)` 均为 false，状态按 (userId, harnessSessionKey) 寻址。 |

环境差异说明（如实记录）：

1. 验证用 H2 内存库 + `H2Dialect`；生产 wiring（`AgentRuntimeConfiguration`）用 MySQL + `MysqlDialect`，二者同为 2.0.3 官方方言，构造形态一致（`new JdbcAgentStateStore(dataSource, dialect)`）。
2. H2 的 `INFORMATION_SCHEMA.TABLES.TABLE_SCHEMA` 存 `public`、而 `DATABASE()` 返回库名，方言的 `sessionStateCheckTableExists`（`TABLE_SCHEMA = DATABASE()`）在 H2 上恒不成立，故验证环境必须用 3 参构造 `createIfNotExist=true` 让 Store 自建表；生产 MySQL 两侧相等，使用 2 参构造 + 预建表（DDL 已固化为 Flyway `V13__agentscope_session_state.sql`，见下节）。
3. 并发版本冲突（`saveIfVersion`/`getVersioned`）尚未做专项试验；平台侧的 fencing 已由 `platform_run_execution_attempt.fence_token` 承担（见开发记录 §13.6 测试），AgentStateStore 版本能力作为 P1 增强候选。

## 启动故障复盘：`agentscope_sessions` 缺失（2026-09-28 已闭环）

真机以 `local` profile（`run-worker.enabled=true`）启动时整个 bean 图装配失败：

```
Caused by: java.lang.IllegalStateException: Table does not exist: agentscope_sessions.
Use createIfNotExist=true to auto-create.
    at io.agentscope.extensions.jdbc.state.JdbcAgentStateStore.verifyTableExists(...)
    at com.haizhuo.brain.bootstrap.configuration.AgentRuntimeConfiguration.agentStateStore(...)
```

根因：`JdbcAgentStateStore` 在**构造器内**校验会话表，缺失即抛异常；而该表此前没有任何 Flyway
迁移脚本（原属上节遗留项 2），只有手工在库里建表才不会崩。修复：

| 处置 | 说明 |
| --- | --- |
| 新增 `V13__agentscope_session_state.sql` | 列名/类型/主键取自 `MysqlDialect#sessionStateCreateTableDdls()` 实跑输出（AgentScope 2.0.3），字符集沿用本项目惯例 `utf8mb4_0900_ai_ci`。只建 `agentscope_sessions`——`agentscope_store`/`agentscope_snapshots` 对应的 workspace/snapshot 面尚未接线，暂无表。 |
| 坚持 2 参构造（严格模式） | 不改用 `createIfNotExist=true` 掩盖未迁移的库；DDL 归 Flyway 管，启动失败即真实故障信号。 |
| 新增 `AgentStateStoreSchemaMigrationTest`（bootstrap，2 用例） | ①静态比对：迁移脚本列名集合 + 主键必须覆盖方言 DDL（升级 AgentScope 时先红）；②动态验证：在 H2 MODE=MySQL 上跑一遍迁移脚本，再让 Store 读写/删除，证明列类型与主键满足方言 SQL。 |

## 真机 MySQL 验证（2026-09-28，已通过）

`MysqlMigrationProbe`（bootstrap，手工触发，需 `-Dmysql.probe.password`）在一次性 scratch 库
`haizhuo_brain_v13probe` 上跑完整链路，跑完即删：

```
[probe] MySQL VERSION() = 8.0.45
[probe] flyway executed=13, success=true, target=13
[probe] strict 2-arg JdbcAgentStateStore constructed OK
[probe] store roundtrip on real MySQL OK
[probe] harness suspend->restart->resume on real MySQL OK
```

| 验证项 | 结果 |
| --- | --- |
| V1–V13 全量迁移在 MySQL 8.0.45 上应用 | 通过。`executed=13, success=true, target=13`（V13 即本次新增的 `agentscope_sessions`）。 |
| 严格 2 参构造 `new JdbcAgentStateStore(ds, new MysqlDialect())` | 通过——这正是真机启动崩掉的那一句。 |
| 表结构 | 通过。`SHOW CREATE TABLE` 与方言 DDL 一致（PK `(session_id,state_key,item_index)`、`state_data longtext`、`version bigint`），字符集 `utf8mb4_0900_ai_ci`。 |
| 状态读写往返 | 通过。`save/get/listSessionIds/delete` 均按 `(userId, sessionId)` 生效。 |
| **P0 准入条件**：Harness 挂起 → close → 全新 Store 实例 + 全新 Harness → 提交 ToolResult | 通过。得到最终回答，且模型请求计数=2（未重放首次调用）。 |

复现命令（本机 MySQL 8.0.45）：

```
mvn -o -pl haizhuo-brain-bootstrap test -Dtest=MysqlMigrationProbe -Dmysql.probe.password=<密码>
```

> 注：`application-local.yml` 里 `root` 的密码是空串，而本机 MySQL 8.0.45 的 root 需要密码
> （空密码报 `Access denied`）。本次真机验证显式传入密码；若换环境忘记配置，症状是
> `RuntimeException: Failed to check table existence`（Store 把连接异常也归到这一步），
> 与"表缺失"的 `IllegalStateException: Table does not exist ...` 不同，据此可快速分流。

## 尚未完成，不能宣称完整 P0 的项目

1. **durable `BaseStore`（Workspace 文件面）**：`RemoteFilesystemSpec` 仅自带 `InMemoryStore`，规格 §22 设想的 efs 构造器在 2.0.3 不存在；当前 wiring 传 `filesystemSpec=null`（关闭定义工作区文件面），P1 需自研 JDBC/BaseStore 实现或等框架后续版本。

> 已闭环：原遗留项 "MySQL 实库跨实例验证"（见上节）、"agentscope_sessions 建表 DDL 未入 Flyway"
> （见下节启动故障复盘）。
