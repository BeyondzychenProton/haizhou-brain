package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Fails closed unless the child is executing under its trusted parent Run and budget. */
public final class FixedExpertScopeMiddleware implements HarnessRuntimeMiddleware {
    private final String roleId;
    private final String employeeId;
    private final long definitionVersionId;
    private final String trustedUserId;
    private final String trustedParentSessionId;
    private final HarnessCallContext trustedParentCall;

    public FixedExpertScopeMiddleware(String roleId, long employeeId, long definitionVersionId,
                                      RuntimeContext parent) {
        this.roleId = roleId;
        this.employeeId = Long.toString(employeeId);
        this.definitionVersionId = definitionVersionId;
        this.trustedUserId = parent.getUserId();
        this.trustedParentSessionId = parent.getSessionId();
        this.trustedParentCall = parent.get(HarnessCallContext.class);
        if (trustedUserId == null || trustedUserId.isBlank() || trustedParentSessionId == null
                || trustedParentSessionId.isBlank() || trustedParentCall == null)
            throw new SecurityException("trusted parent Run context is required for fixed expert execution");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // AgentScope gives each spawned expert a native "sub-*" AgentState session. The
        // framework-provided SubagentFactory still propagates the trusted business call context;
        // verify that context and user, rather than guessing a relationship from the generated ID.
        if (context == null || !trustedUserId.equals(context.getUserId()))
            return Flux.error(new SecurityException("fixed expert context does not match its parent user"));
        HarnessCallContext call = context.get(HarnessCallContext.class);
        RunScopedDelegationBudget budget = context.get(RunScopedDelegationBudget.class);
        if (!sameRun(trustedParentCall, call) || budget == null)
            return Flux.error(new SecurityException("fixed expert Run fence or delegation budget is missing"));
        RunScopedDelegationBudget.Lease lease;
        try {
            lease = budget.activate(roleId, input.msgs().get(0).getTextContent());
        } catch (RuntimeException denied) {
            return Flux.error(denied);
        }
        return next.apply(input).doOnNext(event -> {
            if (event instanceof AgentResultEvent result) {
                budget.complete(lease, result.getResult().getTextContent(),
                        new AgentEventDescriptor(event.getId(), event.getCreatedAt(), event.getType().name(),
                                event.getSource(), result.getResult().getId(), null, null, context.getSessionId(),
                                null, trustedParentSessionId, AgentExecutionRole.CHILD, call.attemptId(), call.fenceToken()));
            }
        }).doFinally(signal -> budget.release(lease));
    }

    private boolean sameRun(HarnessCallContext expected, HarnessCallContext actual) {
        return actual != null && expected.runId().equals(actual.runId())
                && expected.attemptId().equals(actual.attemptId())
                && expected.fenceToken() == actual.fenceToken()
                && expected.workspaceRuntimeKey().equals(actual.workspaceRuntimeKey())
                && expected.roleId().equals(actual.roleId());
    }
}
