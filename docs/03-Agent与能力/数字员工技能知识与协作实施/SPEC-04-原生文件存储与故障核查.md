# SPEC-04 原生文件存储与故障核查

## 本轮实施边界（2026-10-08）

原生 JdbcStore/DistributedStore 的 schema 和 RemoteFilesystemSpec 路由以 2.0.3 源码及 MySQL 探针校准；文件护栏只限制路径，存储仍委托原生后端。新 profile 的存储前置与保护性恢复可验证，但开放技能、专家、Team 仍由后续 SPEC 的发布门槛控制。

AgentState/KV 不具备平台原子 fence，本轮采用保守核查：租约不明、进入 Harness 后异常及父工具消费不明时进入 RECOVERY_REQUIRED，占用 Session；停止订阅，保留原生事实。管理员只有带旧执行停止证据、expectedFenceToken、requestId/reason 的审计终止入口，不提供同 Run 续跑。LEGACY_STABLE 的旧物理键与恢复规则保留。

状态：实现代码已接入；KV schema 初始化、隔离文件系统、RECOVERY_REQUIRED 与管理员审计终止入口有定向测试；全量迁移已在本轮 MySQL 验证。真实两进程旧 Writer、重启状态读取与消费不明故障尚未验证。任务：A4、A5。依赖：[SPEC-00](SPEC-00-原生能力契约与验证门槛.md) 存储/消费探针、[02](SPEC-02-持久事件与会话投影事务.md)、[03](SPEC-03-完整结果与原子交付.md)。设计依据：[详细设计](../数字员工技能知识与只读协作改造详细设计.md) §6、§13、§16。

## 原生复用与所有权

装配 2.0.3 JdbcStore/MysqlDialect、RemoteFilesystemSpec；继续已有 JdbcAgentStateStore，不重写状态引擎。AgentState 保存对话/工具等待，KV 保存计划/运行文件；平台保存 Run 与审计结果。三个事实面不可相互假冒。

## 存储契约

- 新增与锁定源码一致的 KV schema/version/index 迁移，运行时 initializeSchema=false。
- 显式 SESSION 隔离，用户与可信 nativeSessionKey 共同寻址；不用默认 USER，不用外部渠道 ID。
- 发布资产目录只读；计划/运行文件只走明确 SESSION 远端路由；未命中本地可写路径拒绝，而非静默回落。
- harnessSessionKey 和 workspaceRuntimeKey 分别隔离 AgentState 和可写文件；直接角色槽与任务子槽独立。
- 新 profile 缺必要存储拒绝启动；旧 LEGACY_STABLE 不因此被隐式改写或迁移物理键。

## 恢复契约

- 新 profile 增加 RECOVERY_REQUIRED：执行/原生写入是否停止不明时占用业务槽，阻止下一根 Run 自动接管。
- 平台 fence 保护平台事务；原生 AgentState/KV 无对应原子 fence 时，先查租约并不能证明防止旧 writer。
- 心跳失效/取消停止新受理并取消订阅；只有旧执行停止证据完整才能释放槽位。关闭 dispatcher 不等于成员订阅退出。
- 管理员 terminate 入口核对 expectedFenceToken、停止证据、requestId/reason，审计关闭；首期没有自动/人工同 Run 续跑按钮。
- 父工具是否消费不确定：保留结果、状态和引用供核查，不伪造原生消费状态，不重放模型调用。
- 查询、队列、取消、审批、SSE、Worker 都识别新状态；发布门槛要求所有活跃 Worker 升级。

## 实施与验收

装配在 bootstrap，原生配置在 runtime-agentscope，迁移/平台状态在 infrastructure/platform，恢复 API 在 api。先画出真实状态写入顺序再注入故障。

| 场景 | 预期 |
| --- | --- |
| MySQL 进程重建、两用户/两角色同文件名 | 正确读取各自文件/状态，发布资产未被覆盖 |
| 状态写入前/后、根完成前/后退出 | 能证明则读取既有事实；不明则 RECOVERY_REQUIRED，不重复执行 |
| 旧 owner 仍写，新 Worker 租约过期 | 不同时启动新 writer，旧平台结算/意图被 fence 拒绝 |
| 取消与审批结果竞态 | 失效 Scope 不恢复 Run，不重复消费工具结果 |
| legacy/V1 会话回归 | 原身份键、固定版本和批准路径保持 |

真实 MySQL/两个实际进程证据为开放新 profile 的必需项。单进程内存测试只能证明组件隔离。
