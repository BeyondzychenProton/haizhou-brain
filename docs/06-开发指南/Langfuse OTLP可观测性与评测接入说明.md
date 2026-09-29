# Langfuse OTLP 可观测性与评测接入说明

> 状态：已实现代码骨架与本地自动化验证，待新版 Langfuse 部署后的真实 OTLP/评分联调。
> 适用版本：Spring Boot 3.5.x、AgentScope Java 2.0.3、Langfuse v4（自托管最低需 v3.22，且必须提供 OTLP traces 入口）。

## 1. 本轮边界

本轮把“运行时可观测性”和“最小评测写入口”接到同一条 Run 事实链上：

- AgentScope 原生 `OtelTracingMiddleware` 负责 `invoke_agent`、模型 `chat` 和框架工具意图 span。
- 平台中间件建立稳定的 `haizhuo.run` Run 根 span，并传播用户、平台 Session、Run、尝试次数等 Langfuse 属性。
- 工具 worker 在持久化结果落定后补一个工具结果 span；观测失败不会回滚工具状态。
- Spring Boot 的 OTLP HTTP/protobuf exporter 负责把 span 发到 Langfuse；不另写一套 legacy ingestion。
- Run 成功/失败、工具成功/失败和人工数值评分通过 Langfuse Public Scores API 异步写入。

本轮不实现：数据集运行器、LLM-as-a-judge、实验编排、自动回归阈值、原始文档预览和多模态文件解析。后续可以在现有 trace/score 契约上扩展，而不用改变 Run 的状态所有权。

## 2. 数据流与状态边界

```text
平台 Run/Session/User
        │
        ├─ RuntimeContext（LangfuseCallContext）
        │
        ▼
AgentScope Harness
  ├─ 平台 haizhuo.run 根 span
  └─ AgentScope 原生 invoke_agent / chat / execute_tool
        │  OTLP HTTP/protobuf
        ▼
新版 Langfuse /api/public/otel/v1/traces

工具持久化落定 ──> 工具结果 span ──> 同一 trace
Run/人工评分 ────> /api/public/scores（有界异步队列）
```

Run ID 是 trace 的稳定边界：同一 Run 跨“等待工具、工具执行、恢复尝试”使用同一个 32 位十六进制 trace ID。平台仍然是 Session/Run、授权、审计、工具结果和投递事实的唯一所有者；Langfuse 只是观测与评分的外部消费者。

## 3. 部署与配置

新版 Langfuse 需要暴露 HTTP OTLP traces 入口。官方接入约定是：

- traces endpoint：`<langfuse-base>/api/public/otel/v1/traces`；也可以把 `LANGFUSE_OTLP_TRACES_ENDPOINT` 指向兼容的 OTLP Collector。
- HTTP Basic Auth：public key 作为用户名、secret key 作为密码。
- `x-langfuse-ingestion-version: 4` 用于实时 ingestion。
- 本项目使用 HTTP/protobuf；不依赖 Langfuse 的 gRPC 入口。

启动前至少配置：

```text
LANGFUSE_ENABLED=true
LANGFUSE_OTLP_TRACES_ENDPOINT=http://<新版-langfuse>/api/public/otel/v1/traces
LANGFUSE_BASE_URL=http://<新版-langfuse>
LANGFUSE_PUBLIC_KEY=<public-key>
LANGFUSE_SECRET_KEY=<secret-key>
LANGFUSE_ENVIRONMENT=local
LANGFUSE_SERVICE_NAME=haizhuo-brain
```

对应配置位于 `haizhuo-brain-bootstrap/src/main/resources/application.yml` 和 `config/application-local.yml.example`。`LANGFUSE_ENABLED=false` 时，不创建 Langfuse 运行时 span，也不会启动有效的评分发送；默认不采集消息正文、工具参数或工具结果，只发送标识、计数、状态、摘要哈希和长度。

`management.opentelemetry.resource-attributes.service.name` 与 `LANGFUSE_SERVICE_NAME` 同步，避免 Langfuse 中的服务名仍被 Spring 应用名覆盖。若把 endpoint 改成无认证的 Collector，可保留空 key；直接连 Langfuse 时应同时配置两把 key，否则 exporter 会收到认证失败。

## 4. 评分接口

受保护接口：

```http
POST /api/v1/sessions/runs/{runId}/evaluations
Content-Type: application/json

{
  "name": "answer.quality",
  "value": 0.8,
  "observationId": "<可选的 Langfuse observation id>",
  "comment": "人工抽检"
}
```

接口先通过平台 `SessionApplicationService` 校验当前用户拥有该 Run，再将 `0..1` 的数值评分放入有界异步队列，返回 `202 Accepted`。响应里的 `accepted=true` 只表示本进程已接收，不等于 Langfuse 已持久化；队列满、应用退出或远端拒绝时仍需通过日志和后续补偿机制处理。本轮没有把评分写入平台业务库，也没有宣称具备可靠投递。

## 5. 安全与隐私约束

- 不把 public/secret key 放入 RuntimeContext、事件正文、日志或 OTLP 属性。
- 默认不上传 prompt、模型原文、工具输入 JSON 和工具输出；`LANGFUSE_CAPTURE_CONTENT` 只有在明确评审后才可打开。
- 工具结果 span 在 `completeExecution` 成功后再记录，观测端异常不能改变授权、审批、重试或 reconciliation 状态。
- 外部评分只能关联已授权的 Run，不能通过 trace ID 旁路读取其他用户的运行记录。

## 6. 验证状态与下一步

已完成：

- Java 17 下 bootstrap 全模块编译通过。
- trace ID/Span ID 稳定性、配置默认值、Basic Auth 和本地 Score API HTTP 投递测试已加入 observability 模块。
- AgentScope 2.0.3 原生 tracing middleware 已接入，属性传播通过 Spring Boot `SdkTracerProviderBuilderCustomizer` 注册。

尚待新版 Langfuse 部署后验证：

1. 用真实 public/secret key 启动服务，确认 `/api/public/otel/v1/traces` 返回成功并能在 Langfuse UI 看到 `haizhu.run`、`invoke_agent`、`chat` 和工具 span。
2. 验证跨挂起/恢复的同一 Run trace ID、user/session 属性和工具结果状态。
3. 调用评分接口，确认 Score API 的 trace/observation 关联和远端持久化。
4. 注入网络失败、队列满和应用优雅停机，补充“丢弃计数、重试/补偿、关机 drain”策略。

参考： [Langfuse OpenTelemetry 接入](https://langfuse.com/integrations/native/opentelemetry)、[Langfuse Scores API](https://langfuse.com/docs/evaluation/evaluation-methods/scores-via-sdk)、[Spring Boot OTLP tracing](https://docs.spring.io/spring-boot/reference/actuator/observability.html)、[AgentScope Java 2.0.3 tracing middleware](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/agentscope-core/src/main/java/io/agentscope/core/tracing/OtelTracingMiddleware.java)。
