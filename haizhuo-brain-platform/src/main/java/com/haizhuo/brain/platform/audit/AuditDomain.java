package com.haizhuo.brain.platform.audit;

/** 管理审计领域白名单；没有结构化数据源的领域不会生成虚构记录。 */
public enum AuditDomain {
    USER,
    AUTHENTICATION,
    DEFINITION,
    CAPABILITY,
    MCP,
    ASSET,
    TOOL,
    RUN_RECOVERY,
    CHANNEL,
    MODEL_CONNECTION
}
