package com.haizhuo.brain.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 结构化审计事实的只读适配器，并单独记录敏感读取/导出行为。 */
public interface AuditQueryStore {
    List<AuditEntry> findCandidates(AuditFilter filter, Instant upperBound,
                                   AuditPosition after, int perSourceLimit);

    Optional<AuditEntry> findById(String auditId);

    /** 先检查 10,001 行上限，再按稳定分块读取有界导出内容。 */
    List<AuditEntry> exportSnapshot(AuditFilter filter, Instant upperBound,
                                    int maximumRows, int chunkSize);

    /** 只保存操作者、访问类型、筛选条件/目标的单向摘要、数量和时间戳。 */
    void recordSensitiveRead(long actorUserId, String accessKind, String targetDigest,
                             int resultCount, Instant occurredAt);
}
