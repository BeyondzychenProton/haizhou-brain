# SPEC-01：账号激活与密码管理

状态：**实现已落地；本轮前端类型检查/35项单测/生产构建通过，认证服务相关定向用例包含在 Java 总套件内并通过；HTTP、真实 MySQL 与浏览器端到端验收未执行**。编号：FE-01。基线日期：2026-10-09。优先级：激活 P1，日常改密 P2。

本文件是实施规格，不代表页面已交付。共同约定见 [SPEC-00](SPEC-00-公共契约与实施约定.md)，实施顺序见 [总览](实施总览与验收矩阵.md)。

## 1. 范围与当前事实

- 补匿名账号激活入口，复用一次性凭据、24 小时有效期、密码哈希与限流逻辑。
- 同一密码页面支持“必须修改初始密码”和“日常修改密码”，明确成功后的可信会话刷新。
- 第一位管理员仍由既有服务端初始化流程创建；本规格不增加匿名注册或忘记密码重置。
- 已有 `POST /api/v1/auth/activate`；当前前端未封装，也没有激活路由。
- 激活成功返回空响应，**不创建登录会话**；用户随后使用账号和新密码登录。
- 已有改密服务校验当前密码、新密码长度 12–128；成功提高 `auth_version`，控制器刷新当前会话。
- 现有路由把不需要强制改密的用户从 `/password/change` 重定向；页面文案只针对初始管理员密码。
- 当前 HTTP 客户端对所有 401 自动跳登录，因此激活凭据错误和当前密码错误需要局部处理。

依据：[认证控制器](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/auth/AuthController.java)、[密码服务](../../../haizhuo-brain-security/src/main/java/com/haizhuo/brain/security/identity/PlatformIdentityService.java)、[用户管理服务](../../../haizhuo-brain-security/src/main/java/com/haizhuo/brain/security/identity/PlatformUserManagementService.java)、[当前路由](../../../haizhuo-brain-web/src/router/index.ts)。

## 2. 页面、导航与状态

| 入口 | 可访问主体 | 行为 |
| --- | --- | --- |
| `/activate`（拟新增） | 匿名用户 | 手动粘贴激活凭据，输入新密码及确认密码 |
| 登录页“激活账号” | 匿名用户 | 导向激活页；管理员复制的凭据仍由用户自行输入 |
| `/password/change` | 已登录用户 | `mustChangePassword=true` 为强制模式，否则为日常模式 |
| 侧栏账号菜单“修改密码” | 已登录且无需强制改密 | 导向日常模式；成功后返回员工页 |

激活页状态为 `EDITING → SUBMITTING → ACTIVATED`，错误回到 `EDITING`；成功页显示“账号已激活，请登录”。
激活凭据与密码仅保存在组件内存；不得写 localStorage、日志、埋点、路由查询参数或错误详情。
首版采用凭据粘贴，不生成含凭据的 URL；现有管理员复制凭据行为保持。
已经登录的用户打开激活页时，显示当前已登录及退出入口，禁止提交激活，避免误以为切换账号。
离开页面和成功后清空凭据及密码；重试时清空密码，保留凭据输入供用户核查。

强制模式标题为“请设置新的登录密码”，不限定管理员；不能取消绕过，可退出登录。
日常模式标题为“修改密码”，有取消按钮；关闭或返回不改变账号状态。
密码不 trim；按原值校验 12–128 字符、确认一致，当前密码必填且最多 128 字符。
提交中禁用重复提交；切换路由后的迟到响应不得重新展示密码或改写另一账号的页面状态。

## 3. 前后端契约

| 状态 | 方法与路径 | 请求/响应 |
| --- | --- | --- |
| 已有 | `GET /api/v1/auth/csrf` | 返回 `headerName,parameterName,token`，写请求沿用统一客户端 |
| 已有 | `POST /api/v1/auth/activate` | `{activationToken,newPassword}`；成功 200 空响应 |
| 已有 | `POST /api/v1/auth/login` | `{mobile,password}`；返回 `userId,roles,mustChangePassword` |
| 已有 | `POST /api/v1/auth/password/change` | `{currentPassword,newPassword}`；返回同形 `LoginResponse` |
| 已有 | `GET /api/v1/auth/me` | 返回 `userId,mobileMasked,roles,mustChangePassword` |
| 已有 | `POST /api/v1/auth/logout` | 注销当前会话，返回空响应 |
| 前端新增 | `activateAccount()` 与局部 401 处理选项 | 不新增后端认证端点、不更改已有响应形状 |

`activationToken` 必填，最大 512；新密码前端按服务规则 12–128 校验，后端仍为最终校验者。
当前认证失败为 `401 {code:"AUTHENTICATION_FAILED",message:...}`；不能据此区分过期、已消费或错误凭据。
激活统一提示“激活未完成，请核对凭据，稍后重试或联系管理员重新获取”，不推断过期/已消费/限流原因。
参数错误为400 `INVALID_REQUEST`；当前Redis限流累计5次失败、15分钟窗口，同样抛统一401，无独立429或剩余时间头。
页面不据客户端次数显示精确解禁倒计时；实际启用的Redis装配需在接口测试核对。
403 时提示请求验证失败并允许刷新页面；依赖故障不显示“密码错误”。

改密请求 401 时先局部处理，再读取 `/me`：仍有效则提示当前密码不正确；会话无效则清理登录态并导向登录。
该策略不改变后端错误码；`/me`的401才认定会话无效，503/网络失败显示状态待核对，不把停用或依赖故障认定为密码错误。
改密成功采用返回值更新认证状态，并读 `/me` 校准；校准失败提示会话需刷新，不能悄悄保留旧身份。
登录、改密、退出成功后清理客户端缓存的 CSRF 值；下次写请求重新获取，防止会话轮换后复用旧值。
其他独立登录会话因 `auth_version` 变化失效，下一次请求触发重新登录；不通过客户端传播认证凭据。
同一浏览器标签页共享Cookie，可能已经使用轮换后的同一会话；不能把“另一个标签页”直接等同独立旧会话。
改密成功广播无凭据的“认证状态已变化”信号，其他标签页清理CSRF缓存并读 `/me`；无广播能力时在恢复焦点后校准。

## 4. 路由守卫设计

1. 公共激活页不触发自动 `/me` 的失败跳转；匿名可直接打开。
2. 需要认证的页面仍先加载可信当前用户；没有会话则去登录。
3. 必须改密用户仍只能访问改密、退出及必要认证读取；后端限制保持。
4. 移除“非强制用户禁止访问改密页”的规则，保留普通用户不得进入管理员页面的规则。
5. 激活返回登录页时不自动发送登录请求、不预填密码；手机号可以由用户自己输入。
6. 当前密码错误不由全局拦截器先跳走；其他业务接口 401 保持原有退出流程。

## 5. 模块与文件入口

| 模块 | 文件入口 | 工作 |
| --- | --- | --- |
| Web | [auth.ts](../../../haizhuo-brain-web/src/api/auth.ts) | 补激活封装与响应类型 |
| Web | [httpClient.ts](../../../haizhuo-brain-web/src/api/httpClient.ts) | 受控局部 401 策略、CSRF 缓存重置 |
| Web | [认证 Store](../../../haizhuo-brain-web/src/stores/auth.ts) | 改密返回值、可信状态刷新与错误分支 |
| Web | [路由](../../../haizhuo-brain-web/src/router/index.ts) | 新入口与强制/日常模式守卫 |
| Web | `src/views/auth/ActivationView.vue`（拟新增） | 激活表单及成功状态 |
| Web | [PasswordChangeView.vue](../../../haizhuo-brain-web/src/views/auth/PasswordChangeView.vue) | 复用两种模式、表单及失败处理 |
| Web | [LoginView.vue](../../../haizhuo-brain-web/src/views/auth/LoginView.vue)、[AppLayout.vue](../../../haizhuo-brain-web/src/layouts/AppLayout.vue) | 导航入口 |
| API/Security | [AuthController](../../../haizhuo-brain-api/src/main/java/com/haizhuo/brain/api/auth/AuthController.java)、[安全装配](../../../haizhuo-brain-bootstrap/src/main/java/com/haizhuo/brain/bootstrap/security/PlatformSecurityConfiguration.java) | 复用并覆盖契约测试 |

## 6. 实施清单

- [x] FE-01-I01：对激活、改密和安全过滤的实现符号执行 impact，核对错误与限流契约。
- [x] FE-01-I02：补 API 类型、局部 401 处理选项及 CSRF 缓存重置；禁止影响其他业务接口的认证失效处理。
- [x] FE-01-I03：实现激活页面、匿名路由和登录入口，明确成功后必须登录。
- [x] FE-01-I04：改密页面复用强制/日常模式，增加长度校验和侧栏入口。
- [x] FE-01-I05：调整守卫、认证 Store 与迟到响应保护，覆盖会话轮换、独立会话失效和同浏览器状态同步。
- [ ] FE-01-I06：前端单元测试、生产构建，以及认证服务/限流单元测试通过；HTTP、真实 MySQL、浏览器端到端验收仍待执行。

## 7. 持久化、兼容与回退

本切片无需新表或 Flyway 迁移；沿用一次性 Token 消费事务、身份审计和密码版本。
任何失败都不由前端补写账号状态；激活重复提交由现有服务拒绝，密码重复提交不能推断上次是否成功。
改密请求超时提示“结果尚未确认”，先校准当前会话；会话失效后引导用户使用新密码尝试登录。
密码和凭据不写恢复草稿；刷新页面只恢复非敏感 UI 状态。
回退为关闭新增入口及恢复守卫；已激活账号和已更新密码继续由既有后端处理，不回滚身份数据。

## 8. Given / When / Then 验收

| 编号 | Given / When / Then |
| --- | --- |
| FE-01-A01 | Given 有效凭据；When 提交合规密码；Then 显示激活成功、尚未登录，随后可用新密码登录。 |
| FE-01-A02 | Given 同一凭据已消费或过期；When 再提交；Then 显示通用凭据无效提示，无自动跳页或账号信息泄露。 |
| FE-01-A03 | Given 密码少于 12、超过 128 或确认不一致；When 提交；Then 前端阻止；绕过前端仍被服务拒绝。 |
| FE-01-A04 | Given 必须改密用户；When 访问工作台/管理页；Then 仍去改密；完成后可信会话刷新且可进入工作台。 |
| FE-01-A05 | Given 普通有效用户；When 从侧栏改密；Then 页面可访问、可取消，成功后保持当前可信身份。 |
| FE-01-A06 | Given 有效会话且当前密码错误；When 改密；Then 停留表单提示错误；会话已失效时才跳登录。 |
| FE-01-A07 | Given 两个独立登录会话及同浏览器多标签页；When 完成改密；Then 当前会话继续、独立旧会话失效，同Cookie标签页校准用户/CSRF且不被误强制退出。 |
| FE-01-A08 | Given 网络超时/403/503；When 提交；Then 无成功提示、可恢复，密码和凭据不进入 URL、日志或持久缓存。 |
| FE-01-A09 | Given 已登录另一账号；When 打开激活页；Then 需退出后激活，不能因激活响应切换当前账号。 |

## 9. 测试层级与完成标准

源码层：核对旧认证规则、CSRF 与返回形状；单元层：守卫矩阵、密码校验、局部 401/迟到响应。
API 契约层：激活消费、重发后旧凭据、改密版本失效及限流；真实 MySQL 层：凭据并发消费和身份事务。
浏览器层：匿名激活、两种改密模式、错误留页、同浏览器标签页同步及独立会话失效；真实服务 E2E：完整激活→登录→改密。
构建执行 `npm --prefix haizhuo-brain-web run build`；后端变更时执行受影响认证模块定向测试。
完成标准：全部 FE-01 验收有实际记录，认证边界未放宽，新增页面没有敏感数据持久化。
以上“仅设计与源码校准”是设计基线记录，不代表本轮状态。最新代码级证据及未执行层级见[2026-10-09 实施报告](../../07-测试报告/2026-10-09功能闭环实施报告.md)；HTTP、真实 MySQL 和浏览器端到端仍未验收。
