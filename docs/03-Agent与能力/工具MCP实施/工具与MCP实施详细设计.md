# 工具与 MCP 实施详细设计

状态：实施中（2026-09-28）。[概要设计](工具与MCP实施概要设计.md)与[设计基线](../工具与MCP统一配置及授权边界设计基线.md)共同约束实现。

## 数据与事务

新迁移只追加 `mcp_connection`、`mcp_discovery_snapshot`、`mcp_tool_revision` 和跨修订模型名占用表 `mcp_model_tool_name_claim`，并复用 `capability_definition/revision`、员工绑定、能力包、`platform_agent_run_spec` 和 `platform_tool_execution`。连接保存稳定编码、允许的 HTTP(S) 目标、启停和配置修订，不存 Token。候选保存发现快照及时间，不等于授权。批准一项子工具时，在同一事务写入能力修订和 MCP 来源映射；模型名占用以数据库唯一键保证并发审批不碰撞。同一能力的新修订可沿用原名。旧工具修订不可覆写，Schema/说明/连接边界的实质变化使用新修订。紧急停用立即收窄；停用或再启用均递增连接修订，旧工具映射不会因此复活，须重新发现/批准/发布。

管理请求由平台管理员身份发起；连接与批准必须审计操作者、原因及差异。发现是事务外网络操作，落库时再核对连接修订。Run 创建同样先在事务外逐用户发现，然后由现有 `SessionRunStore.createRun` 固定发布版本及可见名称。预检失败不退化为空列表；调用时再次核对当前启停和 MCP 的最终授权。

## 协议适配

复用 AgentScope Java 2.0.3 所锁定的 MCP Java SDK 0.17.2 Streamable HTTP 客户端，**每个用户请求创建短生命周期客户端**，不把 Token/列表缓存放进共享 Harness 模板。源码核对发现 AgentScope `McpClientWrapper.listTools()` 只取首个 `ListToolsResult`，故分页由同版本底层 SDK 的 `McpSyncClient.listTools(cursor)` 薄适配，不重写 JSON-RPC。`initialize`、完整 `tools/list`、`tools/call` 均使用同一终端用户 Token。SDK 对部分 HTTP 401/403 的原始状态传播不稳定：模拟 Server 的 `initialize` 认证错误体可识别为登录失效，`tools/call` 返回 MCP `isError` 加 `structuredContent.code=PERMISSION_DENIED`、`effect=NOT_EXECUTED` 可安全识别为无权限；没有确定的未执行证据时写结果保持未知。真实 Server 的错误格式仍须逐项联调。生产连接不接受任意内网/本机 URL；模拟器使用独立的显式测试开关。HTTP 超时必须有上限。

`CapabilityCatalogEntry.type=TOOL` 走已有平台 Grant/Resource/Action；`type=MCP` 跳过平台业务 Grant 与 ResourcePolicy，但仍校验用户激活、已发布 Run 视图、启停、确认、凭据和已批准来源。发现时和调用前都比对已批准的输入/输出 Schema 与只读声明；变化必须重新审核为新修订。确认后、真正执行前再次查用户/能力/连接启停，避免预检与执行之间撤权被跳过。调用返回 401、确定未执行的 403、不可用、工具 `isError` 与结果不确定时分别映射；发送后的写超时一律进入待核查而非重试。

## 模拟身份

模拟凭证是 `base64url(payload).base64url(HMAC-SHA256(payload, secret))`；payload 包含归一化测试手机号、`aud`、`exp`。服务端逐次验证签名、受众、过期时间。Secret 只能由测试配置注入，不提交默认生产密钥。手机号只是测试身份映射属性，不是密钥；原始手机号和 Token 不进日志/模型。模拟器 `tools/list` 对不同用户返回不同集合，`tools/call` 还按目标资源所有者判权。

## 恢复与失败

调用前确定的 401/403 可落定为认证/授权失败；网络请求一旦可能发出，写结果即视为未知，需用原操作键核查。Run 取消不伪装为写回滚。`tools/list` 认证失效的新 Run 在模拟器切片中明确创建失败且不入队；生产身份系统的取得、续期与产品提示仍等待实际对接决定。

## SPEC 与任务顺序

1. [SPEC-01 连接、发现与批准](SPEC-01-连接发现与批准.md)：迁移、管理 API、候选和修订。
2. [SPEC-02 员工发布与 Run 可见性](SPEC-02-员工发布与Run可见性.md)：冻结与收窄。
3. [SPEC-03 执行、错误与恢复](SPEC-03-执行错误与恢复.md)：MCP 路由、逐次判权、写结果。
4. [SPEC-04 模拟 Server 与身份](SPEC-04-模拟Server与身份.md)：测试服务、Token、跨用户验证。
5. [SPEC-05 前端与功能验收](SPEC-05-前端与功能验收.md)：页面、功能测试与验收记录。

每步以单元/集成检查为门槛，再进入下一步；最后执行后端模块测试、前端构建、模拟 Server 功能测试。测试容器上的 MySQL 迁移通过不等于既有部署数据库升级已验证；真实身份/Server 和模型端到端未运行时必须在报告中分别标明。
