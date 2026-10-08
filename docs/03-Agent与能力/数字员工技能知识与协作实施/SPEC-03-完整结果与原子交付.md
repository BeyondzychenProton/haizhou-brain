# SPEC-03 完整结果与原子交付

## 本轮实施边界（2026-10-08）

根完成唯一入口在持久执行仓储内：锁与 fence/租约/状态校验 → 完整结果 → 消费标记 → 终态与事件/投影 → 冻结入站路由的交付意图，全部同事务。服务层使用 COMMITTED/STALE，不再在完成后另开入队事务。正文上限 1 MiB（UTF-8）；摘要限长不影响完整正文。旧历史仅有摘要时明确 legacySummary。

复用现有 Delivery Worker，outbox 记录 resultId 并在发送前读取完整正文。SENDING 租约过期只转 UNCERTAIN，禁止自动重发。根 result、独立资料及以后 invocation 结果使用不同唯一键；本轮不虚构尚未实施的专家 invocation API。

状态：完整结果、引用与 Outbox 原子交付代码及定向回归已接入；V21 迁移随 V20–V30 全量迁移在本轮 MySQL 验证；故障注入和提供方真实送达仍待完成。任务：A3。依赖：[SPEC-02](SPEC-02-持久事件与会话投影事务.md)。设计依据：[详细设计](../数字员工技能知识与只读协作改造详细设计.md) §12、§13.1、§14。

本轮已加入完整结果表、根完成单事务写入、Result API、会话/Run 事件中的受限结果引用，以及 outbox 完整正文读取和过期 SENDING 转 UNCERTAIN。真实渠道送达、MySQL 原子性与超大正文故障场景尚未验收。

## 现状与目标

当前完成事件、投影和 outbox 正文有 4000 字符截断，RunExecutionService 不检查 complete 的 boolean，随后分事务尝试入队。目标是完整正文有独立事实存储，根结算、工具结果消费记录与渠道交付意图在同一平台事务提交。

## 契约与数据

- 新增 platform_agent_result：resultId、runId、kind、来源/调用关联、媒体类型、完整正文/合法载荷引用、内容哈希、长度、schemaVersion、安全可见性与检查元数据。
- ROOT_FINAL、子 invocation 结果和 RUN_MATERIAL 使用独立唯一键；子结果不会抢占根 final 键。
- Run 事件/Session 投影保留安全摘要和 resultId；outbox 引用完整结果，不以截断摘要交付。
- `complete` 返回明确 COMMITTED/STALE。事务核对 attempt/fence、Run 状态、等待结果；仅 COMMITTED 可产生唯一交付意图。
- 同一事务：结果 → Run 终态 → 工具消费标记 → Run/Session 事件 → 使用冻结入站路由的 outbox。任一步失败全部回滚。
- 发送由现有 Delivery Worker 承担；Run 完成与提供方送达分别展示。不能把模型输出、stream 关闭或 outbox 创建称作送达成功。
- 超过批准大小返回 RESULT_SIZE_EXCEEDED 或明确文件交付，不截断后报告成功。旧历史不能恢复的截断内容标注旧摘要。

## 改动入口

RunExecutionStore/JdbcRunExecutionStore、RunExecutionService、Channel ReplyEnqueuer/outbox、结果仓储、SessionController 结果读取；新增迁移，不修改旧事件表迁移。

API：根 `/api/v1/sessions/runs/{runId}/result`，子调用 result 路径按详细设计 §14；身份来自登录主体，验证 Run 属主、关联和 visibility。冻结路由不从结果文本或实时页面选择推断。

## 验收

1. 超过 4000 字符（含中文/emoji）的正文从模型结果、持久结果 API、刷新页面和 outbox 完整往返，哈希一致。
2. STALE/重复完成/已取消/旧 fence 不提交根结果、不投递；重复 COMMITTED 路径仅一个意图。
3. 结果、Run、工具消费、事件、outbox 各步骤注入故障，验证事务回滚与后续查询一致。
4. 提供方超时处于“不确定送达”，按现有核查流程处理，不盲目重新发送。
5. 真实 MySQL 验证并发完成和进程退出；明确 AgentState 与平台消费标记仍不是同一事务，交给 SPEC-04 核查。
