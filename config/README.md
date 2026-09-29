# 外置运行配置

Spring Boot 从项目根目录启动时读取 `config/` 下的 YAML。应用配置直接写入对应文件，不依赖环境变量注入。包含密码或密钥的实际文件被 Git 忽略；提交的 `.example` 文件只保留占位值。

| 文件 | 用途 |
| --- | --- |
| `application.yml` | 公共端口、应用名与默认模型 |
| `application-local.yml` | 本地 MySQL、Redis、日志与模型服务 |
| `application-staging.yml` | 测试/预发环境配置 |
| `application-prod.yml` | 生产环境配置 |

首次本地配置：

```powershell
Copy-Item config/application-local.yml.example config/application-local.yml
# 编辑本地 YAML 中的数据库、Redis 与 haizhuo.brain.agent 参数
docker compose -f deploy/docker-compose.local.yml up -d
```

运行前需 JDK 17 和 Maven 3.9 以上。本项目的 Redis 在本机映射为 `6380` 端口；不要复用其他项目在 `6379` 上的受保护实例。`application-local.yml` 的 MySQL 密码应与本地数据库一致。Flyway 在应用启动时迁移数据库。

## Agent 运行时开关

公共配置中的 `haizhuo.brain.run-worker.enabled` 默认为 `false`。关闭时不会注册 Agent 运行时，也不会发起模型调用；开启时才会装配 AgentScope Runtime。本机模拟联调可在测试身份、模型和模拟 Server 均配置完成后显式开启；生产环境则必须先完成真实凭证、授权和目标 Server 联调。开关打开不等于真实外部业务能力可用：

```yaml
haizhuo:
  brain:
    run-worker:
      enabled: true
```

管理与 Session/Run 端点使用平台可信身份链路；是否可端到端运行仍取决于本地数据库、Redis、模型服务和目标工具服务。原 `meeting-mock` Profile 和脚本已退出。

## Langfuse OTLP 可观测性

需要使用提供 OTLP traces 入口的新版 Langfuse（建议 v4，自托管最低 v3.22）。默认关闭；启用时配置
`LANGFUSE_ENABLED=true`、`LANGFUSE_OTLP_TRACES_ENDPOINT`、
`LANGFUSE_PUBLIC_KEY` 和 `LANGFUSE_SECRET_KEY`。HTTP endpoint 使用
`/api/public/otel/v1/traces`，项目会通过 Spring Boot OTLP HTTP/protobuf exporter
发送，并把评分通过 Langfuse Public Scores API 异步写入。详细边界、脱敏规则和验收步骤见
[Langfuse OTLP 可观测性与评测接入说明](../docs/06-开发指南/Langfuse%20OTLP可观测性与评测接入说明.md)。

`LANGFUSE_CAPTURE_CONTENT` 默认为 `false`；未完成脱敏评审前不要打开。评分接口返回
`accepted=true` 只表示本地有界队列已接收，不代表远端已经持久化。

## MCP 模拟联调（仅本机/测试）

MCP 连接默认拒绝未知目标；`haizhuo.brain.mcp.allowed-hosts` 只允许显式列出的 HTTPS 主机。下列开关单独放行本机 HTTP，**不得配置在生产环境**。模拟器和平台使用同一条至少 32 字符的临时测试密钥；测试手机号只是用户身份属性，Token 由平台按用户生成，不能把手机号本身当成授权凭据。

```yaml
haizhuo:
  brain:
    mcp:
      simulator:
        enabled: true
        secret: "<仅用于本机联调的临时密钥，至少 32 字符>"
```

模拟 Server 的 `main` 类为 `com.haizhuo.brain.testsupport.mcp.SimulatedMcpServer`，监听 `127.0.0.1:8765/mcp`（可用 `MCP_SIMULATOR_PORT` 调整）；启动进程前须设置同一 `MCP_SIMULATOR_SECRET`。它实现分页 `tools/list`、逐用户可见性、便笺归属判权、带操作键的幂等写与状态查询。默认写入者和只读者分别是固定测试手机号；本机演示已有账户时，可通过 `MCP_SIMULATOR_WRITER_MOBILE`、`MCP_SIMULATOR_READER_MOBILE` 显式指定这两位用户，分别拥有 `note-a`、`note-b`，不改变平台登录或授权规则。管理员可从「MCP 连接」页登记 `demo-mcp`、发现并逐工具批准，再在数字员工草稿中绑定能力并发布。运行本机功能测试无需手工启动此服务，测试会自行启动随机端口的实例。

关闭模拟开关后，生产 `McpUserTokenProvider` 目前故意拒绝发放凭证；终端用户凭证取得/续期及真实 MCP Server 信任联调尚待对接，不应把模拟签名方案用于生产。
