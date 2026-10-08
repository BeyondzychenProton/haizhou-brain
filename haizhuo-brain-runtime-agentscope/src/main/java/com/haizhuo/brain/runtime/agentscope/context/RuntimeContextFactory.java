package com.haizhuo.brain.runtime.agentscope.context;

import com.haizhuo.brain.observability.LangfuseCallContext;
import com.haizhuo.brain.runtime.api.DelegationBudgetProvider;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构建每次调用的 RuntimeContext（规格 §23）。userId 是真实的平台用户 ID，
 * 协调者使用业务 SessionId；固定专家角色使用平台保存的角色槽键；存量会话使用旧 harness 会话键。
 * 无状态，可跨模板安全共享。
 */
public class RuntimeContextFactory {
    private final DelegationBudgetProvider delegationBudgetProvider;
    private final DelegationAcceptanceProvider delegationAcceptanceProvider;

    public RuntimeContextFactory() {
        this(null, null);
    }

    public RuntimeContextFactory(DelegationBudgetProvider delegationBudgetProvider) {
        this(delegationBudgetProvider,
                delegationBudgetProvider instanceof DelegationAcceptanceProvider acceptance ? acceptance : null);
    }

    public RuntimeContextFactory(DelegationBudgetProvider delegationBudgetProvider,
                                 DelegationAcceptanceProvider delegationAcceptanceProvider) {
        this.delegationBudgetProvider = delegationBudgetProvider;
        this.delegationAcceptanceProvider = delegationAcceptanceProvider;
    }

    public RuntimeContext create(AgentExecutionRequest request) {
        String roleId = request.binding().identityMode() == RuntimeSessionBinding.IdentityMode.LEGACY_BRIDGE
                ? RuntimeSessionBinding.COORDINATOR_ROLE
                : request.binding().roleId();
        HarnessCallContext call = new HarnessCallContext(
                request.runId(),
                request.attemptId(),
                request.fenceToken(),
                request.binding().workspaceRuntimeKey(),
                request.constraints(),
                request.binding().bridgeContext(), roleId);
        RuntimeContext.Builder builder = RuntimeContext.builder()
                .userId(Long.toString(request.userId().value()))
                .sessionId(request.binding().harnessSessionKey())
                .put(HarnessCallContext.class, call)
                .put(LangfuseCallContext.class, new LangfuseCallContext(
                        request.runId(), request.platformSessionId().value(),
                        Long.toString(request.userId().value()), request.attemptId()));
        if (request.definition().configuration().profile() == RuntimeProfile.TEAM_READONLY
                && request.definition().configuration().team() != null) {
            if (delegationBudgetProvider == null)
                throw new IllegalStateException("durable delegation reservations are required for TEAM_READONLY");
            int timeout = Math.max(1, Math.min(120,
                    request.definition().configuration().policy().syncTimeoutSeconds()));
            builder.put(AgentSpawnTool.CTX_FORCE_SYNC, true);
            builder.put(AgentSpawnTool.CTX_FORCE_SYNC_TIMEOUT_SECONDS, timeout);
            Map<String, RunScopedDelegationBudget.FrozenExpert> fixedDefinitions = new LinkedHashMap<>();
            boolean allowGeneralPurpose = false;
            boolean allowDynamicExperts = false;
            for (var rule : request.definition().configuration().team().allowedDelegations()) {
                if (!roleId.equals(rule.fromRoleId())) continue;
                if ("general-purpose".equals(rule.toRoleId())) {
                    allowGeneralPurpose = true;
                    continue;
                }
                if ("dynamic-expert".equals(rule.toRoleId())) {
                    allowDynamicExperts = true;
                    continue;
                }
                request.definition().configuration().members().stream()
                        .filter(member -> member.roleId().equals(rule.toRoleId()) && member.definition() != null)
                        .findFirst()
                        .ifPresent(member -> fixedDefinitions.put(member.roleId(),
                                new RunScopedDelegationBudget.FrozenExpert(member.employeeId(),
                                        member.definitionVersionId(), member.definition().definitionBundleHash())));
            }
            builder.put(RunScopedDelegationBudget.class, new RunScopedDelegationBudget(
                    delegationBudgetProvider, delegationAcceptanceProvider,
                    request.runId(), request.attemptId(), request.fenceToken(),
                    request.definition().configuration().policy().maxExpertInvocationsPerRun(),
                    request.definition().configuration().policy().maxParallelDelegations(),
                    fixedDefinitions, allowGeneralPurpose, allowDynamicExperts));
        }
        return builder.build();
    }
}
