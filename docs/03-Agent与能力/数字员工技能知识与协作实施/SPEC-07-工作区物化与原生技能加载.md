# SPEC-07 工作区物化与原生技能加载

状态：冻结工作区物化、缓存完整性核对、SESSION 文件路由与 AgentScope 原生冻结技能加载已实现；物化/技能契约测试通过，V24 随 V1–V33 全量 MySQL 迁移及后端全量回归通过。真实模型任务、真实进程重启与 Windows reparse-point 压力场景仍待验证。任务：B3、B4。依赖：[04](SPEC-04-原生文件存储与故障核查.md)、[06](SPEC-06-发布规格与版本冻结.md)、[00](SPEC-00-原生能力契约与验证门槛.md) Skill 探针。设计依据：[详细设计](../数字员工技能与只读协作改造详细设计.md) §6、§7。

## 目标与复用

扩展现有按 bundleHash 的工作区物化与 HarnessAgentFactory，使用原生冻结技能加载、文件/Plan 工具。`disableDynamicSkills()` 选择 frozen 路径，`disableDefaultWorkspaceSkills()` 只排除默认 Layer 4；本地 Layer 3 必须验证与过滤。

## 物化契约

- 生成 AGENTS.md、skills/、knowledge/ 及 manifest/完成标记；每个主/成员版本各自定义根，不相互覆盖。
- 临时目录校验全文件路径、数量、字节长度和哈希后发布；同 bundle 并发只有完整赢家可被使用。
- 命中缓存必须重新核对 manifest 与全部文件，不能只检查 AGENTS.md 存在。损坏/缺失拒绝或重建，不能继续加载不完整资产。
- 发布根只读；原生远端可写路由只指向 Session 计划/运行区。Windows reparse/symlink 与大小写冲突纳入边界验证。
- 只加载包内审核技能，排除机器级/用户级额外来源；禁用动态技能/记忆写入、Shell 和未批准本地执行。
- 知识是明确引用的只读文件入口，不假称已经接入 RAG。读取权限与引用审计仍来自平台。

## 实施步骤

先对现有 materializer 和 HarnessAgentFactory 做 impact；复用当前定义缓存，只缓存不可变模板。原生 filesystem spec 与必要 bootstrap 依赖在 SPEC-04 装配，不让模型传 namespace。

SINGLE_SKILLED 从发布规格实际传入冻结技能和知识索引、步数/约束，默认原生计划工具使用 Session KV。验证启动时报依赖缺失而不是静默关闭能力。

## 验收

1. 确定性模型可列出/读取批准技能，真实模型按技能完成“销售经理”任务；证据分开。
2. 删除/改写任一资产、追加未批准技能、大小写冲突与并发物化均不会错误命中缓存。
3. 两员工/版本/用户/角色各自只看到批准资产与运行文件，计划重建保持。
4. Shell、远端覆盖发布资产、默认外部技能来源被实际工具调用拒绝。
5. Windows 路径与真实 MySQL 文件恢复验收完成后，SINGLE_SKILLED 才允许灰度发布。
