# SPEC-09 只读专家调用与预算

状态：固定发布专家已接入 AgentScope 原生 SubagentsMiddleware/Factory；只读子构建、Run fence、持久调用/并发预算、子结果保存与父取消/迟到保护有定向测试和原生事件探针。V1–V33 全量 MySQL 迁移及后端全量回归通过。真实模型多轮并行、实际独立取消与跨进程竞争仍待验收；受控协作 profile 不开放。任务：C1–C6。依赖：SPEC-00 spawn/send 契约、01–04、06、07。设计依据：[详细设计](../数字员工技能与只读协作改造详细设计.md) §8.1–8.5、§9、§10、§12。

## 原生复用与作用域

由 agent_spawn/agent_send 和原生管理器执行串行、并行、复核、修订；使用公开 factory/registry 扩展点接入固定员工版本。平台不写子调度器。自定义 factory 不自动应用 SubagentDeclaration 的过滤/步数，必须在薄构建中显式落实。

Builder 的 Function<String, Agent> 回调不接收父 RuntimeContext；正式委派使用公开 `agent.middleware.SubagentEntry`、`agent.subagent.SubagentFactory.create(RuntimeContext)` 与 SubagentsMiddleware/原生管理器接线，或经 SPEC-00 验证的 Run 作用域薄装配。2.0.3 公开签名已由 javap 核对；运行时仍须证明父身份、取消和预算确实传入，不用共享闭包、ThreadLocal 或模型字段补身份。该接线尚未验证。

## 子构建与身份

- 可信 delegationScope 带父 Session/Run/attempt/fence、角色/固定版本、有效子规格、权限与预算；factory 不读 ThreadLocal 全局可变角色。
- CHILD 是 leaf：禁用子/动态子、Shell、业务 TOOL/MCP、Plan 写、自动长期记忆/技能写入、后台与 expose。用公开 disable 方法，最终工具集实测。
- force-sync/expose/执行者等敏感参数由可信上下文覆盖，不能相信模型参数；不允许脱离父 Run。
- 首次 spawn 建 delegation，每次 spawn/send 建独立 invocationId/toolUseId；agent_key 仅解析父 Run 已有可信实例，陌生/其他 Run key 拒绝。
- 跟进保持同一子实例上下文；根结束/取消后实例与登记清理。恢复不从临时 key 猜测活实例。

## 预算与事务

默认根 8、子 4 步，spawn+send 总 4 次、并行 2、同固定角色 active 1；按冻结配置可收紧，不能由子输入扩大。

acting 批次受理前整体预检并事务占位，预算跨 attempt；拒绝本批不启动其中任意子。完成/取消/错误释放并发 reservation 幂等，总调用计数不退还。租约失效拒绝新受理、停止子，未证停止不提前释放槽给新 writer。

平台持久 delegation/invocation/reservation 只记录授权、调用与结果，不新增子 Worker 队列。事件按 SPEC-01 来源和受信实例映射，关键事实经 SPEC-02 写入，结果经 SPEC-03 保存。

SPEC-00 已证明本地子 call 不额外产生 AgentResultEvent。完整子结果须在原生调用返回的消息/工具返回边界做最薄的关联与持久化；不得只等子结果事件。source 仅含父 session/类型，实例和每轮 invocation 必须由受信调用映射关联；两实例并发与取消/晚到仍需探针。

## 验收

1. spawn→send→send 实际保留同子历史，三份调用/结果独立；陌生与跨 Run key 不启动。
2. 并发同角色/超预算/重复受理/跨 attempt 的事务正确，执行计数与原生启动次数一致。
3. 子工具/文件/后台/expose/递归尝试实际被拒，父身份与角色不串。
4. 子先完成不完成根；取消/旧 fence 晚到不改当前投影或产生交付。
5. 串行研究、并行分析、复核修订真实链路与安全多轮卡片可恢复；MySQL 预算竞争单列验证。
