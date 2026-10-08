package com.haizhuo.brain.runtime.agentscope.middleware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.runtime.agentscope.context.RunScopedDelegationBudget;
import com.haizhuo.brain.runtime.agentscope.factory.DynamicSubagentFilesystem;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Persists a complete native spawn/send batch before AgentScope starts its child agents. */
public final class DelegationAcceptanceMiddleware implements MiddlewareBase {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DynamicSubagentFilesystem dynamicFilesystem;
    private final int syncTimeoutSeconds;

    public DelegationAcceptanceMiddleware(DynamicSubagentFilesystem dynamicFilesystem, int syncTimeoutSeconds) {
        this.dynamicFilesystem = dynamicFilesystem;
        this.syncTimeoutSeconds = Math.max(1, Math.min(120, syncTimeoutSeconds));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                    Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls();
        List<PendingCall> pending = new ArrayList<>();
        boolean generating = false;
        for (ToolUseBlock tool : calls) {
            if ("agent_spawn".equals(tool.getName())) {
                pending.add(spawn(context, tool));
            } else if ("agent_send".equals(tool.getName())) {
                pending.add(followUp(tool));
            } else if ("agent_generate".equals(tool.getName())) {
                RunScopedDelegationBudget budget = context == null ? null
                        : context.get(RunScopedDelegationBudget.class);
                if (budget == null || !budget.permitsDynamicExpert())
                    return Flux.error(new SecurityException("dynamic expert generation is not allowed by the frozen Run"));
                generating = true;
            }
        }
        if (generating && !pending.isEmpty())
            return Flux.error(new IllegalStateException("generate the dynamic expert before delegating to it"));
        if (pending.isEmpty()) return next.apply(input);

        RunScopedDelegationBudget budget = context == null ? null : context.get(RunScopedDelegationBudget.class);
        if (budget == null) return Flux.error(new SecurityException("delegation requires a trusted Run scope"));
        try {
            List<DelegationAcceptanceProvider.AcceptedDelegation> accepted = budget.acceptBatch(
                    pending.stream().map(PendingCall::call).toList());
            // Accepted rows are returned in input order. Tool-use ids are not part of the persisted
            // reservation contract, so preserve positional association from the atomic batch.
            List<ToolUseBlock> rewritten = new ArrayList<>(calls.size());
            int acceptedIndex = 0;
            for (ToolUseBlock tool : calls) {
                if (!"agent_spawn".equals(tool.getName()) && !"agent_send".equals(tool.getName())) {
                    rewritten.add(tool);
                    continue;
                }
                var value = accepted.get(acceptedIndex++);
                rewritten.add(rewrite(tool, value));
            }
            return next.apply(new ActingInput(List.copyOf(rewritten)));
        } catch (RuntimeException rejected) {
            return Flux.error(rejected);
        }
    }

    private PendingCall spawn(RuntimeContext context, ToolUseBlock tool) {
        Map<String, Object> args = tool.getInput();
        String roleId = requiredText(args.get("agent_id"), "agent_id");
        String task = requiredText(args.get("task"), "task");
        requireStructuredTask(task);
        if (Boolean.TRUE.equals(args.get("expose_to_user")))
            throw new SecurityException("delegated experts cannot be exposed outside the parent Run");
        RunScopedDelegationBudget budget = context == null ? null : context.get(RunScopedDelegationBudget.class);
        if (budget == null) throw new SecurityException("delegation requires a trusted Run scope");

        DelegationAcceptanceProvider.SourceKind sourceKind;
        String definitionHash;
        Long employeeId = null;
        Long definitionVersionId = null;
        if ((definitionHash = budget.fixedDefinitionHash(roleId)) != null) {
            sourceKind = DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT;
            var fixed = budget.fixedExpert(roleId);
            employeeId = fixed.employeeId();
            definitionVersionId = fixed.definitionVersionId();
        } else if ("general-purpose".equals(roleId) && budget.permitsGeneralPurpose()) {
            sourceKind = DelegationAcceptanceProvider.SourceKind.BUILTIN_GENERAL_PURPOSE;
            definitionHash = null;
        } else if (RunScopedDelegationBudget.isDynamicRole(roleId) && budget.permitsDynamicExpert()) {
            sourceKind = DelegationAcceptanceProvider.SourceKind.RUNTIME_GENERATED;
            definitionHash = dynamicFilesystem.definitionHash(context, roleId);
        } else {
            throw new SecurityException("agent_id is not allowed by the frozen Run definition");
        }
        var call = new DelegationAcceptanceProvider.DelegationCall(tool.getId(),
                DelegationAcceptanceProvider.Operation.SPAWN, roleId, sourceKind, employeeId,
                definitionVersionId, definitionHash, null, optionalText(args.get("label")), task);
        return new PendingCall(tool.getId(), call);
    }

    private PendingCall followUp(ToolUseBlock tool) {
        Map<String, Object> args = tool.getInput();
        String message = requiredText(args.get("message"), "message");
        requireStructuredTask(message);
        String agentKey = optionalText(args.get("agent_key"));
        String label = optionalText(args.get("label"));
        var call = new DelegationAcceptanceProvider.DelegationCall(tool.getId(),
                DelegationAcceptanceProvider.Operation.FOLLOW_UP, null, null, null, null,
                null, agentKey, label, message);
        return new PendingCall(tool.getId(), call);
    }

    private ToolUseBlock rewrite(ToolUseBlock tool,
                                 DelegationAcceptanceProvider.AcceptedDelegation accepted) {
        Map<String, Object> args = new LinkedHashMap<>(tool.getInput());
        if ("agent_spawn".equals(tool.getName())) {
            args.put("task", accepted.normalizedPayload());
            // The opaque native agent_key is not a platform identifier. A unique platform work-item
            // reference is installed as AgentScope's native label so follow-ups resolve the same agent.
            args.put("label", accepted.nativeLabel());
            args.put("timeout_seconds", syncTimeoutSeconds);
            args.put("expose_to_user", false);
        } else {
            args.remove("agent_key");
            args.put("label", accepted.nativeLabel());
            args.put("message", accepted.normalizedPayload());
            args.put("timeout_seconds", syncTimeoutSeconds);
        }
        String encoded;
        try {
            encoded = JSON.writeValueAsString(args);
        } catch (Exception error) {
            throw new IllegalStateException("could not encode accepted delegation input", error);
        }
        return new ToolUseBlock(tool.getId(), tool.getName(), args, encoded, tool.getMetadata(), tool.getState());
    }

    private static void requireStructuredTask(String payload) {
        try {
            JsonNode parsed = JSON.readTree(payload);
            if (parsed == null || !parsed.isObject() || !parsed.path("objective").isTextual()
                    || parsed.path("objective").asText().isBlank())
                throw new SecurityException("delegation payload must be JSON with a non-empty objective");
        } catch (SecurityException rejected) {
            throw rejected;
        } catch (Exception malformed) {
            throw new SecurityException("delegation payload must be a structured JSON work item", malformed);
        }
    }

    private static String requiredText(Object value, String field) {
        String text = value == null ? null : String.valueOf(value);
        if (text == null || text.isBlank()) throw new SecurityException(field + " is required");
        return text.strip();
    }

    private static String optionalText(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? null : text.strip();
    }

    private record PendingCall(String toolUseId, DelegationAcceptanceProvider.DelegationCall call) { }
}
