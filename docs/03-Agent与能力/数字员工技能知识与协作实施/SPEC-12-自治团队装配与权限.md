# SPEC-12 自治团队装配与权限

状态：原生 Team 装配、角色动作白名单、确定性任务唤醒与取消保护已通过 Harness 探针；持久成员生命周期、动作预算、事件安全可见性及完成后的迟到事件拒绝已通过 MySQL 回归。Team 与 workItem 完整业务组合及自治故障矩阵未验收，自治 profile 仍关闭。任务：C10。依赖：SPEC-00 T01–T06、08–10。设计依据：[详细设计](../数字员工技能与只读协作改造详细设计.md) §8.7、§9、§10、§13.5。

## 数据流与原生复用

业务 ROOT Harness 调用薄工具 run_readonly_team；工具为本 Run 预构建 lead/worker Harness。使用原生 LocalTeamClient、TeamTool、TeamsMiddleware、MessageBus、任务板和成员 gate。lead/worker 全部是该业务 Run 的 CHILD，外层 ROOT 才形成正式答案。

平台没有 Team task Worker 队列。一个活 Team 由唯一 owner Worker 持有，一个 client 实例由所有成员共用；createTeam 只建立板元数据，不负责启动成员。

## 身份与持久关联

- TeamExecutionId/generation 产生全局唯一 `hb-{id}-g{generation}` teamName/namespace；不让多个 LocalTeamClient 写旧同一 namespace。
- 新增 platform_run_team_execution/member_binding 仅保存父身份、固定版本、native sid、原生板关联、预算与生命周期；原生任务板仍是任务状态事实。
- 成员独立 task/Team 会话，不混入直接专家槽。缓存只保留不可变模板，TeamClient/context/middleware 是 Run scoped。
- 唤醒目标从绑定恢复真实 user、Run、成员与版本；空 userId 不成为匿名身份，伪造 sid/成员被拒。
- 首期一个 Run 最多 Team 1、worker 2、task 6、总轮次 12、并行 worker 2、每轮 4 步、180 秒、自动重启 0；具体输入可收紧，不能扩大。
- 模型主动消息预算 20，broadcast 按实际收件人数扣减；额度跨父 Run attempt 保留。原生任务内部通知由任务上限约束并单独观测，不能声称 sendMessage 外层计数覆盖内部自调用。

## 动作与授权

availableActions 必须非空。lead 仅获批准 list/create/assign/send/broadcast/completeTeam；worker 仅 list/claim/complete/fail/send。映射原生别名后校验，不让默认空名单放行全部动作。

每个动作检查父有效 Scope、acting 成员、Team/task owner、合法目标与预算；任务 owner/CAS 使用原生能力，不信任模型 payload 的 owner。首期禁用 unsupported spawnMember/shutdownMember/计划审批等控制面动作。

成员全部只读 leaf，不含业务 TOOL/MCP、Shell、递归、动态部署或后台/expose。消息 INTERNAL，普通用户仅看安全任务状态与结果引用，不暴露原始 mailbox。

## 验收

T01–T06 实际证明共用 client/唯一名字避免序号覆盖、跨 namespace 注册串话；真实 native task board 能创建/分配/认领并通过 CAS 防止重复 owner。

动作别名、伪造 owner/目标、其他 Run 任务、超限消息/任务、租约失效全部拒绝。所有成员实际工具集和事件 CHILD 归属可观察。完成这些仍只算装配切片；自治 profile 要等待 SPEC-13 收尾/故障门槛。
