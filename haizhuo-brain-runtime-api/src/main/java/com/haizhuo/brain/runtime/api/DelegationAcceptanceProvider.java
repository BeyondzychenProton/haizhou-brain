package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

/** Platform-owned acceptance boundary for native AgentScope delegation calls. */
public interface DelegationAcceptanceProvider {

    List<AcceptedDelegation> acceptBatch(RunId runId, String attemptId, long fenceToken,
                                         int maxInvocations, int maxParallel,
                                         List<DelegationCall> calls);

    Optional<WorkItemResult> readResult(RunId runId, String attemptId, long fenceToken,
                                        String workItemRef, int assignmentRevision);

    boolean reviewResult(RunId runId, String attemptId, long fenceToken,
                         String workItemRef, int assignmentRevision, String resultId,
                         String bodySha256, String contractVersion,
                         ReviewDecision decision, String reason);

    enum Operation { SPAWN, FOLLOW_UP }
    enum SourceKind { PUBLISHED_EXPERT, BUILTIN_GENERAL_PURPOSE, RUNTIME_GENERATED }
    enum ReviewDecision { ACCEPT, REQUEST_REVISION, REJECT }

    record DelegationCall(String toolUseId, Operation operation, String roleId,
                          SourceKind sourceKind, Long employeeId, Long definitionVersionId,
                          String definitionHash, String agentKey,
                          String label, String payload) {
        public DelegationCall {
            required(toolUseId, "toolUseId");
            Objects.requireNonNull(operation);
            if (operation == Operation.SPAWN) {
                required(roleId, "roleId");
                Objects.requireNonNull(sourceKind);
            } else if ((agentKey == null || agentKey.isBlank()) && (label == null || label.isBlank())) {
                throw new IllegalArgumentException("follow-up requires an agentKey or work-item label");
            }
            required(payload, "payload");
            if (definitionHash != null && !definitionHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("definitionHash must be a lowercase SHA-256 hex digest");
            if (operation == Operation.SPAWN && sourceKind == SourceKind.PUBLISHED_EXPERT
                    && (employeeId == null || employeeId <= 0 || definitionVersionId == null || definitionVersionId <= 0))
                throw new IllegalArgumentException("published experts require frozen employee and version identities");
            if (sourceKind != SourceKind.PUBLISHED_EXPERT && (employeeId != null || definitionVersionId != null))
                throw new IllegalArgumentException("non-published experts cannot claim employee or version identities");
            if (sourceKind == SourceKind.RUNTIME_GENERATED && definitionHash == null)
                throw new IllegalArgumentException("runtime-generated experts require a definition hash");
        }
    }

    record AcceptedDelegation(DelegationBudgetProvider.Reservation reservation,
                              String roleId, String workItemRef, int assignmentRevision,
                              String normalizedPayload, String nativeLabel,
                              SourceKind sourceKind, Long employeeId, Long definitionVersionId,
                              String definitionHash) {
        public AcceptedDelegation {
            Objects.requireNonNull(reservation);
            required(roleId, "roleId");
            required(workItemRef, "workItemRef");
            if (assignmentRevision < 1) throw new IllegalArgumentException("assignmentRevision must be positive");
            required(normalizedPayload, "normalizedPayload");
            required(nativeLabel, "nativeLabel");
            Objects.requireNonNull(sourceKind);
            if (sourceKind == SourceKind.PUBLISHED_EXPERT
                    && (employeeId == null || employeeId <= 0 || definitionVersionId == null || definitionVersionId <= 0))
                throw new IllegalArgumentException("published experts require frozen employee and version identities");
            if (sourceKind != SourceKind.PUBLISHED_EXPERT && (employeeId != null || definitionVersionId != null))
                throw new IllegalArgumentException("non-published experts cannot claim employee or version identities");
        }
    }

    record WorkItemResult(String workItemRef, int assignmentRevision, String resultId,
                          String body, String bodySha256, String contractVersion,
                          String mediaType, String formatStatus, String reviewStatus) {
        public WorkItemResult {
            required(workItemRef, "workItemRef");
            if (assignmentRevision < 1) throw new IllegalArgumentException("assignmentRevision must be positive");
            required(resultId, "resultId");
            Objects.requireNonNull(body);
            required(bodySha256, "bodySha256");
            required(contractVersion, "contractVersion");
            required(mediaType, "mediaType");
            required(formatStatus, "formatStatus");
            required(reviewStatus, "reviewStatus");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
