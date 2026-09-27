> **历史记录（2026-09-27）**：原会议室 Mock 及未认证业务/管理 API 已退出。本文中的原型实现和测试结果只反映当时状态；当前开发与运行边界请见 [Mock 退出与真实开发基线设计](../04-渠道与执行/Mock退出与真实开发基线设计.md)。

# Agent 动态能力装配设计实现测试报告

| 项目 | 结果 |
| --- | --- |
| 验收日期 | 2026-09-27 |
| 验收对象 | `AgentDefinitionVersion → EffectiveCapabilitySet → AgentScope Toolkit` 动态装配首轮实现 |
| 总体结论 | 通过；自动化测试、MySQL V2 迁移、管理 API 动态发布及真实模型 Mock 工具循环均已验证 |
| 本地服务 | `http://127.0.0.1:8080`，仅本机回环监听 |
| 实际数据 | 本地 MySQL 8；Flyway 迁移版本 V2 |
| 安全范围 | 无管理员登录；只适用于 meeting-mock 本机开发，禁止暴露到局域网或公网 |

本报告记录首轮可以复现的结果和未覆盖边界。模型服务地址、API Key、数据库密码等敏感配置均从 Git 忽略的本地 YAML 读取，未写入报告、代码或配置模板。

## 1. 本轮实现范围

- 管理 API 可读取已登记能力目录，读取/保存员工草稿、校验并发布不可变版本，启停能力以及设置 Mock 用户能力授权。
- MySQL/JDBC 仓储保存草稿、发布版本、精确能力修订绑定、全局启停状态、用户授权、Run 有效能力快照和工具调用摘要审计。
- Run 启动时从数据库读取当前发布版本，结合已登记能力、运行时白名单提供者和当前用户授权解析 EffectiveCapabilitySet，并保存快照。
- AgentScope Runtime 按本次允许的 RuntimeCapability 创建独立 Toolkit；去除了 `MeetingRoomTools` 的静态工具注册。
- Gateway 每次工具调用前再次校验 Run 快照、能力启停状态、用户授权及白名单提供者。会议室预定必须先对相同时间和人数成功查询。
- 外置 YAML 模板补充租户、员工、Mock 用户和管理操作者配置项；不支持环境变量注入。

能力目录当前通过 Flyway 种子登记两项会议室工具。数据库只选择已部署的 `meeting-room-v1` 白名单实现，不能在 API 中上传 Java 类、脚本或任意 URL。新增业务能力仍需先由代码提供者实现并部署。

## 2. 自动化测试

在 Java 17 环境执行：

```text
mvn -ntp -pl haizhuo-brain-bootstrap -am test
```

| 测试类 | 用例数 | 失败 | 错误 | 跳过 | 覆盖重点 |
| --- | ---: | ---: | ---: | ---: | --- |
| `ApplicationContextSmokeTest` | 3 | 0 | 0 | 0 | Spring Profile 启动、健康端点和管理 API 草稿/发布/授权流程 |
| `MeetingRoomMockLoopTest` | 6 | 0 | 0 | 0 | 选择能力、下一个 Run 使用新发布、即时撤权、预定核验、模型失败及虚报拦截 |
| `JdbcAgentDefinitionRepositoryTest` | 1 | 0 | 0 | 0 | H2/JDBC 草稿、发布幂等、快照读回、撤权复核和调用审计 |
| `MeetingRoomJdbcPersistenceTest` | 3 | 0 | 0 | 0 | MySQL 兼容持久化、幂等、并发冲突与 Session Run 约束 |
| `ChannelIngressServiceTest` | 3 | 0 | 0 | 0 | 既有渠道入站契约回归 |
| **合计** | **16** | **0** | **0** | **0** | |

另外，`mvn -ntp -pl haizhuo-brain-bootstrap -am -DskipTests compile` 编译通过；meeting-mock 打包脚本成功生成并启动 Spring Boot 应用。

## 3. MySQL 与管理 API 验收

- MySQL 8 成功执行 Flyway V2，动态能力相关表和种子能力可查询。
- `GET /api/admin/v1/capabilities` 返回已登记的会议室查询、预定能力。
- 草稿读取、保存、校验、发布及用户授权 API 均通过 Spring 集成测试；运行时复核授权撤销后会拒绝后续工具动作。
- 在真实本机服务中先发布仅查询能力版本并创建 Run，随后恢复查询+预定能力版本；无需重启应用，后续 Run 即按新的已发布数据库配置装配。
- 验收结束时，员工 1 的本地数据库发布配置已恢复为查询+预定两项能力。发布版本号因验收发布推进到 V7，这是测试过程产生的本地配置历史；全新数据库由 Flyway 从 V1 种子开始。

## 4. 真实模型动态工具验证

真实模型通过 AgentScope OpenAI 兼容调用执行，房间查询与预定均走本机 Mock `MeetingRoomSystem`，不是外部会议室系统。

### 4.1 仅查询能力版本

- 通过管理 API 发布只包含 `meeting_room.search` 的新定义版本。
- 运行日志确认本次 Toolkit 只注册 `meeting_room_search`。
- 真实模型查询 A-201 在 2026-10-15 14:00–15:00、6 人的可用情况；工具调用完成，Run 状态 `SUCCEEDED`。
- 由于该定义没有预定能力，本次未创建 booking；本次验收确认 Toolkit 不会把未绑定的预定工具带入模型执行。

### 4.2 查询并预定能力版本

- 通过管理 API 恢复查询+预定的员工定义，不重启服务。
- 真实模型请求预定 A-201 在 2026-10-20 14:00–15:00、6 人的时段。
- 运行日志确认本次 Toolkit 注册 `meeting_room_search` 和 `meeting_room_reserve`；Agent 先查询，再提交预定。
- Run 结果为 `SUCCEEDED`，业务结果为 `BOOKED`；本地 MySQL 中存在对应 Mock booking，Run 查询结果可核验预定事实。

本次验收写入了一笔本地 Mock 预定记录，作为测试数据保留。它不代表真实会议室资源被预定。

## 5. 当前运行方式与配置

本机服务绑定 `127.0.0.1:8080`，当前启动使用 `local,meeting-mock` Profile。真实运行配置位于 Git 忽略的 `config/application-local.yml` 与 `config/application-meeting-mock.yml`；仓库只保留模板和说明。启动方式见[外置运行配置说明](../../config/README.md)：

```powershell
.\scripts\run-meeting-mock.ps1
```

## 6. 已知边界及后续工作

1. 管理 API 暂无身份认证和管理员权限校验；仅允许本机 Mock 使用。部署到任何非本机环境前必须完成认证和管理权限控制。
2. 当前仅一个预置员工、一个固定 Mock 用户和两项会议室能力；不代表通用多员工、租户隔离或平台级 RBAC 已完成。
3. 首轮授权是 `user_id + capability_code` 布尔值，不支持资源 Scope、部门授权、统一业务 Permission 或外部系统 Token。
4. 能力目录可查询和启停，不能通过 API 新增目录项或能力修订；新动作必须实现并部署白名单提供者。
5. 工具审计保存参数哈希与结果摘要，不可从中还原具体预定参数；管理配置的前后变化也没有完整审计事件表。
6. 同一 Run 查询收据保存在进程内存。重启后会议室预定流程通过操作键核验已提交 booking，并安全收敛 Run；尚不支持从查询步骤完整续跑。
7. 能力 schema 篡改、多个 Run 并发隔离、发布事务故障注入等专项测试仍需补充；本轮 16 项自动化测试和两种真实模型动态工具路径已通过。

## 7. 对应设计文档

- [Agent 定义版本与运行时能力动态装配详细设计](../03-Agent与能力/Agent定义版本与运行时能力动态装配详细设计.md)
- [Agent 能力配置与发布架构](../03-Agent与能力/Agent能力配置与发布架构.md)
- [当前设计文档总览](../01-平台总览/设计文档总览.md)

