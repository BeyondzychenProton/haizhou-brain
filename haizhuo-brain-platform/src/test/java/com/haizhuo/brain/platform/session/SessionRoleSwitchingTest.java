package com.haizhuo.brain.platform.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionVersion;
import com.haizhuo.brain.platform.employee.DigitalEmployee;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.HarnessRunSpecFactory;
import com.haizhuo.brain.platform.run.RunExecutionMode;
import com.haizhuo.brain.platform.run.RunExecutionTarget;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SessionRoleSwitchingTest {
    private static final UserId OWNER = new UserId(42);
    private static final TenantId TENANT = new TenantId(1);
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void directRoleSwitchCreatesStablePrivateSlotsAndFreezesTheSelectedMemberBundle() {
        MemoryStore store = new MemoryStore();
        Map<Long, HarnessDefinitionBundle> byVersion = new HashMap<>();
        byVersion.put(10L, bundle(10, 1, "协调员工", teamConfiguration(), NOW));
        byVersion.put(20L, bundle(20, 2, "研究专家", EmployeeRuntimeConfiguration.singleSkilled(4), NOW));
        byVersion.put(30L, bundle(30, 3, "审核专家", EmployeeRuntimeConfiguration.singleSkilled(3), NOW));
        HarnessDefinitionBundleRepository bundles = repository(byVersion);
        EmployeeCatalog employees = (tenant, employeeId) -> Optional.of(new PublishedEmployee(
                new DigitalEmployee(employeeId, tenant, "owner", "协调员工", true),
                new AgentDefinitionVersion(10, employeeId, 1, "指令", "openai", "model", NOW), List.of()));
        SessionApplicationService service = new SessionApplicationService(store, employees, bundles,
                new HarnessRunSpecFactory(null, null), null, Clock.fixed(NOW, ZoneOffset.UTC), verifiedProfiles());
        AgentSession session = service.create(OWNER, 1);

        assertEquals(List.of("coordinator", "researcher", "reviewer"),
                service.roles(session.id(), OWNER).stream().map(SessionRoleOption::roleId).toList());
        AgentRun firstA = service.createRun(session.id(), OWNER, "a-1", "研究任务", "researcher", RunExecutionMode.DIRECT);
        RunExecutionTarget targetA1 = store.targets.get(firstA.id());
        AgentRun runB = service.createRun(session.id(), OWNER, "b-1", "审核任务", "reviewer", RunExecutionMode.DIRECT);
        RunExecutionTarget targetB = store.targets.get(runB.id());
        AgentRun secondA = service.createRun(session.id(), OWNER, "a-2", "继续研究", "researcher", RunExecutionMode.DIRECT);
        RunExecutionTarget targetA2 = store.targets.get(secondA.id());

        assertEquals(10L, firstA.definitionVersionId(), "Run 仍归属 Session 固定的团队发布包");
        assertEquals(20L, store.specs.get(firstA.id()).definitionVersionId(), "实际执行规格冻结到研究成员版本");
        assertEquals("researcher", targetA1.roleId());
        assertEquals(2L, targetA1.employeeId());
        assertEquals(20L, targetA1.definitionVersionId());
        assertEquals(targetA1.roleSlotId(), targetA2.roleSlotId(), "切回 A 必须复用 A 的持久角色槽");
        assertNotEquals(targetA1.roleSlotId(), targetB.roleSlotId(), "A 与 B 必须拥有不同 AgentScope 会话槽");
        assertEquals(targetA1.roleSlotId(), store.findRoleSlot(session.id(), OWNER, "researcher").orElseThrow().id());
        assertEquals(targetA1.roleSlotId(), service.executionTarget(firstA, OWNER).roleSlotId());
    }

    @Test
    void rejectsNonSelectableRolesAndUnimplementedExecutionModes() {
        MemoryStore store = new MemoryStore();
        Map<Long, HarnessDefinitionBundle> byVersion = new HashMap<>();
        byVersion.put(10L, bundle(10, 1, "协调员工", teamConfiguration(), NOW));
        byVersion.put(20L, bundle(20, 2, "研究专家", EmployeeRuntimeConfiguration.singleSkilled(4), NOW));
        byVersion.put(30L, bundle(30, 3, "审核专家", EmployeeRuntimeConfiguration.singleSkilled(3), NOW));
        EmployeeCatalog employees = (tenant, employeeId) -> Optional.of(new PublishedEmployee(
                new DigitalEmployee(employeeId, tenant, "owner", "协调员工", true),
                new AgentDefinitionVersion(10, employeeId, 1, "指令", "openai", "model", NOW), List.of()));
        SessionApplicationService service = new SessionApplicationService(store, employees, repository(byVersion),
                new HarnessRunSpecFactory(null, null), null, Clock.fixed(NOW, ZoneOffset.UTC), verifiedProfiles());
        AgentSession session = service.create(OWNER, 1);

        assertThrows(IllegalArgumentException.class,
                () -> service.createRun(session.id(), OWNER, "blocked", "越权目标", "hidden-role", RunExecutionMode.DIRECT));
        assertThrows(IllegalStateException.class,
                () -> service.createRun(session.id(), OWNER, "future", "尚未开放", "researcher", RunExecutionMode.COLLABORATIVE));
        assertTrue(store.targets.isEmpty());
    }

    @Test
    void configuredButUnverifiedProfileCannotCreateSessionOrStartRun() {
        MemoryStore store = new MemoryStore();
        Map<Long, HarnessDefinitionBundle> byVersion = Map.of(
                10L, bundle(10, 1, "协调员工", teamConfiguration(), NOW));
        EmployeeCatalog employees = (tenant, employeeId) -> Optional.of(new PublishedEmployee(
                new DigitalEmployee(employeeId, tenant, "owner", "协调员工", true),
                new AgentDefinitionVersion(10, employeeId, 1, "指令", "openai", "model", NOW,
                        "", teamConfiguration()), List.of()));
        RuntimeProfileAdmissionPolicy configuredWithoutEvidence = RuntimeProfileAdmissionPolicy.configuredOnly(
                java.util.Set.of(RuntimeProfile.LEGACY_STABLE, RuntimeProfile.TEAM_READONLY));
        SessionApplicationService service = new SessionApplicationService(store, employees, repository(byVersion),
                new HarnessRunSpecFactory(null, null), null, Clock.fixed(NOW, ZoneOffset.UTC),
                configuredWithoutEvidence);

        assertThrows(IllegalStateException.class, () -> service.create(OWNER, 1));
        AgentSession frozen = new AgentSession(new SessionId("frozen-team-session"), OWNER, 1,
                AgentSession.Status.ACTIVE, NOW, NOW, 0, 10L, false);
        store.createSession(frozen);
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> service.createRun(frozen.id(), OWNER, "req-1", "执行任务"));

        assertTrue(rejected.getMessage().contains("PROFILE_VERIFICATION_MISSING"));
        assertTrue(store.targets.isEmpty());
    }

    private static EmployeeRuntimeConfiguration teamConfiguration() {
        return new EmployeeRuntimeConfiguration(2, RuntimeProfile.TEAM_READONLY,
                new EmployeeRuntimeConfiguration.RuntimePolicy(8, 2, 4, 120, false),
                new EmployeeRuntimeConfiguration.TeamConfiguration("researcher", List.of("researcher", "reviewer"),
                        List.of(new EmployeeRuntimeConfiguration.RoleDelegation("researcher", "reviewer"))),
                List.of(new EmployeeRuntimeConfiguration.FixedMember("researcher", 2, 20, 4),
                        new EmployeeRuntimeConfiguration.FixedMember("reviewer", 3, 30, 3)));
    }

    private static RuntimeProfileAdmissionPolicy verifiedProfiles() {
        return profile -> new RuntimeProfileAdmissionPolicy.Admission(true, true, true, null, "TEST_VERIFICATION");
    }

    private static HarnessDefinitionBundle bundle(long version, long employee, String name,
                                                    EmployeeRuntimeConfiguration configuration, Instant now) {
        return new HarnessDefinitionBundle(version, version, name, "只读指令", "openai", "model", 8,
                "{}", "workspace-" + version, List.of(), "tools", "{}", "{}", "bundle-" + version,
                configuration, List.of(), now);
    }

    private static HarnessDefinitionBundleRepository repository(Map<Long, HarnessDefinitionBundle> bundles) {
        return new HarnessDefinitionBundleRepository() {
            public HarnessDefinitionBundle save(HarnessDefinitionBundle bundle) { return bundle; }
            public Optional<HarnessDefinitionBundle> findByDefinitionVersionId(long version) {
                return Optional.ofNullable(bundles.get(version));
            }
            public Optional<HarnessDefinitionBundle> findByBundleHash(String hash) { return Optional.empty(); }
        };
    }

    private static final class MemoryStore implements SessionRunStore {
        private final Map<SessionId, AgentSession> sessions = new HashMap<>();
        private final Map<String, SessionRoleSlot> slots = new HashMap<>();
        private final Map<RunId, RunExecutionTarget> targets = new HashMap<>();
        private final Map<RunId, HarnessRunSpec> specs = new HashMap<>();

        @Override public AgentSession createSession(AgentSession session) {
            sessions.put(session.id(), session);
            return session;
        }
        @Override public Optional<AgentSession> findSession(SessionId sessionId, UserId owner) {
            return Optional.ofNullable(sessions.get(sessionId)).filter(session -> session.userId().equals(owner));
        }
        @Override public SessionRoleSlot createRoleSlot(SessionRoleSlot proposed) {
            String key = proposed.sessionId().value() + ":" + proposed.roleId();
            return slots.computeIfAbsent(key, ignored -> proposed);
        }
        @Override public Optional<SessionRoleSlot> findRoleSlot(SessionId sessionId, UserId owner, String roleId) {
            return Optional.ofNullable(slots.get(sessionId.value() + ":" + roleId))
                    .filter(slot -> slot.userId().equals(owner));
        }
        @Override public Optional<RunExecutionTarget> findExecutionTarget(RunId runId, UserId owner) {
            return Optional.ofNullable(targets.get(runId));
        }
        @Override public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec) { return run; }
        @Override public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec, RunExecutionTarget target) {
            targets.put(run.id(), target);
            specs.put(run.id(), runSpec);
            return run;
        }
        @Override public Optional<AgentRun> findRun(RunId runId, UserId owner) { return Optional.empty(); }
        @Override public List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) { return List.of(); }
    }
}
