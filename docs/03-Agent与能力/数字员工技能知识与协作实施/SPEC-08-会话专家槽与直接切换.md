# SPEC-08 会话专家槽与直接切换

状态：角色槽持久化、直接目标冻结、A/B/A 受理与服务端读面已实现并通过平台测试；V27 随 V1–V33 全量迁移及后端全量回归通过。跨重启真实 Harness 私有状态隔离及浏览器切换验收仍待完成。任务：C7、C8。依赖：SPEC-01–04、06、07。设计依据：[详细设计](../数字员工技能与只读协作改造详细设计.md) §3.1–3.7、§5、§6、§14。

## 场景与原生边界

同一业务 Session 内直接问 A → B → A，保留每个专家的私有对话/文件。通过原生 Harness 与 AgentStateStore 的不同可信会话键隔离，不启用 exposeSubagent，不把之前委派产生的任务子实例当作稳定直接角色槽。

## 数据与受理

- Session 固定整个团队发布版本；新增 `(sessionId, roleId)` 唯一角色槽，保存固定成员版本、nativeSessionKey/workspaceRuntimeKey、安全展示标识。
- 原协调者直接键按既有契约保留；新角色键服务端生成。AgentExecutionRequest 引入明确直接/角色槽/legacy 身份模式，不用伪造 bridgeSnapshotHash 绕过当前直接 ID 相等校验。
- Run 冻结团队所有者与实际 executor、targetRoleId、mode 和 references；用户选专家在该 Run 为 ROOT。
- 请求选择只影响下一条提交，活动、审批等待、排队 Run 不变；保持每 Session 一个业务根 Run 的现有受理/提升队列。
- references 只能引用同属主且可见的完整结果；明确共享资料，不合并各角色内部记忆。
- 新身份映射持久化；重启后 A/B 键与版本不变。旧 Session 不升级成团队。

## API/实施

新增 GET `/api/v1/sessions/{sessionId}/roles`；现有 createRun 扩展可空 targetRoleId/referencedResultIds、direct/collaborative/autonomous 模式。响应显示实际 executor，body 不接收 nativeSessionId、userId 或任意成员版本。

同步平台 Session/Run 受理、渠道默认目标/提升、runtime context/state exists 检查、guidance、工具等待、观测与查询读面。渠道初期默认协调者，文本中的 @ 名字不授予可信目标。

## 验收

1. A 记住合成私密标识，B 不见；切回 A 保留历史。双用户/版本/重启状态与文件都隔离。
2. A 是直接 ROOT 时正常结算；A 被委派为 CHILD 时不能结算父 Run。
3. 活动/排队/审批等待时切换选择，已有目标和 frozen references 不变。
4. 非团队角色、停用成员、跨属主结果、伪造 native key 被拒；不能越过直接选择/委派策略。
5. UI 刷新恢复每条答案的实际角色，内部自治/任务子上下文没有混入直接槽。
