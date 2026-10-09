package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 安全协作进度投影使用的属主范围一致性读取端口。 */
public interface RunProgressQueryStore {
    SnapshotFacts snapshot(UserId owner, RunId runId);

    List<WorkItemFact> workItems(UserId owner, RunId runId, Position before, int limit);

    Optional<WorkItemDetailFacts> workItem(UserId owner, RunId runId, String workItemRef);

    /** 仅在用户可见 Run、工作项和修订的完整属主链均有效时返回结果。 */
    Optional<WorkItemResultFact> workItemResult(UserId owner, RunId runId, String workItemRef,
                                                int assignmentRevision);

    record SnapshotFacts(String runtimeProfile, String runState, long asOfSessionCursor, Instant updatedAt,
                         List<WorkItemFact> workItems, int invocationCount, int activeInvocationCount,
                         Integer invocationLimit, Integer parallelLimit, TeamFact team) {
        public SnapshotFacts {
            workItems = List.copyOf(workItems == null ? List.of() : workItems);
        }
    }

    record Position(Instant createdAt, String workItemRef) { }

    record WorkItemFact(String workItemRef, String roleId, String executorDisplayName, String sourceKind,
                        boolean required, int assignmentRevision, String invocationState,
                        String formatStatus, String reviewStatus, String resultId, String resultKind,
                        String resultVisibility, boolean resultBodyAvailable, Instant revisionCreatedAt, Instant completedAt,
                        Instant createdAt, Instant updatedAt, int displayOrdinal) { }

    record WorkItemDetailFacts(WorkItemFact item, List<RevisionFact> revisions) {
        public WorkItemDetailFacts {
            revisions = List.copyOf(revisions == null ? List.of() : revisions);
        }
    }

    record RevisionFact(int assignmentRevision, String invocationState, String formatStatus,
                        String reviewStatus, String resultId, String resultKind, String resultVisibility,
                        boolean resultBodyAvailable, Instant createdAt, Instant completedAt) { }

    record WorkItemResultFact(String resultId, String kind, String visibility, String mediaType,
                              String body, String bodySha256, long byteSize, String revisionSha256,
                              Instant createdAt) { }

    record TeamFact(String executionId, String state, Instant startedAt, Instant completedAt,
                    String recoveryReasonCode, List<MemberFact> members, List<ActionBudgetFact> actionBudgets,
                    Instant lastActionAt) {
        public TeamFact {
            members = List.copyOf(members == null ? List.of() : members);
            actionBudgets = List.copyOf(actionBudgets == null ? List.of() : actionBudgets);
        }
    }

    record MemberFact(String roleId, String displayName, String kind, String state,
                      long lastTransitionOrdinal, Instant lastTransitionAt) { }

    record ActionBudgetFact(String actionType, int reserved) { }

    final class RunNotFoundException extends RuntimeException { }
}
