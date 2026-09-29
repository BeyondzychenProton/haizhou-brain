# 海卓智慧大脑（Haizhuo Brain）

企业级 Agent 平台。Java 17 / Spring Boot 3.5.16 模块化单体，Agent 执行基于 AgentScope Java 2.0.3，前端为 Vue 3。

本文件是仓库入口与导航；功能边界、已实现/未验证状态以[项目说明](项目说明.md)和[文档索引](docs/文档索引.md)为准，不把设计目标或测试模拟当作已交付能力。

## 技术栈

- JDK 17、Maven 3.9+、Spring Boot 3.5.16
- AgentScope Java 2.0.3（执行、模型与工具循环、Harness）
- MyBatis-Plus 3.5.17 + Flyway（MySQL）、Redis（WebSession）
- Langfuse（OTLP traces，可观测性，默认关闭）
- Vue 3 + Vite 6 + TypeScript + Element Plus（`haizhuo-brain-web/`）

## 模块结构

模块是同一应用内的工程边界，不是独立服务；统一前缀 `haizhuo-brain-`。

| 模块 | 职责 |
| --- | --- |
| `platform` | 员工与能力配置、渠道受理、Workspace 与资源、Session/Run 与执行协调 |
| `api` | 登录与 Web/webhook 接入、请求控制、状态查询、进度订阅 |
| `runtime-api` | 平台运行输入、事件、控制与工具桥接契约（不暴露 AgentScope 类型） |
| `runtime-agentscope` | Agent 装配、模型与工具循环、事件转换、框架状态解释 |
| `security` | 平台账号与登录态、角色、授权判断、操作确认与撤销 |
| `infrastructure` | 数据库、文件存储、外部服务与框架状态存取实现 |
| `observability` | 执行追踪、模型调用、耗时与用量上报（Langfuse） |
| `kernel` | 公共标识、基础错误与少量共享契约 |
| `bootstrap` | 应用启动、配置与组件装配 |
| `test-support` | 测试辅助设施与 MCP 模拟 Server，仅测试依赖 |

依赖方向与数据归属见[平台整体工程架构](docs/01-平台总览/平台整体工程架构.md)。

## 快速开始

前置：JDK 17、Maven 3.9+、Node.js 18+，以及集成测试所需的 Docker。本地 MySQL、Redis 与模型配置见[外置配置说明](config/README.md)：复制 `config/application-local.yml.example` 为被 Git 忽略的 `config/application-local.yml` 并填写。

```powershell
docker compose -f deploy/docker-compose.local.yml up -d
mvn -ntp -pl haizhuo-brain-bootstrap -am package -DskipTests
java -jar haizhuo-brain-bootstrap/target/haizhuo-brain-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
npm --prefix haizhuo-brain-web install
npm --prefix haizhuo-brain-web run dev
```

- 后端健康检查：`http://127.0.0.1:8080/actuator/health`
- 前端开发地址：`http://127.0.0.1:5173/`
- 本地 Redis 映射到 `6380`；Flyway 启动时执行当前最高至 V18 的迁移

公共默认的 `run-worker.enabled` 与 `channel-worker.enabled` 均为关闭；开启 Run Worker 后才会执行模型并可能产生调用费用。不要未经备份和升级演练就让新版本直接连接含历史数据的业务库。

## 验证

```powershell
# 受影响模块的定向测试（示例为 platform，按改动替换）
mvn -ntp -pl haizhuo-brain-platform -am test

# 跨模块装配或完整后端验证
mvn -ntp -pl haizhuo-brain-bootstrap -am test

# 前端类型检查与构建
npm --prefix haizhuo-brain-web run build
```

涉及 Flyway、权限、渠道投递或重启恢复时需补充集成验证；H2 通过不等于 MySQL 真机通过。

## 文档入口

- [项目说明](项目说明.md)：截至最近日期的代码能力与边界。
- [文档索引](docs/文档索引.md)：区分当前实现、目标设计与历史记录。
- [当前设计与实现总览](docs/01-平台总览/设计文档总览.md)
- [外置运行配置](config/README.md)
- [Agent 工作指南](AGENTS.md)：开发流程与 AgentScope 复用原则。
