package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.event.AgentInternalEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import java.util.List;
import org.junit.jupiter.api.Test;

class DelegationResultCollectorTest {
    @Test
    void collectsCompletedTextOnlyForAnAllowedFrozenChild() {
        AgentExecutionRequest request = teamRequest();
        DelegationResultCollector collector = new DelegationResultCollector(request);

        assertTrue(collector.accept(event(AgentInternalEvent.Kind.STARTED, "session-1/researcher", null)).isEmpty());
        assertTrue(collector.accept(event(AgentInternalEvent.Kind.TEXT_DELTA, "session-1/researcher", "专家答复")).isEmpty());
        var result = collector.accept(event(AgentInternalEvent.Kind.ENDED, "session-1/researcher", null)).orElseThrow();

        assertEquals("researcher", result.roleId());
        assertEquals(22L, result.employeeId());
        assertEquals(202L, result.definitionVersionId());
        assertEquals("专家答复", result.body());
    }

    @Test
    void ignoresUnknownSessionAndUnpublishedChildSources() {
        DelegationResultCollector collector = new DelegationResultCollector(teamRequest());
        assertTrue(collector.accept(event(AgentInternalEvent.Kind.TEXT_DELTA, "other-session/researcher", "x")).isEmpty());
        assertTrue(collector.accept(event(AgentInternalEvent.Kind.TEXT_DELTA, "session-1/attacker", "x")).isEmpty());
    }

    private static AgentInternalEvent event(AgentInternalEvent.Kind kind, String source, String text) {
        AgentEventDescriptor descriptor = new AgentEventDescriptor("event-" + kind, "2026-10-08T00:00:00Z",
                kind.name(), source, "reply-1", null, null, "sub-session", null, "session-1",
                AgentExecutionRole.CHILD, "attempt-1", 1L);
        return new AgentInternalEvent(new RunId("run-1"), descriptor, kind, text);
    }

    private static AgentExecutionRequest teamRequest() {
        RuntimeDefinitionSnapshot member = new RuntimeDefinitionSnapshot(202, "研究员", "只读分析", "test", "test",
                4, "member-bundle", "defws:member-bundle", "member-workspace", "{}", List.of(),
                RuntimeEmployeeConfiguration.legacyStable(), List.of());
        RuntimeEmployeeConfiguration configuration = new RuntimeEmployeeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new RuntimeEmployeeConfiguration.RuntimePolicy(8, 2, 4, 20, false),
                new RuntimeEmployeeConfiguration.TeamConfiguration("coordinator", List.of("researcher"),
                        List.of(new RuntimeEmployeeConfiguration.RoleDelegation("coordinator", "researcher"))),
                List.of(new RuntimeEmployeeConfiguration.FixedMember("researcher", 22, 202, 4, member)));
        RuntimeDefinitionSnapshot root = new RuntimeDefinitionSnapshot(101, "协调者", "协作", "test", "test", 8,
                "root-bundle", "defws:root-bundle", "root-workspace", "{}", List.of(), configuration, List.of());
        return new AgentExecutionRequest(new TenantId(1), new UserId(7), new SessionId("session-1"),
                new RunId("run-1"), new TraceId("trace-1"), "attempt-1", 1L, root,
                new RuntimeRunConstraints("tools", java.util.Set.of(), "capabilities"),
                RuntimeSessionBinding.direct("session-1", false), new UserPromptExecutionInput("协作"));
    }
}
