package com.haizhuo.brain.platform.audit;

import java.time.Instant;

/** 经规范化并参数化的筛选条件，供所有静态来源适配器共用。 */
public record AuditFilter(AuditDomain domain, String action, Long actorUserId,
                          String targetType, String targetId, String requestId, String runId,
                          Instant createdFrom, Instant createdTo) {
}
