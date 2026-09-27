# Session 与 Run 历史、队列及运行中引导详细设计

> 状态：待开发
>
> 版本：v1
>
> 范围：可信 Session 下的历史时间线、普通消息排队、运行中引导（Guidance）、取消与可恢复调度；不包含渠道供应商接入和具体业务工具实现。

## 1. 要解决的问题

当前平台已经能把一条输入创建为一个 Run，并持久化该 Run 的事件。但它仍是“单次执行视角”：页面只能读取一个 Run 的事件，后续普通消息不能排队，运行中的 Agent 也无法在工具返回后接收新的引导信息。

本设计把这三个概念分开：

| 概念 | 含义 | 何时消费 |
| --- | --- | --- |
| 历史消息 | 已经发生的用户输入、助手输出、失败和控制结果 | 打开 Session 时按时间线读取 |
| 普通排队消息 | 需要作为一个新的 Run 执行的用户输入 | 当前 Run 完成后 |
| 运行中引导 | 对当前 Run 后续推理的补充、纠偏或约束 | 当前工具完成、下一次模型决策前 |

这里的“抢占”特指运行中引导，不是粗暴终止当前 Run，更不是用高优先级任务覆盖低优先级任务。

## 2. 心智模型与对象关系

Session 是一段持续对话的可信容器；Run 是该 Session 中一次可审计的执行；消息是用户、模型或控制者在两者上留下的时间线事实。

```text
Session
├── 历史时间线（可分页读取）
│   ├── 用户输入 / Run A
│   ├── 工具执行状态 / Run A
│   ├── 助手输出 / Run A
│   ├── 用户输入 / Run B（普通排队）
│   └── 引导消息 / Run C（当前运行中）
│
├── 当前运行槽：最多一个 RUNNING Run
└── 普通 Run 队列：按 Session 内顺序等待
```

身份边界保持不变：Session 由可信登录用户拥有；任何历史、排队项、引导和取消请求都必须通过 `Session.userId` 校验。渠道只负责把外部消息归一化，不能直接指定 Run、优先级或引导身份。

## 3. 目标状态机

### 3.1 Run 主状态

```text
QUEUED ──领取──> RUNNING ──正常完成──> SUCCEEDED
   │                 │
   │                 ├──发生错误──> FAILED
   │                 ├──收到取消──> CANCEL_REQUESTED ──检查点确认──> CANCELLED
   │                 └──收到引导──> RUNNING（追加指导后继续）
   └──取消排队项──────────────────────────────────────> CANCELLED
```

`CANCEL_REQUESTED` 表示控制请求已经被可靠记录，但当前工具可能仍在执行；它不是“已经停止”的假象。只有运行时在安全检查点确认不再进行下一步模型或工具调用后，才进入 `CANCELLED`。

### 3.2 两种后续消息的差异

```text
普通消息：Run A 正在执行
用户发送 B → Run B = QUEUED → A 结束 → worker 领取 B

引导消息：Run A 正在执行工具
控制者发送 G → G 写入 A 的引导收件箱
工具返回 → 运行时检查收件箱 → 将 G 注入下一次模型调用 → A 继续
```

同一 Session 内普通消息严格 FIFO。引导消息不会创建新的普通 Run，不会改变既有排队项顺序，也不会伪装为用户输入。

## 4. 历史时间线

### 4.1 事实来源

继续以 `platform_agent_run` 与 `platform_agent_run_event` 保存 Run 及其原始事件；新增面向 Session 查询的时间线投影或查询视图。对前端只暴露经过分类的项目：

| 时间线类型 | 来源事件 | 展示内容 |
| --- | --- | --- |
| `USER_MESSAGE` | `USER_INPUT` | 用户输入 |
| `ASSISTANT_MESSAGE` | `RUN_COMPLETED` | 助手输出 |
| `GUIDANCE` | `RUN_GUIDANCE_ACCEPTED` | 引导者与摘要；按权限决定是否展示正文 |
| `SYSTEM` | 开始、失败、取消等事件 | 状态说明，不展示内部异常堆栈 |

历史查询使用稳定游标 `(occurred_at, run_id, sequence_no)` 倒序分页，避免仅按时间戳造成并发事件重复或遗漏。查询必须先校验 Session 归属，再限定 `run.session_id`。

### 4.2 前端行为

进入会话时先读取最新一页历史，再向上滚动加载更早内容。当前 Run 仅以增量事件更新，不再清空已加载的消息。失败、取消和引导均作为时间线事实显示，因此刷新或重新登录后仍可恢复上下文。

## 5. 普通 Run 队列

### 5.1 入队规则

- 每个 Session 最多一个 `RUNNING` 或 `CANCEL_REQUESTED` Run。
- 新普通消息总是创建新的 `QUEUED` Run；不因已有活跃 Run 直接返回冲突。
- 默认每个 Session 最多保留 20 个未完成 Run，超过上限返回明确的容量错误。
- 客户端幂等键在“用户 + Session + 请求”范围唯一，重试返回原 Run，不重复入队。

### 5.2 调度规则

worker 领取 Run 时遵守“每个 Session 串行、跨 Session 可并行”的约束。v1 可使用全局 FIFO 领取；当 worker 数量增加后，调度器应演进为按租户与 Session 轮转，避免一个高频 Session 长时间占用全局执行槽。

领取记录需要携带 `worker_id`、`lease_expires_at` 和心跳。worker 崩溃后，过期租约的 Run 回到可恢复状态或进入明确失败状态，不能永久停留在 `RUNNING`。

## 6. 运行中引导

### 6.1 引导消息模型

新增 `RunGuidance`，至少包含：`guidance_id`、`run_id`、`session_id`、`author_user_id`、`source`、`content`、`status`、`created_at`、`accepted_at`。

`source` 用于区分用户补充、管理员引导、渠道系统策略和自动安全策略。写入时需要验证：

1. Run 属于目标 Session，且 Session 与用户/管理员身份匹配；
2. Run 状态为 `RUNNING` 或 `CANCEL_REQUESTED` 之前的可引导状态；
3. 引导正文满足长度、内容安全和审计要求；
4. 不能由外部渠道伪造管理员或系统引导。

### 6.2 消费检查点

引导的安全时机是“工具已经得到确定结果，模型尚未决定下一步”。运行时每轮遵循：

```text
模型决定下一步
  ↓
如需工具：执行一个工具调用并持久化结果摘要
  ↓
检查取消标记
  ├── 已取消：停止后续推理，写入 CANCELLED
  └── 未取消：读取尚未消费的引导
                  ↓
            将引导按受信来源写入下一轮模型上下文
                  ↓
            模型基于“工具结果 + 引导”决定下一步
```

引导不是直接修改已经提交的工具结果，也不能绕过工具授权。它只影响后续模型推理，例如“不要展示金额，改为总结异常订单”。

### 6.3 重要边界

不能承诺在长时间、不可取消的工具调用中瞬时生效。若工具正在执行，引导先可靠落库；工具返回后才会被消费。对需要更快响应的工具，后续应定义协作式取消协议与合理超时，但不能用线程强杀代替业务补偿。

## 7. 运行时改造

当前 `AgentScopeRuntime` 对一次 `agent.call(...)` 进行整体阻塞等待，无法在“工具返回与下一轮模型调用之间”插入平台逻辑。实现本设计时，平台需要掌握 ReAct 的分步循环，或为 AgentScope 增加等价的工具后钩子与受控上下文刷新点。

目标接口语义如下：

```text
RunControlInbox.poll(runId)
  -> pending guidance + cancellation request

RunStepExecutor.executeNext(runSnapshot, conversationContext)
  -> model decision / tool result / final answer

RunCoordinator
  -> 在每个安全检查点合并控制消息，再决定继续、停止或完成
```

`RunCoordinator` 是平台层对象，负责身份、队列、审计、租约和状态迁移；AgentScope 只负责在已给定上下文中执行一小步推理。这样渠道、前端和未来的多 Agent 运行时都复用同一套控制语义。

## 8. API 草案

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/sessions/{sessionId}/timeline?before=&limit=` | 读取 Session 历史时间线 |
| `GET /api/v1/sessions/{sessionId}/runs?state=` | 查询队列和历史 Run 摘要 |
| `POST /api/v1/sessions/{sessionId}/runs` | 普通消息入队；返回 Run 与队列位置 |
| `POST /api/v1/sessions/runs/{runId}/guidance` | 向当前 Run 写入引导消息 |
| `POST /api/v1/sessions/runs/{runId}/cancel` | 取消排队项或请求取消运行中 Run |
| `GET /api/v1/sessions/runs/{runId}/controls` | 查询取消与引导的处理状态，仅用于运行详情 |

所有写接口从登录主体推导用户和角色；不得接受客户端提交的 `userId`、`tenantId`、`workerId` 或内部优先级。

## 9. 实施顺序与验收

1. **历史时间线**：补充跨 Run 查询、前端分页与恢复展示。
2. **普通队列与取消**：允许同 Session 多 Run 排队，增加容量上限、取消状态与 worker 租约。
3. **运行中引导**：新增引导收件箱、事件审计和安全检查点。
4. **分步运行时**：将当前整体 `agent.call(...)` 改为可在工具结果后读取控制消息的编排方式。
5. **恢复与多 worker**：租约续期、过期接管、跨 Session 公平调度。

核心验收场景：

- 重新进入同一 Session，能按顺序看到此前多个 Run 的输入、输出、失败和取消。
- Run A 执行时发送普通消息 B，B 显示排队并在 A 结束后自动执行。
- Run A 的工具完成后提交引导 G，下一次模型决策实际收到 G，且时间线可追溯 G 已被消费。
- 工具执行中提交取消或引导，系统如实显示“等待检查点”，不会误报已停止或已生效。
- worker 重启后，过期领取的 Run 不永久卡在运行中。

## 10. 非目标

- 不在本阶段实现任意工具的强制中断、分布式事务回滚或跨供应商工具补偿。
- 不允许普通消息自动越过当前 Run；它们只能排队。
- 不将引导正文直接拼接到系统提示词而绕过来源、审计与权限检查。
- 不因引导机制改变用户、管理员、渠道身份的可信来源规则。
