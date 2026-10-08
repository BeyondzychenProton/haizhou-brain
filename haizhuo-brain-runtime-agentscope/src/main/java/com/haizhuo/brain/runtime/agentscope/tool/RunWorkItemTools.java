package com.haizhuo.brain.runtime.agentscope.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Objects;

/** Root-only platform semantics for reading and explicitly reviewing exact child results. */
public final class RunWorkItemTools {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Tool(name = "work_item_result",
            description = "Read the exact result of a delegated work item before synthesis or review. "
                    + "Always pass its returned result_id, body_sha256 and contract_version to work_item_review.")
    public String readResult(RuntimeContext runtimeContext,
                             @ToolParam(name = "work_item_ref", description = "Platform work item reference")
                             String workItemRef,
                             @ToolParam(name = "assignment_revision", description = "Exact revision number")
                             Integer assignmentRevision) {
        RunScopedDelegationBudget budget = requireBudget(runtimeContext);
        if (assignmentRevision == null || assignmentRevision < 1) return "Invalid assignment_revision";
        var result = budget.readResult(workItemRef, assignmentRevision);
        if (result.isEmpty()) return "No completed result exists for this exact work item revision.";
        try {
            return JSON.writeValueAsString(result.get());
        } catch (Exception error) {
            throw new IllegalStateException("could not encode work item result", error);
        }
    }

    @Tool(name = "work_item_review",
            description = "Explicitly review one exact child result. decision must be ACCEPT, REQUEST_REVISION, or REJECT. "
                    + "Review is bound to result_id, body_sha256 and contract_version; never review the latest result implicitly.")
    public String review(RuntimeContext runtimeContext,
                         @ToolParam(name = "work_item_ref", description = "Platform work item reference")
                         String workItemRef,
                         @ToolParam(name = "assignment_revision", description = "Exact revision number")
                         Integer assignmentRevision,
                         @ToolParam(name = "result_id", description = "Exact result id returned by work_item_result")
                         String resultId,
                         @ToolParam(name = "body_sha256", description = "Exact result hash returned by work_item_result")
                         String bodySha256,
                         @ToolParam(name = "contract_version", description = "Exact deliverable contract version")
                         String contractVersion,
                         @ToolParam(name = "decision", description = "ACCEPT, REQUEST_REVISION, or REJECT")
                         String decision,
                         @ToolParam(name = "reason", description = "Required explanation for revision or rejection",
                                 required = false)
                         String reason) {
        RunScopedDelegationBudget budget = requireBudget(runtimeContext);
        if (assignmentRevision == null || assignmentRevision < 1) return "Invalid assignment_revision";
        DelegationAcceptanceProvider.ReviewDecision parsed;
        try {
            parsed = DelegationAcceptanceProvider.ReviewDecision.valueOf(
                    Objects.requireNonNullElse(decision, "").strip().toUpperCase());
        } catch (RuntimeException invalid) {
            return "Invalid decision; use ACCEPT, REQUEST_REVISION, or REJECT.";
        }
        if (parsed != DelegationAcceptanceProvider.ReviewDecision.ACCEPT
                && (reason == null || reason.isBlank()))
            return "A reason is required for REQUEST_REVISION and REJECT.";
        boolean accepted = budget.reviewResult(workItemRef, assignmentRevision, resultId,
                bodySha256, contractVersion, parsed, reason);
        return accepted ? "Review recorded for the exact result." : "Review rejected: result, hash, contract, or Run fence did not match.";
    }

    private static RunScopedDelegationBudget requireBudget(RuntimeContext context) {
        RunScopedDelegationBudget budget = context == null ? null : context.get(RunScopedDelegationBudget.class);
        if (budget == null) throw new SecurityException("work-item review requires a trusted parent Run");
        return budget;
    }
}
