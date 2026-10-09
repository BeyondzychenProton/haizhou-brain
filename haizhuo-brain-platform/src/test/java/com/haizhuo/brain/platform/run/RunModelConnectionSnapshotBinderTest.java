package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.ModelConnectionBindingStore;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import com.haizhuo.brain.runtime.api.model.RuntimeRunConstraints;
import com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding;
import com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class RunModelConnectionSnapshotBinderTest {
    private static final RunId RUN_ID = new RunId("run-model-binding");
    private static final UserId OWNER = new UserId(71);
    private static final SessionId SESSION_ID = new SessionId("session-model-binding");
    private static final RuntimeModelConnectionRef ROOT_REF = new RuntimeModelConnectionRef(
            "root-model", 2, "a".repeat(64));
    private static final RuntimeModelConnectionRef MEMBER_REF = new RuntimeModelConnectionRef(
            "member-model", 4, "b".repeat(64));

    @Test
    void bindsExactExecutorAndFixedMemberReferencesAndFreezesEachRunBinding() {
        RuntimeDefinitionSnapshot memberDefinition = definition(202, "dashscope", "member-model");
        RuntimeEmployeeConfiguration.FixedMember member = new RuntimeEmployeeConfiguration.FixedMember(
                "researcher", 302, 202, 2, memberDefinition);
        RuntimeEmployeeConfiguration configuration = teamConfiguration(List.of(member));
        RuntimeDefinitionSnapshot rootDefinition = withConfiguration(
                definition(101, "openai", "root-model"), configuration);
        FakeSessions sessions = new FakeSessions(
                new RunExecutionTarget(RunExecutionMode.DIRECT, "coordinator", 301, 101, null));
        FakeBindings bindings = new FakeBindings(Map.of(101L, ROOT_REF, 202L, MEMBER_REF));
        RunModelConnectionSnapshotBinder binder = new RunModelConnectionSnapshotBinder(sessions, bindings);

        AgentExecutionRequest bound = binder.bind(request(rootDefinition, "coordinator"));

        assertEquals(ROOT_REF, bound.definition().modelConnectionRef());
        assertEquals(MEMBER_REF, bound.definition().configuration().members().get(0)
                .definition().modelConnectionRef());
        assertEquals(List.of("coordinator:101", "researcher:202"), bindings.frozenBindings);
    }

    @Test
    void rejectsRequestWhoseRoleOrVersionDoesNotMatchPersistedRunTarget() {
        RuntimeDefinitionSnapshot definition = definition(101, "openai", "root-model");
        FakeSessions sessions = new FakeSessions(
                new RunExecutionTarget(RunExecutionMode.DIRECT, "researcher", 302, 202, "slot-1"));
        FakeBindings bindings = new FakeBindings(Map.of(101L, ROOT_REF, 202L, MEMBER_REF));
        RunModelConnectionSnapshotBinder binder = new RunModelConnectionSnapshotBinder(sessions, bindings);

        assertThrows(ModelConnectionBindingUnavailableException.class,
                () -> binder.bind(request(definition, "coordinator")));
        assertEquals(List.of(), bindings.frozenBindings);
    }

    @Test
    void missingVersionReferenceFailsClosedBeforeRuntimeExecution() {
        RuntimeDefinitionSnapshot definition = definition(101, "openai", "root-model");
        FakeSessions sessions = new FakeSessions(RunExecutionTarget.coordinator(301, 101));
        FakeBindings bindings = new FakeBindings(Map.of());
        RunModelConnectionSnapshotBinder binder = new RunModelConnectionSnapshotBinder(sessions, bindings);

        assertThrows(ModelConnectionBindingUnavailableException.class,
                () -> binder.bind(request(definition, "coordinator")));
        assertEquals(List.of(), bindings.frozenBindings);
    }

    @Test
    void missingBindingReturnsSafeFailureWithoutCallingAgentScopeRuntime() {
        RuntimeDefinitionSnapshot definition = definition(101, "openai", "root-model");
        RunModelConnectionSnapshotBinder binder = new RunModelConnectionSnapshotBinder(
                new FakeSessions(RunExecutionTarget.coordinator(301, 101)), new FakeBindings(Map.of()));
        AtomicInteger delegateCalls = new AtomicInteger();
        AgentRuntime delegate = request -> {
            delegateCalls.incrementAndGet();
            return Flux.empty();
        };
        ModelConnectionBindingAgentRuntime runtime = new ModelConnectionBindingAgentRuntime(delegate, binder);

        List<BrainAgentEvent> events = runtime.execute(request(definition, "coordinator")).collectList().block();

        assertEquals(0, delegateCalls.get());
        assertEquals(1, events.size());
        assertInstanceOf(AgentRunFailedEvent.class, events.get(0));
        assertEquals("MODEL_CONNECTION_UNAVAILABLE: 模型连接的冻结版本缺失或不匹配，执行未开始。",
                ((AgentRunFailedEvent) events.get(0)).message());
    }

    private static RuntimeDefinitionSnapshot definition(long versionId, String provider, String model) {
        String manifest = "{\"schemaVersion\":1,\"agents\":\"AGENTS.md\",\"skills\":[],\"subagents\":[],\"knowledge\":[]}";
        return new RuntimeDefinitionSnapshot(versionId, "employee-" + versionId, "instructions", provider,
                model, 3, "bundle-" + versionId, "workspace-" + versionId, "c".repeat(64), manifest,
                List.of(), RuntimeEmployeeConfiguration.legacyStable(), List.of());
    }

    private static RuntimeDefinitionSnapshot withConfiguration(RuntimeDefinitionSnapshot source,
                                                               RuntimeEmployeeConfiguration configuration) {
        return new RuntimeDefinitionSnapshot(source.definitionVersionId(), source.employeeName(),
                source.instructions(), source.modelProvider(), source.modelName(), source.maxIterations(),
                source.definitionBundleHash(), source.workspaceProjectionKey(), source.workspaceContentHash(),
                source.workspaceManifestJson(), source.toolCatalog(), configuration, source.workspaceFiles());
    }

    private static RuntimeEmployeeConfiguration teamConfiguration(
            List<RuntimeEmployeeConfiguration.FixedMember> members) {
        RuntimeEmployeeConfiguration.RuntimePolicy policy = new RuntimeEmployeeConfiguration.RuntimePolicy(
                3, 1, 2, 30, false);
        RuntimeEmployeeConfiguration.TeamConfiguration team = new RuntimeEmployeeConfiguration.TeamConfiguration(
                "researcher", List.of("researcher"), List.of());
        return new RuntimeEmployeeConfiguration(1,
                com.haizhuo.brain.runtime.api.model.RuntimeProfile.TEAM_READONLY, policy, team, members);
    }

    private static AgentExecutionRequest request(RuntimeDefinitionSnapshot definition, String roleId) {
        RuntimeSessionBinding binding = "coordinator".equals(roleId)
                ? RuntimeSessionBinding.direct(SESSION_ID.value(), false)
                : RuntimeSessionBinding.roleSlot(roleId, "harness-role-slot", "workspace-role-slot", false);
        return new AgentExecutionRequest(new TenantId(1), OWNER, SESSION_ID, RUN_ID, new TraceId("trace-binding"),
                "attempt-binding", 3L, definition, new RuntimeRunConstraints("tools", Set.of(), "capabilities"),
                binding, new UserPromptExecutionInput("prompt"));
    }

    private static final class FakeSessions implements SessionRunStore {
        private final RunExecutionTarget target;
        private final AgentRun run = new AgentRun(RUN_ID, SESSION_ID, OWNER, 301, 101, "request-1", "digest-1",
                RunState.RUNNING, Instant.parse("2026-10-09T00:00:00Z"), null, null);

        private FakeSessions(RunExecutionTarget target) {
            this.target = target;
        }

        @Override public AgentSession createSession(AgentSession session) { return session; }
        @Override public Optional<AgentSession> findSession(SessionId sessionId, UserId owner) { return Optional.empty(); }
        @Override public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec) { return run; }
        @Override public Optional<AgentRun> findRun(RunId runId, UserId owner) {
            return run.id().equals(runId) && run.userId().equals(owner) ? Optional.of(run) : Optional.empty();
        }
        @Override public List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) {
            return List.of();
        }
        @Override public Optional<RunExecutionTarget> findExecutionTarget(RunId runId, UserId owner) {
            return Optional.of(target);
        }
    }

    private static final class FakeBindings implements ModelConnectionBindingStore {
        private final Map<Long, RuntimeModelConnectionRef> byVersion;
        private final List<String> frozenBindings = new ArrayList<>();

        private FakeBindings(Map<Long, RuntimeModelConnectionRef> byVersion) {
            this.byVersion = new HashMap<>(byVersion);
        }

        @Override public void freezeDefinitionVersionBinding(long employeeId, int draftRevision, long definitionVersionId) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<RuntimeModelConnectionRef> findDefinitionVersionBinding(long definitionVersionId,
                                                                                           String expectedProvider) {
            return Optional.ofNullable(byVersion.get(definitionVersionId));
        }
        @Override public RuntimeModelConnectionRef freezeRunBinding(RunId runId, String executorRoleId,
                                                                    long executorDefinitionVersionId,
                                                                    String expectedProvider,
                                                                    RuntimeModelConnectionRef reference) {
            if (!reference.equals(byVersion.get(executorDefinitionVersionId)))
                throw new ModelConnectionBindingUnavailableException();
            frozenBindings.add(executorRoleId + ":" + executorDefinitionVersionId);
            return reference;
        }
    }
}
