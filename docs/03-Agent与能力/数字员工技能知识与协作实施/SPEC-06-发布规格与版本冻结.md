# SPEC-06 发布规格与版本冻结

状态：V2 运行配置、校验/发布、成员版本冻结与 Run 目标冻结已实现并通过服务测试；V23 迁移随 V1–V33 全量迁移及后端全量回归通过。各新 profile 仍受独立启用开关及后续验收门槛控制。任务：B2。依赖：[SPEC-05](SPEC-05-技能知识资产管理.md)。设计依据：[详细设计](../数字员工技能与只读协作改造详细设计.md) §3、§5、§9、§14。

## 原生复用与目标

沿用 AgentDefinitionDraft、DefaultHarnessDefinitionBundleCompiler、不可变发布事务及 RuntimeDefinitionSnapshot，把审核资产、固定角色、预算和原生运行配置加入 V2 包。平台生成规格，Harness 读取执行；不再建另一套定义运行引擎。

## V2 契约

- configuration 缺省归一 LEGACY_STABLE；SINGLE_SKILLED、TEAM_READONLY、TEAM_AUTONOMOUS_READONLY 必须明确选择且通过对应开关/验收门槛。
- V2 冻结 profile、runtimePolicy、技能知识具体修订/哈希、固定成员 employeeId/versionId、直接选择/委派策略、预算及工作区 manifest。
- 编译器产生实际工具、模型、提示、文件和成员策略哈希；不是仅给现有空数组换名字。步数/超时来自已验证策略。
- 固定成员必须同租户、版本属于指定员工、无自引用/循环；成员发布版本不解析 latest。
- 原生通用/动态角色没有正式员工版本时保留来源/父冻结规格/有效子规格，不制造虚假 employee/version 外键。
- Run 创建时冻结团队所有者、实际 executor、targetRole、mode、references、槽位与预算；排队提升不读新草稿。
- 零业务工具 V2 合法；旧 DTO 的 @NotEmpty 按 profile 调整，不能隐式启用未审核功能。

## 实施与 API

扩展 draft/validate/publish 现有入口，validate 输出 code/severity/fieldPath/message/relatedId 及 previewHash。新增最小员工创建、固定版本列表、脱敏 bundle 预览入口；保存/发布沿用 expectedDraftRevision/requestId/reason。

infrastructure 仅新增 V2 内容/依赖迁移，旧 V1 包和 definitionVersionId 不重编译。读面识别 V1/V2，旧 Session 不在线升级为团队。

## 验收

1. 同 requestId、同审核草稿返回同版本；失败回滚包/依赖/指针，修订冲突不覆盖。
2. 资产或成员更新后，旧 Session/已排队 Run 的发布版本、manifest 和能力保持。
3. 零工具员工、未审核资产、跨租户/错版/循环成员、超限预算各有准确 fieldPath。
4. V1/legacy 请求回归；新 profile 缺原生文件/安全边界时 validate 或运行拒绝。
5. 编译产物与实际 Harness 工具集/策略一致，后续 SPEC-07/09 用实际运行断言确认。
