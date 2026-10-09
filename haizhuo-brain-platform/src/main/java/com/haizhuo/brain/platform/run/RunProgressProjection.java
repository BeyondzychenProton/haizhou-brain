package com.haizhuo.brain.platform.run;

import java.time.Instant;
import java.util.List;

/** 当前属主 Run 的用户可见协作事实严格白名单投影。 */
public record RunProgressProjection(int schemaVersion, String runId, String runtimeProfile, String runState,
                                    String version, long asOfSessionCursor, Instant updatedAt, boolean available,
                                    Summary summary, DelegationBudget delegationBudget,
                                    List<WorkItemSummary> workItemsPreview, boolean hasMoreWorkItems, Team team) {
    public RunProgressProjection {
        workItemsPreview = List.copyOf(workItemsPreview == null ? List.of() : workItemsPreview);
    }

    public record Summary(int workItemTotal, int activeCount, int returnedCount, int acceptedCount,
                          int revisionRequestedCount, int rejectedCount, int unknownCount) { }

    public record DelegationBudget(int invocationsReserved, Integer invocationLimit,
                                   int activeInvocations, Integer parallelLimit) { }

    public record WorkItemSummary(String workItemRef, String displayLabel, String roleId,
                                  String executorDisplayName, String sourceKind, boolean required,
                                  int assignmentRevision, String state, Instant createdAt, Instant updatedAt,
                                  Integer blockingDependencyCount, RevisionSummary latestRevision) { }

    public record RevisionSummary(int assignmentRevision, String executionState, String formatStatus,
                                  String reviewStatus, String resultAvailability, String resultId,
                                  Instant createdAt, Instant completedAt) { }

    public record Team(String executionId, String state, Instant startedAt, Instant completedAt,
                       String safeStopReasonCode, List<MemberSummary> memberSummaries,
                       List<ActionBudget> actionBudgets) {
        public Team {
            memberSummaries = List.copyOf(memberSummaries == null ? List.of() : memberSummaries);
            actionBudgets = List.copyOf(actionBudgets == null ? List.of() : actionBudgets);
        }
    }

    public record MemberSummary(String roleId, String displayName, String kind, String state,
                                Instant lastTransitionAt, String stateSource) { }

    public record ActionBudget(String actionType, int reserved, Integer limit) { }
}
