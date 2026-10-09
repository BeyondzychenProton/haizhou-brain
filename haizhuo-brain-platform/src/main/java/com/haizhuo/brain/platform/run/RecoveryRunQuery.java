package com.haizhuo.brain.platform.run;

import java.time.Instant;
import java.util.List;

/** 管理员核查执行结果不确定 Run 时使用的安全只读模型。 */
public final class RecoveryRunQuery {
    private RecoveryRunQuery() { }

    public record Filter(String state, Long userId, Long employeeId, Instant createdFrom, Instant createdTo) { }

    public record Summary(String runId, String sessionId, long ownerUserId, long employeeId,
                          long definitionVersionId, String runtimeProfile, String state,
                          String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt,
                          Instant recoveryRequiredAt, String recoveryRequiredAtStatus) { }

    public record Attempt(String attemptId, int attemptNo, String workerLabel, long fenceToken,
                          String state, Instant heartbeatAt, Instant leaseExpiresAt,
                          Instant startedAt, Instant finishedAt) { }

    public record Eligibility(boolean allowed, String reasonCode, Long expectedFenceToken) { }

    /** 有意省略事件正文；只暴露白名单中的公开状态码和时间。 */
    public record PublicEvent(String eventType, Instant occurredAt) { }

    /** 不返回证据引用本身；管理界面只获得“是否存在”的标签。 */
    public record Action(String requestId, long actorUserId, long expectedFenceToken,
                         String reason, String stopEvidenceReferenceLabel, Instant createdAt) { }

    public record SessionRunFact(String runId, String state, Instant createdAt, int queuePosition) { }

    /** 现有 Run 槽位和排队记录的快照；不会启动或提升任何工作。 */
    public record SessionActivity(boolean slotOccupied, List<SessionRunFact> activeOrQueuedRuns, Instant observedAt) {
        public SessionActivity { activeOrQueuedRuns = List.copyOf(activeOrQueuedRuns); }
    }

    public record Detail(Summary summary, Attempt latestAttempt, Eligibility terminationEligibility,
                         List<PublicEvent> publicEventSummary, List<Action> recoveryActions,
                         SessionActivity sessionActivity, Instant observedAt) {
        public Detail {
            publicEventSummary = List.copyOf(publicEventSummary);
            recoveryActions = List.copyOf(recoveryActions);
        }
    }
}
