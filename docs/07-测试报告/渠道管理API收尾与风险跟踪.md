# 渠道管理 API 收尾与风险跟踪

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-28 |
| 依据 | 提交 `db70736`（渠道管理 API 与框架渠道运行时）；设计方案见 [渠道发送调度、提升机制与受控团队设计方案](../04-渠道与执行/渠道发送调度、提升机制与受控团队设计方案.md) |
| 范围 | 方向 4 交付后的**待办与风险跟踪**，共 2 项 |
| 结论 | 1 项联调前必做（V16 真机迁移），1 项可延后但需明确替代方案（审计端口） |

## 0. 待办速览

| 编号 | 事项 | 风险等级 | 是否阻断联调 | 当前状态 |
| --- | --- | --- | --- | --- |
| R1 | V16 迁移的真机 MySQL 执行验证 | **低**（低概率 / 高影响） | **是** | 编译与 H2 已验证；真机未执行 |
| R2 | 审计端口未接入，管理操作无结构化留痕 | **中**（治理缺口，非功能缺陷） | 否 | 仅控制层应用日志；审计需独立立项 |

风险等级的判定口径：**R1** 是"失败概率低但一旦失败会阻止应用启动"，因此按"联调前必做"处理；**R2** 不影响功能正确性，属于上线前需要补齐的治理能力。

---

## R1. V16 迁移的真机验证

### R1.1 问题背景

V16 为渠道账号补上"会话粒度"配置，内容只有一条语句：

```sql
ALTER TABLE platform_channel_account
    ADD COLUMN dm_scope VARCHAR(32) NOT NULL DEFAULT 'PER_PEER';
```

**已完成的部分（前提，勿遗漏）：**

1. **编译已验证**：全量模块主代码与测试代码编译通过。
2. **H2 侧已验证**：新增 `ChannelAccountSessionScopeMigrationTest`，在 H2（`MODE=MySQL`）上真实执行 V16 脚本，断言：
   - 列被正确添加，类型为字符串、非空、带 `DEFAULT 'PER_PEER'`；
   - **存量行被回填为 `PER_PEER`**（而非框架默认的 `MAIN`），即回填语义是"收紧"而非"放宽"；
   - 迁移后新插入的行在不显式指定时同样拿到 `PER_PEER`；
   - 既有唯一约束 `(provider, external_account_key)` 未被破坏。
3. **低风险依据**：这是纯 `ADD COLUMN` + 常量 `DEFAULT` —— **没有数据回填 DML、没有索引重建、没有类型转换**，MySQL 8 上属瞬时 DDL。

**尚未完成的部分：**

- V16 从未在**真机 MySQL**上执行过。
- 注意口径：上一轮真机验证跑的是 scratch 库的 **V1–V15** 与真库的 V13→V15 升级路径，**不包含 V16**。

> **前提修正（如实记录）**：此前"仅完成编译与 H2 侧验证"的表述**并不准确**。当时的真实状态是：V16 既没有迁移脚本测试，测试用手工建表也与真实 schema 不一致（缺 `dm_scope`，注释仍写着"与 V15 保持一致"），只是恰好没有测试走到该列才未暴露。本轮已补齐上述两项，才使"H2 侧已验证"这一说法成立。

### R1.2 影响范围

| 维度 | 说明 |
| --- | --- |
| 直接影响 | 渠道账号绑定的会话粒度配置列 `dm_scope`；渠道运行时的装配投影依赖它 |
| 不受影响 | 入站受理、出站投递、等待提升、Web 侧既有功能——V16 只加列，不改任何既有语义 |
| 失败时的后果 | **Flyway 在已应用但失败时会阻止应用启动**，属启动级故障。这是把等级定为"联调前必做"的唯一原因 |
| 数据面 | 无数据改写；存量行仅被填入一个常量默认值，可逆 |

### R1.3 风险等级

**低**。理由：单条 `ADD COLUMN` + 常量默认值，无回填 DML、无锁表风险操作；且 H2 侧已确认脚本可在 MySQL 兼容库执行、回填语义正确。之所以仍要求联调前执行，是因为**失败后果是启动级**（低概率 / 高影响），而非脚本本身复杂。

补充：当前 `platform_channel_account` 数据量极小（仅验证期造的数据），**不存在大表 DDL 锁的实测风险**；若未来生产已有大量绑定，需另行评估 DDL 窗口。

### R1.4 联调前应执行的动作

**责任边界**：动作 1–3 由**开发（本轮实现者）**执行；动作 2 需要**运维/环境负责人**提供真库访问与执行窗口；若迁移失败，回滚决策由**开发 + 运维**共同确认。

**动作 1 — scratch 库验证脚本可执行性（不动真库）**

```bash
cd "D:/code project/working/haizhuo-brain/haizhuo-brain"
MYSQL="/d/code environment/mysql/mysql-8.0.45-winx64/bin/mysql"
"$MYSQL" -h127.0.0.1 -P3306 -uroot -p123456 --default-character-set=utf8mb4 \
  -e "DROP DATABASE IF EXISTS haizhuo_brain_probe; CREATE DATABASE haizhuo_brain_probe CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

for f in $(ls haizhuo-brain-infrastructure/src/main/resources/db/migration/V*.sql | sort -V); do
  name=$(basename "$f")
  if "$MYSQL" -h127.0.0.1 -P3306 -uroot -p123456 --default-character-set=utf8mb4 \
       haizhuo_brain_probe < "$f" 2>/tmp/migerr.txt; then
    echo "OK   $name"
  else
    echo "FAIL $name"; head -3 /tmp/migerr.txt
  fi
done
```

**动作 2 — 真库走生产升级路径（V15→V16）**

按正常启动流程拉起应用，由 Flyway 自己迁移：

```bash
java -jar haizhuo-brain-bootstrap/target/haizhuo-brain-bootstrap-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=8081
```

**动作 3 — 核对结果**（见 R1.5 的 SQL 与接口检查）

**动作 4 — 回滚预案（仅在动作 2 失败时）**

V16 纯加列，可逆：`ALTER TABLE platform_channel_account DROP COLUMN dm_scope;`，**并同步处理 `flyway_schema_history` 中该条记录**（失败的迁移不会写入成功记录，但需确认 `success=0` 的残留行已被清理），否则下次启动会因校验和不一致继续报错。

### R1.5 验收标准

逐条可判定，全部满足才视为通过：

| 编号 | 验收项 | 判定方式 |
| --- | --- | --- |
| A1 | scratch 库 V1–V16 全部执行成功 | 动作 1 输出中**无 `FAIL`**，末行为 `OK V16__channel_account_session_scope.sql` |
| A2 | 真库升级成功 | 应用启动日志出现 `Successfully applied ... now at version v16`，且启动完成无 `APPLICATION FAILED` |
| A3 | schema 正确 | `SELECT column_name,column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='haizhuo_brain' AND table_name='platform_channel_account' AND column_name='dm_scope';` → `dm_scope / varchar(32) / NO / PER_PEER` |
| A4 | 存量行已回填且无空值 | `SELECT COUNT(*) FROM platform_channel_account WHERE dm_scope IS NULL OR dm_scope<>'PER_PEER';` → `0` |
| A5 | 迁移记录已登记 | `SELECT version,success FROM flyway_schema_history WHERE version='16';` → `16 / 1` |
| A6 | 装配链路读到新列 | 以管理员身份 `GET /api/admin/v1/channels/runtime` 返回 200；若有启用绑定，对应渠道的 `sessionScope` 为 `PER_PEER` 而非 `MAIN` |
| A7 | 既有能力无回归 | 全量后端测试 `BUILD SUCCESS`（含 `ChannelAccountSessionScopeMigrationTest`） |

---

## R2. 审计端口未接入

### R2.1 问题背景

平台层（`haizhuo-brain-platform`）按既有架构约定**不依赖日志框架**——该模块内零 `org.slf4j` 使用。这是本轮在编译期才暴露的约束（在 `ChannelAdministrationService` 里使用 `Logger` 直接编译失败）。

因此 `ChannelAdministrationService` 无法自行记录审计日志。当前采取的分层做法是：

- **服务层**：`actorUserId` 已贯穿**所有写方法**签名（`createAccount` / `updateAccount` / `linkIdentity` / `revokeIdentity`），为将来接审计端口预留了调用点，但当前**未被消费**。
- **控制层**：`ChannelAdminController` 用应用日志记录操作（渠道功能与审计解耦，未破坏平台层分层约定）。

> 明确**不接受**的临时做法：为了留痕而在平台层硬加日志/持久化依赖——这会破坏既有的模块分层约定，且日志并非审计。

### R2.2 当前缺失的能力

| 能力 | 现状 |
| --- | --- |
| 结构化审计记录持久化（谁 / 何时 / 对象 / 变更内容 / 结果） | **完全缺失** |
| 按对象追溯（"这个渠道绑定被谁改过"） | **完全缺失** |
| 变更前后值对比 | **完全缺失** |
| 审计可查询、可导出 | **完全缺失** |
| 已具备的近似能力 | 控制层应用日志（非结构化、不入库、随日志轮转丢失、无法按对象检索） |

### R2.3 受影响的场景

| 场景 | 现状 | 缺口 |
| --- | --- | --- |
| 谁停用了某个渠道账号 | 仅应用日志一行 | 无法按 `bindingId` 查到完整操作史 |
| 谁把外部用户绑到了哪个平台用户 | 仅应用日志一行 | 身份绑定属敏感配置，缺少可追溯记录 |
| 事故复盘（"为什么这个渠道没人回复"） | 只能翻日志文件 | 绑定/解绑时点与操作者难以还原 |
| 合规审查 / 内控要求 | 无据可查 | 权限类变更通常要求审计留痕 |
| 变更历史（同一绑定的多次修改顺序） | **完全缺失** | 无前后值，无法判断"改成了什么" |

### R2.4 风险等级

**中**。定位为**治理缺口而非功能缺陷**：

- **不阻断联调**：渠道受理、投递、等待提升、管理 API 本身均工作正常；
- **但不宜带病上线**：渠道绑定与身份绑定是权限相关配置（决定哪些外部用户以哪个平台身份被受理），生产环境通常要求可审计。

### R2.5 审计端口落地前的临时替代方案

按推荐度排序：

1. **控制层结构化日志**（当前已具备，建议小幅增强）。现状已打印 `actor / bindingId / 变更字段`，可按需补齐 `变更前后值`，日志格式固定后即可被采集。
   ```
   channel account updated: actor=1 binding=sim-account enabled=false employee=2 scope=PER_PEER
   ```
2. **接入日志采集/检索**（若已有 ELK 等）。日志格式固定后，按 `channel account` 前缀 + `actor`/`binding` 关键字可做粗粒度检索——能回答"谁改的"，但**不保证留存**，也不具备审计所需的不可篡改性。
3. **组织措施兜底**（高风险窗口）。管理 API 的 `PLATFORM_ADMIN` 权限暂时只授予少数管理员；绑定/解绑变更走人工审批与变更记录，直至审计端口落地。
4. **明确不采用**：在平台层临时落库"伪审计表"——缺少统一端口会在后续接入时产生第二套语义，重复劳动且容易与真实审计冲突。

### R2.6 后续接入的前置条件

以下四项需要**先决策、后实现**，属独立事项，不在本轮范围内：

| 前置项 | 需要确定的内容 | 决策方 |
| --- | --- | --- |
| 端口契约 | 审计记录的字段集（操作者、对象类型与标识、动作、前后值、结果、时间、来源 IP/渠道） | 开发提出，产品/安全确认字段范围 |
| 存储方案 | 独立审计表（新增迁移）还是外部审计服务 | 开发 + 运维 |
| 一致性语义 | 审计写入与业务写入**同事务**（强一致、绑定成功即留痕）还是异步（最终一致、可能丢记录） | 产品/安全确认可接受语义，开发实现 |
| 保留策略 | 留存时长、是否可删除、是否需要防篡改 | 合规/安全 |

**责任边界**：R2 的**决策**属产品/安全；**实现**属开发；本轮仅完成"预留调用点 + 控制层日志"，不擅自扩大范围。

---

## 附：本轮为支撑本文档所做的补强

| 变更 | 说明 |
| --- | --- |
| 新增 `ChannelAccountSessionScopeMigrationTest` | 在 H2（`MODE=MySQL`）上真实执行 V16，验证加列、存量回填、新行默认值、唯一约束完好（1 用例） |
| 修正 `HarnessJdbcTestSupport.createChannelTables` | 测试手工建表补上 `dm_scope`，与 V15+V16 的真实 schema 对齐（此前缺列，导致 H2 侧无法覆盖 `ChannelAccountDirectory` 的读取路径） |
| 全量回归 | `BUILD SUCCESS` |

## 变更记录

| 日期 | 进展 |
| --- | --- |
| 2026-09-28 | 建立本收尾与风险跟踪：R1（V16 真机验证）与 R2（审计端口）的背景、影响、等级、动作与验收标准；同时补齐 V16 的 H2 迁移验证与测试建表一致性 |
