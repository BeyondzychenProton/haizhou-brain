# SPEC-01 执行来源与根子事件隔离

状态：已实施、定向与完整后端回归已通过；新专家 profile 尚未开放。任务：A1。依赖：[SPEC-00](SPEC-00-原生能力契约与验证门槛.md) 的事件来源探针。设计依据：[详细设计](../数字员工技能知识与只读协作改造详细设计.md) §3.6、§10。

## 现状与目标

实施前，`AgentScopeEventTranslator.translate` 将每个原生 AgentResultEvent 都映射成根完成，将每个停止/挂起事件映射成根控制；计划缓冲由共享 translator 持有。本切片已在现有 streamEvents 适配边界保留原生来源，隔离根与子事件，不增加事件总线。

## 契约

- runtime-api 保留 BrainAgentEvent 平台端口及已有调用方式，新增不依赖 AgentScope 类型的来源描述。
- 描述保留原生 eventId/createdAt/source、replyId/blockId 与已批准 metadata；业务 attempt/fence 与 ROOT/CHILD/UNKNOWN 归属由可信执行作用域产生。
- 原生根来源的具体形状由 SPEC-00 固定；非空 source 不凭角色名称认作 ROOT。无法关联的事件是 UNKNOWN/内部诊断，不能完成或暂停业务 Run。
- 只有 ROOT 的最终结果、停止或外部工具等待可以映射现有根事件。CHILD 可携带内部进度/结果描述供后续 delegation 关联，默认 INTERNAL。
- 直接选择专家仍属于 ROOT；同一角色被委派时属于 CHILD。作用域不是发布角色固有属性。
- 每次执行订阅建立独立 translator 上下文，固定可信 native session、attempt/fence；只有 ROOT 收集 Plan 缓冲，按 runId/replyId/toolUseId 关联。CHILD/UNKNOWN 不收集原始入参；onComplete/onError/cancel 都清理，不让同 Run 的不同 attempt 共用增量。
- 仅对 Plan 工具收集必要参数；不要长期缓存其他工具敏感入参。未知增量在能确定名称后才进入 Plan 提取。

## 改动位置与步骤

1. 对 `AgentScopeEventTranslator`、`AgentScopeRuntime`、事件记录和相关测试做 upstream impact；核对 bootstrap 注入及旧构造器。
2. 先增加“子结果先于根结果”的失败回归，确认原实现提前产生根完成。
3. 在 runtime-api 增加来源值对象与内部事件契约；旧根事件的构造调用保持可用。
4. Translator 增加执行上下文适配，Runtime 在 defer 内创建并在 finally 释放；观测只跟随根终态。
5. 平台现有 consumer 继续处理根事件；内部子事件不会进入旧主答案、审批或结算分支。持久关联由 SPEC-02/09 接入。

## 验收场景

| 场景 | 预期 |
| --- | --- |
| child result → root result | 前者不产生 AgentRunCompletedEvent；后者恰有一个根完成候选 |
| 子停止/挂起/文本/计划 | 不取消、暂停、拼入根答案或更新根计划 |
| 两订阅同 runId、不同 source/reply | Plan 互不污染；取消一个不清除另一个 |
| buffer 后异常/取消 | 执行上下文清理；后续订阅不读取残留 |
| 单 Agent 旧工具与 Plan | 原有结果、工具等待和 Plan 行为保持 |
| 真实 native spawn/send | 捕获的字段与 SPEC-00 一致，无角色/source 猜测 |

定向运行 runtime-agentscope 测试，再验证平台/装配消费兼容。未启用专家之前不声明协作链路已完成。

## 实施结果（2026-10-07）

- 新增 AgentEventDescriptor、AgentExecutionRole、AgentInternalEvent；既有根事件保留旧构造器，新增 descriptor，平台生成事件允许无原生描述。
- ROOT 仅接受已验证的 source=null；属于当前 native session 的子 source 标为 CHILD，陌生/空 source 标为 UNKNOWN。两者不会完成、停止、挂起根 Run 或拼入主答案。
- Runtime 以 Flux.using 为每次订阅建立并释放 translator；计划缓冲限制 64 个待结束调用、每个 128K 字符，已知非 Plan 参数不保留。原生 AgentResult/Stop 清理该 Run 的根缓冲。
- 根完成后、异常和订阅取消均验证作用域关闭。定向 30 项通过，完整后端 251 项通过（含现有真实 MySQL 8.4 兼容链路）。
- 来源描述本轮只到 runtime 契约；数据库/SSE V2 持久化、受信实例映射与用户专家卡片分别由 SPEC-02/09/14 完成，不能把内部事件当作已开放的协作功能。

证据：[第一阶段验证报告](../../07-测试报告/数字员工协作实施第一阶段验证报告.md)。
