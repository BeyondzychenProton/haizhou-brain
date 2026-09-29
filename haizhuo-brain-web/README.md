# 海卓智慧大脑 · 前端（haizhuo-brain-web）

海卓智慧大脑的 Web 控制台，面向普通用户提供“我的数字员工”对话入口，面向管理员提供能力目录、工具授权、MCP 工具、员工定义编排与用户管理界面。

前后端通过同源 Cookie 会话交互，开发环境由 Vite 代理 `/api` 到后端。

## 技术栈

- Vue 3（`<script setup>` + TypeScript）
- Vite 6 + `@vitejs/plugin-vue`
- Vue Router 4、Pinia 2
- Element Plus（含 `@element-plus/icons-vue`）
- axios、markdown-it + DOMPurify（消息渲染）

## 快速开始

前置：Node.js 18+，npm（仓库使用 `package-lock.json`）。

```powershell
npm install
npm run dev        # 开发服务器 http://localhost:5173，/api 代理到 http://localhost:8080
```

其他脚本：

```powershell
npm run type-check # vue-tsc 类型检查
npm run build      # 类型检查 + 生产构建，产物在 dist/
npm run preview    # 本地预览生产构建
```

后端需在本机 `8080` 启动，或按需修改 `vite.config.ts` 中的 `server.proxy`。

## 页面与路由

| 路由 | 页面 | 说明 |
| --- | --- | --- |
| `/login` | `views/auth/LoginView.vue` | 登录 |
| `/password/change` | `views/auth/PasswordChangeView.vue` | 强制修改密码（需登录） |
| `/app/employees` | `views/app/EmployeesView.vue` | 数字员工列表，创建/进入会话 |
| `/app/sessions/:sessionId` | `views/app/SessionView.vue` | 会话与运行主界面：SSE 流式消息、Run 取消/引导、工具审批与交互 |
| `/admin` | `views/admin/AdminView.vue` | 管理中心（需管理员）：能力目录、工具授权、MCP 工具、员工定义编排、用户管理 |
| `/` | — | 重定向到 `/app/employees` |

路由守卫在 `src/router/index.ts`：未登录跳转登录页，需改密时强制跳转改密页，非管理员访问 `/admin` 会被拦截。

## 目录结构

```
src/
├── api/           # 后端接口封装
│   ├── httpClient.ts   # axios 实例、CSRF、401 跳转
│   ├── auth.ts         # 登录/登出/当前用户
│   ├── app.ts          # 员工、会话、Run、时间线与工具决策
│   ├── admin.ts        # 管理端接口
│   └── runStream.ts    # 会话级 SSE（EventSource）封装与事件类型
├── components/         # 通用组件（Markdown 消息等）
├── layouts/            # 应用外壳（侧边栏 + 主区域）
├── presenters/         # 运行事件到视图模型的转换
├── renderers/          # 内容块渲染注册表
├── router/             # 路由与守卫
├── stores/             # Pinia：auth、app
├── utils/              # 通知等工具
└── views/              # 页面（auth / app / admin）
```

## 关键约定

- **CSRF**：非 GET 请求前先调用 `/api/v1/auth/csrf` 获取掩码后的 token 与 header 名，由 `httpClient` 自动写入请求头。不要开启 axios 的 cookie-to-header 自动化，否则会用原始 cookie 覆盖掩码值导致 403。
- **鉴权**：基于同源 Cookie，`withCredentials: true`；响应 401 时统一跳转登录页。
- **实时事件**：会话页使用 `openSessionStream` 打开会话级 SSE，`sessionCursor` 作为持久游标，断线补读与退避重连由调用方负责。
- **消息安全**：Markdown 经 markdown-it 渲染后再由 DOMPurify 清洗后展示。

## 构建产物

`npm run build` 输出到 `dist/`，可由后端或静态服务器托管；`dist/` 与 `node_modules/` 已在 `.gitignore` 中忽略。
