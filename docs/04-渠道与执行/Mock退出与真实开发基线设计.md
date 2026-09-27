# 海卓智慧大脑：Mock 退出与真实开发基线设计

| 项目 | 内容 |
| --- | --- |
| 版本 | 0.1 |
| 日期 | 2026-09-27 |
| 状态 | 实施依据；本轮退出 Mock，真实会议室接口待契约确认后接入 |
| 目标 | 移除可运行的会议室 Mock、固定测试身份和未认证管理入口，保留可复用的 Agent 定义与能力装配基础 |

## 1. 已确认的处理范围

1. 关闭固定用户 `1001` 的 Session/Run API，以及使用固定操作者的 Agent 管理 API。真实身份与管理员权限接入前，不对外开放这两类接口。
2. 移除生产路径中的 A-201 房间、模拟监控、模拟预定、`/mock/**` 接口、`meeting-mock` Profile 和专用启动脚本。
3. 删除现有 MySQL 中的 Mock 会议室表及其测试预订数据；同时按明确的原型标识清除关联的 Run、Session、工具审计、能力快照和种子员工/能力数据。通用表结构保留，供后续真实业务使用。
4. 保留 `AgentDefinitionRepository`、JDBC 实现、草稿与版本发布领域服务、`EffectiveCapabilitySetResolver`、能力提供者注册表、AgentScope Toolkit 装配契约等可复用代码。它们在没有真实身份、业务适配器和能力目录项时不自行产生可执行会议室能力。
5. 历史 Flyway V1/V2 已在本机数据库执行，文件保持不变；使用 V3 迁移清理 Mock 数据与表，确保已存在数据库和全新数据库得到相同的最终结构。

本轮不把已有固定会议室适配器改名成“真实适配器”。真实会议室系统的接口、认证方式、资源范围和写操作确认规则必须以目标系统契约重新设计。

## 2. 代码和配置处置

| 类别 | 现状 | 处置 |
| --- | --- | --- |
| 会议室业务与运行入口 | `MeetingRoomController`、`MeetingRoomRunService`、`MeetingRoomToolGateway`、`MeetingRoomSystem` 等把固定身份和 A-201 规则写在链路中 | 退出应用运行路径；真实会议室业务在拿到接口契约后重新建立领域边界 |
| Mock 持久化 | `JdbcMockMeetingRoomSystem`、`JdbcMeetingRoomRunStore` 依赖 `mock_*` 表 | 移除；不保留运行时对 Mock 表的引用 |
| 测试替身 | 内存房间、内存 Run Store 和种子员工仅服务旧 Mock 测试 | 旧业务测试随实现退出；通用能力发布测试改用中性测试数据 |
| AgentScope 通用能力装配 | `AgentScopeToolkitAssembler`、`CapabilityAdapterRegistry`、`RuntimeCapability` | 保留；无提供者时能力解析失败关闭，不凭数据库键名动态加载代码 |
| 会议室能力提供者 | `MeetingRoomRuntimeToolProvider` 只映射 Mock 查询/预定 | 移除；未来真实提供者须按真实动作与资源规则重新实现 |
| 未认证管理 API | `AgentDefinitionManagementController` 使用配置中的 `admin-actor-id` | 关闭 HTTP 入口；管理领域服务和 JDBC 仓储保留，待认证主体接入后再开放 |
| 本地配置与脚本 | `application-meeting-mock.yml`、模板、`run-meeting-mock.ps1` | 删除运行路径中的 Profile、模板与脚本；本地忽略的模型凭据需安全保留供后续开发，不写入 Git |

## 3. 数据库清理方案

新建 `V3__retire_meeting_room_mock.sql`，顺序如下：

1. 只选择 `user_id=1001` 且 `operation_key=CONCAT('run:', run_id)` 的原型 Run；按外键顺序删除其 `tool_invocation_audit`、`run_effective_capability_item`、`run_effective_capability_set`、`agent_run_event` 与 Run。仅删除该测试用户已无 Run 引用的 Session。
2. 将种子员工的 `current_published_version_id` 置空，再删除其发布能力绑定、发布版本、草稿能力绑定和草稿。
3. 删除 Mock 用户能力授权、会议室能力修订、会议室能力定义及种子员工。
4. 删除 `mock_meeting_booking`、`mock_meeting_room` 表。`agent_run` 的旧 `booking_id`、`operation_key` 字段暂时保留但不再由代码使用；它们属于通用表的历史结构，待真实 Run 模型确定后单独迁移。
5. 保留通用 `agent_session`、`agent_run`、`agent_run_event`、员工定义、能力目录、Run 有效能力快照与审计的表结构。清理后的库不再包含可执行的 Mock 种子。

迁移前对当前本机数据库进行了只读核对：9 条 Run 均属于用户 1001 且使用原型 `run:<RunId>` 操作键，10 条 Session 均属于用户 1001；只有种子员工 1 和两项会议室能力，没有其他员工或能力。迁移仍使用上述条件删除，避免将通用表中未来可能出现的其他记录一并清空。

V3 是对本地原型数据库的清理迁移。执行前停止旧 `meeting-mock` 进程，避免它在表删除期间继续接收请求。迁移完成后不再启动旧 Profile。历史测试报告保留为历史验收记录，并明确其运行方式已经退出。

## 4. 真实开发入口的门槛

- **身份**：Session/Run 从可信认证上下文取得 UserId，管理 API 从管理员认证上下文取得 actorId；请求体不能自报两者。
- **真实会议室系统**：明确房间查询、空闲判断、预订、查询预订结果、幂等键、超时与重试、用户或服务凭据、监控数据来源和资源权限。
- **写操作控制**：重新评审真实预订是否需要用户确认或审批；Mock 免确认的结论不能沿用。
- **能力登记**：真实适配器通过白名单代码注册，数据库登记精确能力修订后才能发布；缺少适配器或授权时失败关闭。
- **存储**：真实业务不能复用 `mock_*` 表。若需保存外部预订状态，应按真实系统语义增加独立迁移。

## 5. 验收标准

1. 生产源码与可启动 Profile 不再包含 `meeting-mock`、`/mock/**`、固定 A-201 Mock 服务或固定用户/操作者 1001 的运行入口。
2. 未认证的 Session/Run 和管理 API 均不可调用；应用启动不会自动生成 Mock 预订或装配 Mock 会议室工具。
3. Flyway V1/V2 保持原样，V3 在本地 MySQL 成功执行；最终无 `mock_meeting_room`、`mock_meeting_booking` 表和原型测试数据。
4. Java 全模块编译、保留的通用领域与 JDBC 测试通过；新增测试验证旧 HTTP 路径不再开放。
5. 文档入口说明当前真实开发基线，旧 Mock 设计与测试报告标记为历史记录。
