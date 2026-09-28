# MCP 持久发现差异视图验收报告

日期：2026-09-28。范围：MCP 管理页按当前管理员与连接读取最近两次已保存发现、比较工具声明，并确保历史快照不能作为批准依据。此前模拟闭环的验收边界见[原报告](工具与MCP模拟闭环功能验收报告.md)；本报告只记录本次增量。

## 实现与验证

- 沿用 V17 的 `mcp_discovery_snapshot`，按同一 `connection_id + discovered_by` 的快照 ID 倒序取两条；未满两条时不虚构变更。展示时间、连接修订、工具数量，以及新增、消失、说明、输入/输出 Schema、只读提示的差异。历史读取不要求连接仍启用，因此停用后可核查记录。
- 批准先校验快照属于同一连接和管理员，且仍是最新、连接修订仍有效；批准事务持有连接行锁后再次检查最新快照。保存新发现也持有该行锁。相同最新快照可依次批准多个工具，新的发现保存后旧快照不可批准。
- `mvn -ntp -q -pl haizhuo-brain-infrastructure -am '-Dtest=McpCatalogMysqlTest,McpFunctionalMysqlTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：Testcontainers MySQL 8.4 实际执行 V1–V18 和 JDBC/模拟 Server 用例，3 项通过，0 失败。新增用例覆盖重建仓库对象后读取、最近两次选择、跨管理员与跨连接隔离、四项元数据变化、工具新增/消失、停用后历史读取、连接修订失效、旧快照拒批；既有功能用例确认同一快照连续批准读/写工具正常。
- `mvn -ntp -q -pl haizhuo-brain-api -am '-Dtest=McpAdministrationControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`：1 项通过，确认差异接口使用认证主体的用户 ID。`npm --prefix haizhuo-brain-web run build`：Vue 类型检查与生产构建通过。

## 限制

MySQL 验证使用临时测试容器，尚未在含历史数据的既有数据库执行升级。页面已经构建，但浏览器人工操作尚未进行；真实身份系统、真实 MCP Server 与模型推理仍需要各自的对接环境。本次未修改 Flyway 迁移，也未把历史候选转为已批准能力。
