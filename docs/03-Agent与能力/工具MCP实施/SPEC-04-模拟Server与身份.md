# SPEC-04 模拟 Server 与身份

状态：实施中。依赖[详细设计](工具与MCP实施详细设计.md)。

提供可独立启动的本机 Streamable HTTP MCP 测试服务，支持 `initialize`、完整 `tools/list`、`tools/call` 和必要的通知。测试 Token 从归一化手机号、受众和有效期计算签名；必须校验 HMAC、受众、过期时间并拒绝伪造。两名测试用户拥有不同的可见工具；同一工具对目标资源还应验证归属。提供只读工具及带原操作键、幂等和结果查询的写工具。

Secret 仅从环境或测试启动参数注入，不能提交固定生产密钥。测试应覆盖无 Token、过期、错受众、签名篡改、列表隔离、跨用户资源拒绝、重复写不重复副作用、结果未知查询。模拟器结论不能代替真实 MCP Server 验收。

本轮落地：独立可启动的 `SimulatedMcpServer` 与双用户场景均已实现，真实 MCP Java SDK 经本机 HTTP 通过协议和负向身份测试；密钥不在代码中提供生产默认值。模拟器测试号仅是虚构号码，不能替代真实用户凭证。

确定性模型联调通过 AgentScope 2.0.3 的真实 Harness 注册冻结工具 Schema，模型发出带原始 JSON 内容的 `ToolUseBlock` 后由框架校验并挂起；测试再以用户 Token 调用本机 MCP 模拟服务，将结果作为 `ToolResult` 恢复模型。假模型必须同时给出参数 Map 与相应的原始 JSON，否则框架会先判参数校验失败，不能据此判断 MCP 链路。另有临时 MySQL 上的 `Run Worker → Tool Worker → Run Worker` 持久交接测试；这两项测试均不连接真实模型和真实 Server，且没有验证跨进程重启。
