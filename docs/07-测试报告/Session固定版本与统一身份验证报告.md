# Session 固定版本与统一身份验证报告

日期：2026-10-07。基线：`b317f8a3b5458b2cdc441f7d8fcdbea552f5831b`。AgentScope Java 2.0.3、Java 17、Maven 3.9.11，Vue 3 前端。

## 已实现

- 新会话创建时固定员工定义版本；Run 和工具视图始终根据该版本构建，发布只影响新会话。
- 新会话业务 Session ID 直接传入 Harness，Binding/Bridge 不再查询、写入或构建。框架 JDBC StateStore 继续拥有 Agent 状态。
- V19 只增加业务元数据：存量保持旧键兼容并固定最近 Run 的版本；无 Run 的旧会话首次执行原子固定版本，不覆盖并发赢家。
- 继续执行/工具恢复的 Runtime 状态缺失时失败，不创建空记忆会话。
- API 返回固定版本字段；渠道创建与等待消息提升复用同一版本规则。

## 已执行验证

使用当前环境安装的 Maven 3.9.11 执行以下命令（测试 JVM 附加 Mockito 5.17.0 的 `-javaagent`，解决环境不允许 JVM 自附加的问题）：

```bash
mvn -ntp -pl haizhuo-brain-bootstrap -am test \
  '-Dtest=*Test,!*MysqlTest' -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:/root/.m2/repository/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar
npm --prefix haizhuo-brain-web ci
npm --prefix haizhuo-brain-web run build
git diff --check
```

后端共 232 个测试通过，0 失败、0 错误；覆盖到 bootstrap，`ApplicationContextSmokeTest` 三个用例通过。前端 `vue-tsc --noEmit` 和 Vite 生产构建通过；既有大 bundle 警告仍存在。

| 用例 | 结果 |
| --- | --- |
| SessionFixedVersionTest | 3 个通过：旧会话版本不随发布变化、新会话使用新版本、属主隔离及旧会话只固定一次 |
| ChannelFixedSessionVersionTest | 通过：V1 会话等待消息在发布 V2 后仍按 V1 提升，新渠道会话使用 V2 |
| SessionFixedIdentityMigrationTest | 通过：执行真实 V19 脚本，固定最近 Run 版本，AgentState 原键和载荷不变 |
| JdbcSessionRunStoreTest | 12 个通过，包含固定版本往返、版本不匹配拒绝、属主限定的首次固定 |
| RunExecutionServiceTest | 直连会话不访问 Bridge Snapshot；已有运行历史时要求 Runtime 状态 |
| AgentScopeRuntimeTest | 5 个通过，包含缺失状态时不构建/调用 Agent |
| JdbcAgentStateStoreVerificationTest | 2 个通过：关闭并重建 Harness/Store 后恢复外部工具结果；用户/会话状态隔离（H2） |
| SessionControllerTest | 2 个通过，包含固定版本字段返回 |

## 未验证与保留边界

- 完整测试最初运行到 MySQL Testcontainers 用例时因没有 Docker 失败。以上最终命令明确排除了 `*MysqlTest`，H2 不代表 MySQL 真机通过。
- MySQL Flyway 升级、真实模型/IM 提供方、生产部署和浏览器业务操作未在本次执行。
- 本次没有自动迁移旧 AgentState 槽、删除旧 Binding/Bridge 表；旧路径只为存量会话兼容保留。
- Run/审批/投递和最终展示事件仍由平台保留，不作为 AgentScope 的会话记忆恢复源。
- StateStore 不替代 Workspace 文件/沙箱持久化；该文件面在当前项目仍未接线。
- 本次不改变既有租约失效后的至少一次重试。Runtime 已保存而业务 Run 尚未结算的崩溃、旧 Worker 回写，以及跨业务库/框架状态的原子性仍需专项恢复测试，不能宣称精确一次执行或框架状态已具备平台 fence。

## 上线方式

先在 MySQL 空闲窗口验证 V19 与现有恢复链路，然后发布应用；不得将存量 `legacy_runtime` 批量改为 false。新建 Session 自动走直连 ID，存量会话仍走原状态键。员工版本升级通过新建 Session 完成。

回滚应用前应排空活动 Run，保留新增列和已有 AgentState 数据。旧应用不能识别新直连状态槽，不能直接把这些 Session 当旧 Binding 会话继续执行；回滚期间应暂停新直连会话执行，或保持可识别新身份的版本。
