# 外置运行配置

本目录集中放置各运行环境的 Spring Boot 配置。Spring Boot 从项目根目录启动时会自动读取 `config/`，其中的配置会覆盖程序包内的默认值。

| 文件 | 用途 |
| --- | --- |
| `application.yml` | 公共端口、应用名、时区与默认模型 |
| `application-meeting-mock.yml` | 本地 Mock 会议室闭环、OpenAI 兼容模型名称、服务地址和 API Key |
| `application-local.yml` | 本地 MySQL、Redis 与日志配置 |
| `application-staging.yml` | 测试/预发环境连接池与服务地址、凭据 |
| `application-prod.yml` | 生产环境连接池与服务地址、凭据 |

本项目直接从 YAML 文件读取配置，不要求环境变量注入。为避免提交密钥和密码，上表中的实际配置文件由 Git 忽略；首次配置时复制 `.example` 模板，修改复制出的文件。

Mock 演示使用 PowerShell 启动。启动脚本会同时激活 `local` 和 `meeting-mock` 配置，以使用本地 MySQL 持久化 Run 和预定。`meeting-room` 节点可调整默认租户、员工、模拟用户和管理审计操作者 ID；首轮数据库迁移为 ID 1/1001 创建测试数据：

```powershell
Copy-Item config/application-meeting-mock.yml.example config/application-meeting-mock.yml
# 编辑 application-meeting-mock.yml，填写 api-key、base-url 和模型 ID
.\scripts\run-meeting-mock.ps1
```

运行前需安装 JDK 17 与 Maven 3.9 或更高版本，并确保它们可从 PowerShell 使用；若机器默认 Java 不是 17，请将 `JAVA_HOME` 指向 JDK 17。`JAVA_HOME` 仅选择编译和运行所用的 Java，不承担应用配置注入。

本地数据库启动时，再复制 `application-local.yml.example` 为 `application-local.yml`，并填写有权创建本地开发库的 MySQL 账号。首次连接时，MySQL 驱动会在目标库不存在时创建该库，随后由 Flyway 建表。直接编辑当前环境的 `application-<profile>.yml` 即可调整端口、地址、连接池、模型名称与凭据。

通过其他方式启动时，从项目根目录运行，以便 Spring Boot 自动找到 `config/`。切换 Profile 时在启动命令中设置 `spring-boot.run.profiles`，无需改系统环境变量。
