package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.event.AgentInternalEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Captures only completed outputs from the frozen fixed-roster delegation scope. */
public final class DelegationResultCollector {
    private static final int MAX_RESULT_CHARS = 349_000;

    private final AgentExecutionRequest request;
    private final Map<String, Buffer> active = new HashMap<>();

    public DelegationResultCollector(AgentExecutionRequest request) {
        this.request = request;
    }

    public Optional<AgentDelegationResult> accept(AgentInternalEvent event) {
        if (event == null || event.descriptor().executionRole() != AgentExecutionRole.CHILD
                || request.definition().configuration().profile() != RuntimeProfile.TEAM_READONLY) {
            return Optional.empty();
        }
        TrustedRole trusted = trustedRole(event.descriptor().nativeSource());
        if (trusted == null) return Optional.empty();
        String roleId = trusted.roleId();
        String source = event.descriptor().nativeSource();
        switch (event.kind()) {
            case STARTED -> active.put(source, new Buffer(roleId, trusted.sourceKind()));
            case TEXT_DELTA -> append(source, roleId, trusted.sourceKind(), event.text());
            case RESULT -> appendResult(source, roleId, trusted.sourceKind(), event.text());
            case ENDED -> {
                Buffer buffer = active.remove(source);
                if (buffer == null || !buffer.roleId.equals(roleId) || buffer.sourceKind != trusted.sourceKind())
                    return Optional.empty();
                String body = buffer.authoritativeResult == null ? buffer.text.toString() : buffer.authoritativeResult;
                return body.isBlank() ? Optional.empty() : Optional.of(result(buffer, body, event));
            }
            case STOPPED, EXTERNAL_TOOL_WAIT -> active.remove(source);
            case TOOL_PROGRESS -> { }
        }
        return Optional.empty();
    }

    private void append(String source, String roleId, DelegationAcceptanceProvider.SourceKind sourceKind, String text) {
        if (text == null || text.isEmpty()) return;
        Buffer buffer = active.computeIfAbsent(source, ignored -> new Buffer(roleId, sourceKind));
        if (!buffer.roleId.equals(roleId) || buffer.sourceKind != sourceKind)
            throw new SecurityException("child source changed role during execution");
        if (buffer.text.length() + text.length() > MAX_RESULT_CHARS)
            throw new IllegalStateException("fixed expert result exceeds the safe capture limit");
        buffer.text.append(text);
    }

    private void appendResult(String source, String roleId,
                              DelegationAcceptanceProvider.SourceKind sourceKind, String text) {
        Buffer buffer = active.computeIfAbsent(source, ignored -> new Buffer(roleId, sourceKind));
        if (!buffer.roleId.equals(roleId) || buffer.sourceKind != sourceKind)
            throw new SecurityException("child source changed role during execution");
        if (text != null && text.length() > MAX_RESULT_CHARS)
            throw new IllegalStateException("fixed expert result exceeds the safe capture limit");
        buffer.authoritativeResult = text == null ? "" : text;
    }

    private AgentDelegationResult result(Buffer buffer, String body, AgentInternalEvent event) {
        RuntimeEmployeeConfiguration.FixedMember member = request.definition().configuration().members().stream()
                .filter(candidate -> candidate.roleId().equals(buffer.roleId)).findFirst().orElse(null);
        if (buffer.sourceKind == DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT && member == null)
            throw new SecurityException("completed published child is outside the frozen roster");
        return new AgentDelegationResult(buffer.roleId, member == null ? null : member.employeeId(),
                member == null ? null : member.definitionVersionId(), body, event.descriptor());
    }

    private TrustedRole trustedRole(String source) {
        var configuration = request.definition().configuration();
        if (configuration.team() == null || source == null) return null;
        String prefix = request.binding().harnessSessionKey() + "/";
        if (!source.startsWith(prefix)) return null;
        String roleId = source.substring(prefix.length());
        if (roleId.isBlank() || roleId.contains("/")) return null;
        boolean allowed = configuration.team().allowedDelegations().stream().anyMatch(rule ->
                rule.fromRoleId().equals(request.binding().roleId()) && rule.toRoleId().equals(roleId));
        if (!allowed) return null;
        boolean fixed = configuration.members().stream().anyMatch(member -> member.roleId().equals(roleId));
        if (fixed) return new TrustedRole(roleId, DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT);
        if ("general-purpose".equals(roleId))
            return new TrustedRole(roleId, DelegationAcceptanceProvider.SourceKind.BUILTIN_GENERAL_PURPOSE);
        if (roleId.matches("dyn-[a-z0-9][a-z0-9-]{0,58}")
                && configuration.team().allowedDelegations().stream().anyMatch(rule ->
                rule.fromRoleId().equals(request.binding().roleId()) && rule.toRoleId().equals("dynamic-expert")))
            return new TrustedRole(roleId, DelegationAcceptanceProvider.SourceKind.RUNTIME_GENERATED);
        return null;
    }

    private record TrustedRole(String roleId, DelegationAcceptanceProvider.SourceKind sourceKind) { }

    private static final class Buffer {
        private final String roleId;
        private final DelegationAcceptanceProvider.SourceKind sourceKind;
        private final StringBuilder text = new StringBuilder();
        private String authoritativeResult;

        private Buffer(String roleId, DelegationAcceptanceProvider.SourceKind sourceKind) {
            this.roleId = roleId;
            this.sourceKind = sourceKind;
        }
    }
}
