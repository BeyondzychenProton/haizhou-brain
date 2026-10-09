# SPEC-00 原生能力契约与验证门槛

状态：部分完成；固定/动态子 Agent 的原生调用、只读工具面、Team 正常唤醒和取消探针、冻结技能加载契约均已通过确定性 Harness 测试；Team 持久生命周期与预算/晚到事件 fence 已通过 MySQL 测试。真实模型、Team 超时/租约失效/JVM 故障及完整原生消费故障矩阵仍待验证。任务：A0、C9。依赖：无。设计依据：[详细设计](../数字员工技能与只读协作改造详细设计.md) §1.3、§6–10、§13、§17.5。

## 目标与交付

在锁定 AgentScope Java 2.0.3 的真实 Harness 上确定运行协议，用确定性模型与真实原生组件做可重复探针。交付专项测试、实际结果报告及对后续 SPEC 的校准；不会通过自写模拟 Team/子管理器证明框架行为。

## 验证分组

| 组 | 输入与观测 | 合格条件 / 校准点 | 阻断对象 |
| --- | --- | --- | --- |
| 事件 | 根→spawn→send→根结果；并发两实例；停止/挂起 | source、事件 ID/时间、replyId/blockId、metadata 与实际执行对应；未知来源不降为根 | 01、09、11 |
| 子构建 | 固定角色、通用、生成专家；伪造后台/expose/工具 | 原生公开扩展点可收敛 leaf、force-sync 与工具白名单；最终工具集实际可观测 | 09、11 |
| 技能 | 冻结资产与额外 Layer 3/4 来源 | native frozen 路径加载批准技能；未批准技能与可写覆盖被拒 | 07 |
| 存储 | SESSION 路由、两用户/两角色、AgentState、JdbcStore | 文件面/状态隔离；重建能读；不存在本地可写漏路由 | 04、08 |
| 原生消费 | 外部工具等待→结果送入→状态写入前后故障 | 记录恢复是否确定；不能以结果已存等同父已消费 | 03、04、10 |
| Team | 详细设计 T01–T12 | 实际复现消息序号、注册键、动作、通知、互斥、收尾和崩溃限制 | 12、13 |

## 实施位置与方法

- `haizhuo-brain-runtime-agentscope/src/test/` 增加专项测试，复用 `HarnessP0IsolationTest` 的 ChatModelBase/Toolkit/临时目录方式。
- source JAR/javap 只用于确定公开签名与解释行为；运行断言不得只测试类型存在。
- 根模型脚本生成真实 ToolUseBlock；子模型记录实际历史，确认 send 跟进的是同一实例且用户/Run/role 不串。
- 原生工具名及参数从当前依赖读取。不会调用 package-private `asLeafSubagent()`，不会反射绕开框架约束。
- Team 先使用真实 InMemoryStore 验证确定性协议，再以真实 MySQL 验证持久与跨进程故障；二者证据分开。
- 记录只包含事件形状、合成身份及计数，不输出思考链、凭据或真实用户文本。

## 验收

1. 定向 JUnit 探针可重复结束，超时/取消会关闭真实 Harness、dispatcher 和订阅。
2. 对每个能力标记“可配置 / 可薄适配 / 原生缺口”，缺口必须有具体失败场景。
3. 事件来源探针通过后才进入 SPEC-01；其他未完成探针继续阻断对应 profile，不阻断独立修复。
4. 真实 MySQL/模型/浏览器尚未运行时明确写未验证，不能把确定性模型当业务质量验证。

验证入口：`mvn -ntp -pl haizhuo-brain-runtime-agentscope -am test`。定向执行时使用 `-Dtest=<实际测试类> -Dsurefire.failIfNoSpecifiedTests=false`；报告记录实际命令与结果。

## 已验证的事件契约（2026-10-07）

- `NativeSubagentEventContractTest` 使用真实 Harness、原生 agent_spawn/agent_send、原生管理器和确定性模型。可信 CTX_FORCE_SYNC 覆盖模型的 timeout_seconds=0；两次子调用沿用同一实例，第二次能读取第一次历史。
- 当前探针的根 AgentResultEvent 恰有一个，source=null；子文本及生命周期 source 为 `parentSessionId/subagentType`。事件 ID、创建时间和 replyId 等由原生流产生。
- 本地子执行使用 `Agent.call()`：会转发子文本、start/end，**不会额外发出子 AgentResultEvent**。子完整结果从原生调用返回的消息/工具返回边界关联，不能只等待子结果事件。远程路径另行验证。
- source 不提供同类型多个实例的唯一身份；后续 SPEC-09 仍需受信 spawn/send 调用与实例映射探针，不能凭 source 补出 invocationId。
- 确定性模型的流式 ToolUseBlock 包含真实 content 参数结构，避免用不符合原生调用协议的脚本制造假缺口。
- Team 正常路径已有原生 Harness 探针；本轮又验证了动态专家从 `agent_generate` 到同 Run `agent_spawn` 的执行及只读工具面。技能过滤、新文件面、跨进程恢复和失败收尾矩阵仍待探针。

实际步骤与结果：[第一阶段验证报告](../../07-测试报告/数字员工协作实施第一阶段验证报告.md)；2026-10-08 完整回归及 2026-10-09 V34 定向迁移/启动补验见[阶段验收报告](../../07-测试报告/数字员工协作实施阶段验收报告.md)。
