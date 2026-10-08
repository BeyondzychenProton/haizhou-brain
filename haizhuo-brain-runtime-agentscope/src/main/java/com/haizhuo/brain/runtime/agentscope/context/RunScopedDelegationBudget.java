package com.haizhuo.brain.runtime.agentscope.context;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.DelegationBudgetProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run-scoped bridge from native Harness calls to durable platform acceptance and budgets. */
public final class RunScopedDelegationBudget {
    private final DelegationBudgetProvider budgetProvider;
    private final DelegationAcceptanceProvider acceptanceProvider;
    private final RunId runId;
    private final String attemptId;
    private final long fenceToken;
    private final int maxInvocations;
    private final int maxParallel;
    private final Map<String, FrozenExpert> allowedFixedDefinitions;
    private final boolean generalPurposeAllowed;
    private final boolean dynamicExpertAllowed;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<DelegationAcceptanceProvider.AcceptedDelegation>> pending
            = new ConcurrentHashMap<>();

    public RunScopedDelegationBudget(DelegationBudgetProvider provider, RunId runId,
                                     String attemptId, long fenceToken,
                                     int maxInvocations, int maxParallel) {
        this(provider, provider instanceof DelegationAcceptanceProvider acceptance ? acceptance : null,
                runId, attemptId, fenceToken, maxInvocations, maxParallel, Map.of(), false, false);
    }

    public RunScopedDelegationBudget(DelegationBudgetProvider budgetProvider,
                                     DelegationAcceptanceProvider acceptanceProvider,
                                     RunId runId, String attemptId, long fenceToken,
                                     int maxInvocations, int maxParallel,
                                     Map<String, FrozenExpert> allowedFixedDefinitions,
                                     boolean generalPurposeAllowed, boolean dynamicExpertAllowed) {
        if (budgetProvider == null || runId == null
                || attemptId == null || attemptId.isBlank())
            throw new IllegalArgumentException("durable delegation acceptance scope is required");
        if (maxInvocations < 0 || maxParallel < 0)
            throw new IllegalArgumentException("delegation limits must not be negative");
        this.budgetProvider = budgetProvider;
        this.acceptanceProvider = acceptanceProvider;
        this.runId = runId;
        this.attemptId = attemptId;
        this.fenceToken = fenceToken;
        this.maxInvocations = maxInvocations;
        this.maxParallel = maxParallel;
        this.allowedFixedDefinitions = Map.copyOf(allowedFixedDefinitions == null ? Map.of() : allowedFixedDefinitions);
        this.generalPurposeAllowed = generalPurposeAllowed;
        this.dynamicExpertAllowed = dynamicExpertAllowed;
    }

    /** Accept an entire native tool-call batch before AgentScope starts any member of it. */
    public List<DelegationAcceptanceProvider.AcceptedDelegation> acceptBatch(
            List<DelegationAcceptanceProvider.DelegationCall> calls) {
        if (calls == null || calls.isEmpty()) return List.of();
        if (acceptanceProvider == null)
            throw new IllegalStateException("durable work-item acceptance is unavailable");
        List<DelegationAcceptanceProvider.DelegationCall> authorized = new ArrayList<>(calls.size());
        for (var call : calls) {
            if (call.operation() == DelegationAcceptanceProvider.Operation.FOLLOW_UP) {
                authorized.add(call);
                continue;
            }
            FrozenExpert expected = allowedFixedDefinitions.get(call.roleId());
            if (expected != null) {
                if (call.sourceKind() != DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT
                        || !expected.definitionHash().equals(call.definitionHash())
                        || !expected.employeeId().equals(call.employeeId())
                        || !expected.definitionVersionId().equals(call.definitionVersionId()))
                    throw new SecurityException("fixed expert definition does not match the frozen Run");
            } else if ("general-purpose".equals(call.roleId()) && generalPurposeAllowed) {
                if (call.sourceKind() != DelegationAcceptanceProvider.SourceKind.BUILTIN_GENERAL_PURPOSE
                        || call.definitionHash() != null)
                    throw new SecurityException("general-purpose source metadata is invalid");
            } else if (isDynamicRole(call.roleId()) && dynamicExpertAllowed) {
                if (call.sourceKind() != DelegationAcceptanceProvider.SourceKind.RUNTIME_GENERATED
                        || call.definitionHash() == null)
                    throw new SecurityException("runtime-generated expert definition is not frozen");
            } else {
                throw new SecurityException("delegation role is not allowed by the frozen Run");
            }
            authorized.add(call);
        }
        List<DelegationAcceptanceProvider.AcceptedDelegation> accepted = acceptanceProvider.acceptBatch(
                runId, attemptId, fenceToken, maxInvocations, maxParallel, List.copyOf(authorized));
        if (accepted == null || accepted.size() != calls.size())
            throw new IllegalStateException("delegation provider did not accept the complete tool-call batch");
        for (var item : accepted) {
            pending.computeIfAbsent(item.roleId(), ignored -> new ConcurrentLinkedQueue<>()).add(item);
        }
        return List.copyOf(accepted);
    }

    public String fixedDefinitionHash(String roleId) {
        FrozenExpert expert = allowedFixedDefinitions.get(roleId);
        return expert == null ? null : expert.definitionHash();
    }

    public FrozenExpert fixedExpert(String roleId) { return allowedFixedDefinitions.get(roleId); }

    public boolean permitsGeneralPurpose() { return generalPurposeAllowed; }

    public boolean permitsDynamicExpert() { return dynamicExpertAllowed; }

    public Lease activate(String roleId) {
        var queue = pending.get(roleId);
        var accepted = queue == null ? null : queue.poll();
        if (accepted == null) {
            // Preserve the pre-SPEC-10 contract for direct native probes. Production tool calls
            // always arrive through acceptBatch and therefore use an accepted work item.
            return acquire(roleId);
        }
        if (!accepted.reservation().activate()) {
            accepted.reservation().close();
            throw new IllegalStateException("delegation acceptance became stale before native execution");
        }
        return new Lease(accepted.reservation());
    }

    public Optional<DelegationAcceptanceProvider.WorkItemResult> readResult(
            String workItemRef, int assignmentRevision) {
        if (acceptanceProvider == null)
            throw new IllegalStateException("durable work-item result lookup is unavailable");
        return acceptanceProvider.readResult(runId, attemptId, fenceToken, workItemRef, assignmentRevision);
    }

    public boolean reviewResult(String workItemRef, int assignmentRevision, String resultId,
                                String bodySha256, String contractVersion,
                                DelegationAcceptanceProvider.ReviewDecision decision, String reason) {
        if (acceptanceProvider == null) return false;
        return acceptanceProvider.reviewResult(runId, attemptId, fenceToken, workItemRef,
                assignmentRevision, resultId, bodySha256, contractVersion, decision, reason);
    }

    /** Close accepted calls that AgentScope did not start before this root execution ended. */
    public void closeUnstarted() {
        pending.values().forEach(queue -> {
            DelegationAcceptanceProvider.AcceptedDelegation call;
            while ((call = queue.poll()) != null) call.reservation().close();
        });
        pending.clear();
    }

    /** The fixed profile has no standalone dynamic namespace; generated roles are reserved dyn-* ids. */
    public static boolean isDynamicRole(String roleId) {
        return roleId != null && roleId.matches("dyn-[a-z0-9][a-z0-9-]{0,58}");
    }

    public record FrozenExpert(Long employeeId, Long definitionVersionId, String definitionHash) {
        public FrozenExpert {
            Objects.requireNonNull(employeeId);
            Objects.requireNonNull(definitionVersionId);
            Objects.requireNonNull(definitionHash);
            if (employeeId <= 0 || definitionVersionId <= 0 || !definitionHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid frozen expert identity");
        }
    }

    private Lease acquire(String roleId) {
        if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("roleId is required");
        DelegationBudgetProvider.Reservation reservation = budgetProvider.reserve(
                runId, attemptId, fenceToken, roleId, maxInvocations, maxParallel);
        if (reservation == null || reservation.invocationId() == null || reservation.invocationId().isBlank())
            throw new IllegalStateException("delegation provider returned an invalid reservation");
        return new Lease(reservation);
    }

    public static final class Lease implements AutoCloseable {
        private final DelegationBudgetProvider.Reservation reservation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(DelegationBudgetProvider.Reservation reservation) {
            this.reservation = Objects.requireNonNull(reservation);
        }

        public String invocationId() { return reservation.invocationId(); }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) reservation.close();
        }
    }
}
