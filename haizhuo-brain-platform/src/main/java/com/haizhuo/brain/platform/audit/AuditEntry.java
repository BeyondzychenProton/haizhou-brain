package com.haizhuo.brain.platform.audit;

import java.time.Instant;
import java.util.List;

/** 对一条白名单来源记录生成的内部安全投影。 */
public record AuditEntry(String auditId, AuditDomain domain, String action, Long actorUserId,
                         String targetType, String targetId, String requestId, String runId,
                         AuditOutcome outcome, Instant createdAt, List<AuditChange> safeChanges,
                         String evidenceReferenceLabel, AuditSourceCompleteness sourceCompleteness) {
    public AuditEntry {
        safeChanges = safeChanges == null ? List.of() : List.copyOf(safeChanges);
    }
}
