package com.haizhuo.brain.platform.employee;

import java.time.Instant;
import java.util.Objects;

/** 脱敏后的管理变更成功记录。配置正文与凭据绝不能落在这里。 */
public record AgentDefinitionManagementAudit(long actorUserId, String eventType, String targetType,
                                             String targetId, String requestId, String reason,
                                             String previousSummary, String newSummary, Instant occurredAt) {
    public AgentDefinitionManagementAudit {
        if (actorUserId <= 0) throw new IllegalArgumentException("管理审计操作者必须是有效平台用户");
        if (!java.util.Set.of("DRAFT_SAVED", "DEFINITION_PUBLISHED", "CAPABILITY_STATUS_CHANGED",
                "USER_CAPABILITY_GRANT_CHANGED").contains(eventType)) {
            throw new IllegalArgumentException("不支持的管理审计动作");
        }
        if (!java.util.Set.of("DIGITAL_EMPLOYEE", "CAPABILITY", "PLATFORM_USER").contains(targetType)) {
            throw new IllegalArgumentException("不支持的管理审计目标");
        }
        if (targetId == null || targetId.isBlank() || targetId.length() > 128) {
            throw new IllegalArgumentException("管理审计目标不能为空");
        }
        if (requestId != null && requestId.length() > 128) throw new IllegalArgumentException("发布请求标识过长");
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("变更原因长度必须为 1 到 500 个字符");
        }
        Objects.requireNonNull(previousSummary, "previousSummary");
        Objects.requireNonNull(newSummary, "newSummary");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public static AgentDefinitionManagementAudit pending(long actorUserId, String eventType, String targetType,
                                                          String targetId, String requestId, String reason) {
        return new AgentDefinitionManagementAudit(actorUserId, eventType, targetType, targetId, requestId,
                reason.trim(), "{}", "{}", Instant.EPOCH);
    }

    public AgentDefinitionManagementAudit completed(String previousSummary, String newSummary, Instant occurredAt) {
        return new AgentDefinitionManagementAudit(actorUserId, eventType, targetType, targetId, requestId,
                reason, previousSummary, newSummary, occurredAt);
    }
}
