# 海卓智慧大脑

基于 JDK 17、Spring Boot 3.5.16 的模块化单体工程骨架。

本初始化版本按实施说明创建模块边界、运行时接口、配置和基础设施边界；按项目要求暂不包含 Flyway、数据库迁移脚本、建表 SQL、数据库表对象及 Mapper XML。

## 本地运行

```bash
mvn clean verify
mvn -pl haizhuo-brain-bootstrap -am spring-boot:run -Dspring-boot.run.profiles=local
```

数据库、Redis、模型密钥等敏感配置通过 `HZ_BRAIN_` 环境变量注入。

## 项目文档

- [文档索引与目录约定](docs/README.md)
- [Agent 工作助手平台初版概要设计](docs/design/agent-platform/overview.md)
