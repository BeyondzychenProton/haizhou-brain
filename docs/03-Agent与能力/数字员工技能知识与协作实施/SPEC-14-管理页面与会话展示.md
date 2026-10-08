# SPEC-14 管理页面与会话展示

状态：管理能力/资产 API、Session 角色选择、Run 事件和结果引用 UI 已接入；本轮 Vue 类型检查与生产构建通过。真实浏览器发布/审核、A→B→A、断线恢复与 INTERNAL 可见性验收尚未执行。任务：B5、C3、C8、C13。依赖：对应已实现 API；设计依据：[详细设计](../数字员工技能知识与只读协作改造详细设计.md) §14、§15。

## 管理页面

沿用 DefinitionPanel.vue 与当前管理 API：最小员工创建、运行方式、技能知识审核修订、固定成员/版本、预算、校验问题定位和脱敏发布预览。零工具 V2 可保存；冲突保留编辑内容，禁止自动覆盖。

页面说“普通员工 / 使用技能与知识 / 使用只读专家 / 专家自主协作”，未验收方式不可发布。资产编辑/审核和员工版本发布分别呈现，不把 capability 启用等同员工已经绑定。

## Session 数据与展示

- SessionView/useSessionStream/runEventPresenter 按 ROOT/role/delegation/invocation 分组，不再把所有文本都拼入 runId-assistant。
- 选择专家作用于下一次提交；活动/排队 Run 显示各自固定目标。直接专家的根答案显示真实专家身份。
- 根完成后用 resultId 获取完整正文校准；专家卡片显示多轮关键状态和可见结果引用。
- 自治展示任务与 Team 收尾的确定性，区分一轮结束、任务完成、团队收尾和正式答案；原始 inbox/内部推理不展示。
- RECOVERY_REQUIRED 显示需要核查和可用结果，不提供未经批准的继续/重试按钮。

## 快照与流

新增 snapshot API 在同一一致性读边界获取投影和 snapshotCursor。页面先应用 snapshot，再从 cursor 接入持久事件；format=v2 兼容原编号，streamOffset 只用于瞬时增量。

事件补读/结果请求均由服务端按属主与 visibility 过滤，不能只靠前端隐藏。游标过期重新读 snapshot，INTERNAL 页 nextCursor 仍前进；Run 完成不关闭 Session 级流。

## 验收

1. 管理员完成资产发布→员工校验发布→用户新 Session 使用，旧会话版本冻结。
2. A→B→A、并行专家与三轮修订展示身份正确，根/子答案不混；页面刷新/断线保留持久关键事实与完整答案。
3. 慢客户端、重复事件、旧 attempt 晚到、游标过期不重复卡片或丢失根终态。
4. Markdown/JSON/错误正文安全渲染，用户看不到 INTERNAL/思考链/密钥/未授权资料。
5. `npm --prefix haizhuo-brain-web run build` 通过，浏览器真实流程单列报告；构建不能代替浏览器验收。
