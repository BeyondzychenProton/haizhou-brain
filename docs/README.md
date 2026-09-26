# 项目文档

## 文档索引

| 文档 | 状态 | 用途 |
| --- | --- | --- |
| [Agent 工作助手平台初版概要设计](design/agent-platform/overview.md) | 已确认的概要设计 | 核心概念与运行关系；主 Agent 行为边界；Workspace 资料、产物版本与跨会话使用规则 |
| [请求执行链路与控制概要](design/agent-platform/request-lifecycle.md) | 已确认原则的链路整理，工程细节待验证 | 网页请求到 AgentScope Java 执行的主链路、交互控制、前后端展示分工与工程职责 |
| [Agent 平台整体工程架构](design/agent-platform/architecture.md) | 架构草案待审阅；职责与复用方向已确认，具体接入及运行验证待完成 | 模块与数据归属、官方 Workspace 定位及重叠评估、执行控制、事务、Langfuse 和验证清单 |
| [数字员工多渠道接入与消息流转设计](design/agent-platform/multi-channel-ingress.md) | 0.1 架构设计；当前仅完成通用 Java 骨架 | 数字员工发布、渠道身份、Session/Run、入站去重与出站投递边界 |
| [AgentScope Java：Agent 与 Harness 能力参考](guides/agentscope-java-capabilities.md) | 官方 2.0.3 文档与源码核查 | Agent / Harness 能力、Workspace 与 Profile 区别、项目复用边界及 Langfuse 定位 |
| [AgentScope Java：源码阅读与接入验证](guides/agentscope-java-integration-checklist.md) | 静态核查完成，运行验证待执行 | 调用链阅读入口、文档与源码差异、进入详细设计前的实验和验收条件 |
| [AgentScope Java 2.0.3：面向本项目的学习路线](guides/agentscope-java-learning-path.md) | 官方资料与源码核查；学习建议 | 按编码决策排序的学习主题、官方阅读入口及学到位的判断标准 |

设计文档描述目标方案，不代表代码已经实现。

## 目录组织

```text
docs/
├── README.md                         # 文档索引与管理约定
├── guides/
│   ├── agentscope-java-capabilities.md          # 框架能力与项目映射
│   ├── agentscope-java-integration-checklist.md # 源码阅读与验证清单
│   └── agentscope-java-learning-path.md         # 面向本项目的学习路线
└── design/
    └── agent-platform/
        ├── overview.md               # Agent 平台概要设计
        ├── request-lifecycle.md       # 请求执行链路与控制概要
        ├── architecture.md            # 整体工程架构草案
        └── multi-channel-ingress.md   # 多渠道接入与消息流转
```

- 设计文档按业务主题归档到 `design/<topic>/`，同一主题的概要与后续详细设计放在一起。
- `overview.md` 是该主题的概要入口；详细设计按内容命名，例如 `session-runtime.md`。仅在有实际内容时创建。
- 后续操作指南放入 `guides/`；需要单独留存的重大架构决策放入 `decisions/`。目前不创建空目录。
- 目录和文件使用小写英文及连字符，正文使用中文，保留必要的技术术语。

## 维护约定

1. 新增、移动文档时同步更新本索引，仓库根 README 保留文档入口。
2. 设计文档标明状态、版本、更新日期、适用范围；区分已确定原则、后续细化项和实现状态。
3. 同一主题持续维护固定文件，不使用“最终版”“最终版2”等重复文件；历史由 Git 管理。
4. 变更已确认的设计原则时，在原文中更新结论并记录变更摘要，避免新旧结论并存。
5. 仓库内链接使用相对路径；外部参考使用原始项目或官方文档链接。外部实现不自动构成本项目的能力承诺。
