# Mock 退出与数据清理测试报告

| 项目 | 结果 |
| --- | --- |
| 日期 | 2026-09-27 |
| 对应设计 | [Mock 退出与真实开发基线设计](../04-渠道与执行/Mock退出与真实开发基线设计.md) |
| 结论 | 通过；应用当前运行于本机 8080，旧业务入口关闭，原型数据已清理 |

## 验证环境

JDK 17.0.9、Maven 3.9.14、MySQL 8.0；本项目独立 Redis 容器映射到本机 6380。应用使用 `local` Profile，配置文件在 `config/application-local.yml`，该文件不入库。

## 自动化与构建

执行 `mvn -ntp -pl haizhuo-brain-bootstrap -am clean package -q`，退出码 0。干净构建生成的测试报告共 5 项：渠道入口 3、通用 Agent 定义 JDBC 1、已关闭 HTTP 路径 1；失败 0、错误 0、跳过 0。旧会议室 Mock 测试源码和其历史报告缓存均未参与本次构建。

## 本地运行检查

| 检查 | 结果 |
| --- | --- |
| `GET /actuator/health` | HTTP 200，`status=UP`，liveness/readiness 正常 |
| `POST /api/v1/sessions` | HTTP 404；固定用户 Run 入口关闭 |
| `GET /api/v1/sessions` | HTTP 404 |
| `GET /api/admin/v1/capabilities` | HTTP 404；未认证管理入口关闭 |
| `GET /mock/api/v1/rooms/A-201/availability` | HTTP 404 |
| Flyway | 既有库从 V2 升到 V3；应用重启后 3 个迁移校验通过，无待执行迁移 |
| `mock_meeting_room`、`mock_meeting_booking` | 信息架构中均不存在 |
| `agent_run`、`agent_session` | 原型数据清理后各 0 条；通用表保留 |
| `digital_employee`、`capability_definition` | 种子数据清理后各 0 条；通用表保留 |
| `run_effective_capability_set`、`tool_invocation_audit` | 原型关联记录清理后各 0 条；通用表保留 |

清理前只读核对确认：9 条 Run 均为测试用户 1001 且使用 `run:<RunId>` 原型操作键，10 条 Session 均为用户 1001；仅有种子员工 1 和两项会议室能力。V3 按标识删除关联记录，不全表清空通用实体。V1/V2 保留原文件，以维护 Flyway 历史校验。

## 当前边界

本次验证证明应用启动、数据库迁移、通用 JDBC 测试和旧入口关闭。当前没有真实身份认证、会议室外部系统契约或可执行会议室工具，因此不进行真实预订测试。原 `agent_run.booking_id` 与 `operation_key` 字段保留为历史结构，待真实 Run 模型确定后再迁移。

