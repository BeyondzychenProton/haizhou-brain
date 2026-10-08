package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Applies the trusted parent Run fence and activation lease to general/dynamic leaf experts. */
public final class DelegatedChildScopeMiddleware implements HarnessRuntimeMiddleware {
    private final String roleId;
    private final String trustedUserId;
    private final HarnessCallContext trustedCall;

    public DelegatedChildScopeMiddleware(String roleId, RuntimeContext parent) {
        this.roleId = roleId;
        this.trustedUserId = parent == null ? null : parent.getUserId();
        this.trustedCall = parent == null ? null : parent.get(HarnessCallContext.class);
        String parentSessionId = parent == null ? null : parent.getSessionId();
        if (roleId == null || roleId.isBlank() || trustedUserId == null || trustedUserId.isBlank()
                || parentSessionId == null || parentSessionId.isBlank() || trustedCall == null)
            throw new SecurityException("trusted parent Run context is required for child execution");
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // AgentScope gives every spawned child its own native sub-* state session. The platform
        // ownership boundary is the trusted user plus the inherited, fenced Run context.
        if (context == null || !trustedUserId.equals(context.getUserId()))
            return Flux.error(new SecurityException("delegated child context does not match its parent"));
        HarnessCallContext call = context.get(HarnessCallContext.class);
        RunScopedDelegationBudget budget = context.get(RunScopedDelegationBudget.class);
        if (!sameRun(trustedCall, call) || budget == null)
            return Flux.error(new SecurityException("delegated child Run fence or budget is missing"));
        RunScopedDelegationBudget.Lease lease;
        try {
            lease = budget.activate(roleId);
        } catch (RuntimeException denied) {
            return Flux.error(denied);
        }
        return next.apply(input).doFinally(signal -> lease.close());
    }

    private static boolean sameRun(HarnessCallContext expected, HarnessCallContext actual) {
        return actual != null && expected.runId().equals(actual.runId())
                && expected.attemptId().equals(actual.attemptId())
                && expected.fenceToken() == actual.fenceToken()
                && expected.workspaceRuntimeKey().equals(actual.workspaceRuntimeKey());
    }
}
