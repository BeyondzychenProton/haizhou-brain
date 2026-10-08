# SPEC-13 自治团队唤醒、收尾与恢复

状态：正常任务唤醒、任务板收敛、Team 完成屏障、成员 CHILD 持久事件与保护性 RECOVERY_REQUIRED 路径已有实现；成功路径有原生 Harness 端到端探针，SPEC-10 核心工作项数据层已接入。取消/超时/晚到、租约丢失、读板失败和真实 JVM 故障矩阵，以及 Team 与工作项组合的 MySQL 端到端路径尚未验证；自治 profile 保持关闭。任务：C11、C12。依赖：[SPEC-12](SPEC-12-自治团队装配与权限.md)、SPEC-00 T07–T11。设计依据：[详细设计](../数字员工技能知识与只读协作改造详细设计.md) §8.7、§13.5、§17.5。

## 唤醒与互斥

- 使用原生 WakeupDispatcher/MessageBus/SessionTurnGate；平台只解析可信绑定、记录活动订阅与轮次预算。
- 原生跳过正在运行成员，最后 inbox drain 与 gate 释放间消息可遗漏；收尾读 inbox 并合并补发原生 wakeup，受总轮次/截止时间限制，不自写无限 Agent 循环。
- acquire/等待/执行可取消；同成员互斥，不同 worker 按批准并行数执行。
- 原生任务转换已提交但通知失败，按 taskId/version/结果核查并幂等補投影/提示 lead；不能重新执行已完成任务。

## 完成屏障

- 成员一轮 AgentResult、worker 普通文本、completeTeam 元数据都不是根完成。
- 收尾先禁止新动作/激活，等待实际活动成员退出，成功读取板/任务/完整结果；读取异常不等同“无剩余工作”。
- 必需任务完成且资料检查合格，或按批准策略明确部分交付后，run_readonly_team 返回安全完整汇总，外层 ROOT 继续推理/交付。
- 原生正常 turn end 的 settleOwnedTasks、异常/cancel 不同行为以探针记录；没有 completeTask 的任务不自动成功。

## 取消与故障

- 用户取消、超时、父 lease 失效：关闭 dispatcher，取消全部活动订阅，待实际退出再释放 gate lease/注销映射。关闭 dispatcher 本身不算成员停止。
- 晚到旧 attempt/generation 不能改变现投影、结算或出站意图。
- JVM 失效、queue drain 后未明启动、父工具消费不明：RECOVERY_REQUIRED 占槽，旧 Team/原生 state/结果留存并只读核查。
- 首期同旧 Run 自动/人工续跑关闭。确认旧执行停止/安全结束后，新请求使用全新 Team/name/成员 sid；成功旧任务仅作为授权资料，不重新写旧消息 namespace。
- 父原生状态是否可继续也不明时，新 Session 明确资料输入，不能手改 AgentState 消费记录。

## 验收

复现 T04、T07–T11：晚 inbox/重复 wakeup、提前 lead result、pending/failed 任务、读板错误、通知失败、取消/超时、JVM 在关键位置退出。断言无根提前完成、无并发同成员、无丢失可核查状态、无旧 fence 交付。

真实 MySQL 与实际进程故障报告是自治开放门槛。无法证明停止时保持核查，不以“租约已到期”替代证据。未来群聊只沿用参与者与任务生命周期分离约束，本 SPEC 不引入群聊运行机制。
