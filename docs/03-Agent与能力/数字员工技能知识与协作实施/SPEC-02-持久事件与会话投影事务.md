# SPEC-02 持久事件与会话投影事务

## 本轮实施边界（2026-10-08）

统一所有 Run/工具/审批/引导写入；锁顺序为 Session → Run → attempt → 工具/审批。认领先读取候选，再按该顺序锁定并重查，不在候选查询先锁工具或 attempt。Appender 必须加入实际 Spring JDBC 事务，缺少事务直接拒绝。测试通过真实事务代理执行，不能以手工 new 的无事务调用证明原子性。

Session 保存独立 next_event_cursor；事实行保存已分配游标，投影裁剪和重建都不能回退编号。升级时保留已有编号；无法区分曾被裁剪和从未投影的旧 Run 事实，统一追加高位游标并标记 historicalProjection，不能声称恢复了原编号。迁移期间停止旧 Worker 写入。

读面按扫描游标分页、服务端过滤 INTERNAL，快照使用同一个 REPEATABLE_READ 事务。V1/V2 使用相同持久 SSE ID；新元数据只在显式 format=v2 返回。

状态：实现代码与事务/游标回归已接入；V20–V30 全量 MySQL 迁移已在本轮 Testcontainers 验证；并发竞争与进程故障注入仍待完成。任务：A2。依赖：[SPEC-01](SPEC-01-执行来源与根子事件隔离.md)。设计依据：[详细设计](../数字员工技能知识与只读协作改造详细设计.md) §10、§11、§14.3、§16.2。

本轮已加入 Run 事实与 Session 投影共事务 appender、单调 Session cursor、内部事件过滤后的扫描游标、过期 SSE 控制事件、快照 API 和显式 V2 安全元数据。尚未以真实 MySQL 执行 V20 数据回填，也未重跑本轮定向事务回归；不能据此开放新协作 profile。

## 目标与原生边界

继续使用 native streamEvents 产生执行事件。平台的 Run 事实表承担长期审计与补读，Session 表承担跨 Run 的可重建读投影；两者在同一平台数据库事务写入。它们不能替代 AgentState，也不能依赖原生有限回放窗口恢复长期历史。

## 数据与写入契约

- 新增统一 `JdbcRunEventAppender`，使用当前业务事务，禁止 REQUIRES_NEW 分拆；Run、Session、工具执行、审批全部经此入口。
- 校验当前 Run attempt/fence 及事件可见性；存 eventSchemaVersion、来源、payload、结果引用。敏感原始参数不进入通用 payload。
- 在锁定 Session 的事务中分配单调 session_cursor，持久到 RunEvent 和 SessionEvent；Run sequenceNo 仍在各 Run 内有序。
- eventId/业务幂等键避免同一事实重复投影。投影失败使该入口的业务变更与 Run 事件全部回滚。
- INTERNAL 可以占用持久游标但不返回普通用户；扫描分页返回 nextCursor，过滤后仍可前进。
- SSE ID 继续使用 sessionCursor；瞬时 text delta 用 streamOffset，不占持久 cursor、不保证 token 回放。
- V1 字段与载荷继续可读；format=v2 新增来源/结果引用，不重编号旧 SSE ID。

## 实施位置

基础设施 `JdbcRunExecutionStore`、`JdbcSessionEventProjector`、`JdbcToolExecutionRepository`、`JdbcToolApprovalRepository` 及 Session/Run 其他 append 入口；平台事件模型；API Session/Run events/stream。

先从图和源码列齐全部写入路径及事务代理，再统一入口。新增迁移加入游标/来源字段与索引；历史只写 Run 的事件按设计补投影，使用新高位游标并记录原发生时间与历史补投影标识。

## 验收与失败处理

| 场景 | 预期 |
| --- | --- |
| Run、工具、审批各入口注入投影写失败 | 业务状态、RunEvent、SessionEvent 一起回滚 |
| 两 Run 并发写同 Session | 游标唯一递增，sequenceNo 各自有序，无越过快照的事件 |
| 相同业务幂等键重复写 | 一份事实和一份投影，不重复分配用户可见事件 |
| INTERNAL 连续页 | 内容不外泄，nextCursor 前进，用户可见事件可继续读取 |
| V1 历史、断线 Last-Event-ID | 保持旧编号，补读跨 Run，不因某 Run 完成关闭 Session 流 |
| 升级回填并发 | 新旧分配器不并行写；cursor 最大值与唯一键核对通过 |

运行基础设施定向事务测试、API 读面测试，并在真实 MySQL 验证回填和并发。H2 通过不替代 MySQL 锁/索引/字符集验证。
