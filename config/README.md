# 外置运行配置

Spring Boot 从项目根目录启动时读取 `config/` 下的 YAML。应用配置直接写入对应文件，不依赖环境变量注入。包含密码或密钥的实际文件被 Git 忽略；提交的 `.example` 文件只保留占位值。

| 文件 | 用途 |
| --- | --- |
| `application.yml` | 公共端口、应用名与默认模型 |
| `application-local.yml` | 本地 MySQL、Redis、日志与模型服务 |
| `application-staging.yml` | 测试/预发环境配置 |
| `application-prod.yml` | 生产环境配置 |

首次本地配置：

```powershell
Copy-Item config/application-local.yml.example config/application-local.yml
# 编辑本地 YAML 中的数据库、Redis 与 haizhuo.brain.agent 参数
docker compose -f deploy/docker-compose.local.yml up -d
```

运行前需 JDK 17 和 Maven 3.9 以上。本项目的 Redis 在本机映射为 `6380` 端口；不要复用其他项目在 `6379` 上的受保护实例。`application-local.yml` 的 MySQL 密码应与本地数据库一致。Flyway 在应用启动时迁移数据库。当前仅开放健康检查等基础端点；业务 Run 与管理端点等待真实身份认证后重新接入。原 `meeting-mock` Profile 和脚本已退出。
