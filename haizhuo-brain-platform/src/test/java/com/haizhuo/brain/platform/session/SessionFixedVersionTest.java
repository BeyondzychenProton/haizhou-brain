package com.haizhuo.brain.platform.session;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.*;
import com.haizhuo.brain.platform.employee.*;
import com.haizhuo.brain.platform.employee.runtime.*;
import com.haizhuo.brain.platform.run.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionFixedVersionTest {
    private static final UserId OWNER = new UserId(42);
    private static final Instant NOW = Instant.parse("2026-10-07T07:00:00Z");
    private final MemoryStore store = new MemoryStore();
    private long currentVersion = 1;
    private final HarnessDefinitionBundleRepository bundles = new HarnessDefinitionBundleRepository() {
        public HarnessDefinitionBundle save(HarnessDefinitionBundle bundle) { return bundle; }
        public Optional<HarnessDefinitionBundle> findByBundleHash(String hash) { return Optional.empty(); }
        public Optional<HarnessDefinitionBundle> findByDefinitionVersionId(long id) {
            return Optional.of(new HarnessDefinitionBundle(id, id, "员工", "指令-" + id, "openai", "model-" + id,
                    3, "{}", "workspace-" + id, List.of(), "tools", "{}", "{}", "bundle-" + id, NOW));
        }
    };
    private SessionApplicationService service() {
        EmployeeCatalog employees = (tenant, employeeId) -> Optional.of(new PublishedEmployee(
                new DigitalEmployee(employeeId, tenant, "employee", "员工", true),
                new AgentDefinitionVersion(currentVersion, employeeId, (int) currentVersion, "指令", "openai", "model", NOW),
                List.of()));
        return new SessionApplicationService(store, employees, bundles, new HarnessRunSpecFactory(null, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publicationChangesOnlyNewSessions() {
        var service = service();
        AgentSession first = service.create(OWNER, 1);
        assertEquals(1L, first.definitionVersionId());
        assertFalse(first.legacyRuntime());
        currentVersion = 2;
        AgentRun continued = service.createRun(first.id(), OWNER, "req-old", "继续");
        assertEquals(1, continued.definitionVersionId());
        assertEquals(1, store.lastSpec.definitionVersionId());
        AgentSession second = service.create(OWNER, 1);
        assertEquals(2L, second.definitionVersionId());
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void legacyEmptySessionPinsVersionOnceAndRetainsOldIdentityMode() {
        var legacy = new AgentSession(SessionId.newId(), OWNER, 1, AgentSession.Status.ACTIVE, NOW, NOW, 0);
        store.createSession(legacy);
        service().createRun(legacy.id(), OWNER, "first", "首次");
        currentVersion = 2;
        assertEquals(1, service().createRun(legacy.id(), OWNER, "second", "继续").definitionVersionId());
        assertTrue(store.sessions.get(legacy.id()).legacyRuntime());
    }

    @Test
    void newSessionCannotOmitVersionAndAnotherOwnerCannotContinueIt() {
        assertThrows(IllegalArgumentException.class, () -> new AgentSession(SessionId.newId(), OWNER, 1,
                AgentSession.Status.ACTIVE, NOW, NOW, 0, null, false));
        var first = service().create(OWNER, 1);
        assertThrows(IllegalArgumentException.class,
                () -> service().createRun(first.id(), new UserId(99), "foreign", "继续"));
    }

    private static final class MemoryStore implements SessionRunStore {
        final Map<SessionId, AgentSession> sessions = new HashMap<>();
        HarnessRunSpec lastSpec;
        public AgentSession createSession(AgentSession session) { sessions.put(session.id(), session); return session; }
        public Optional<AgentSession> findSession(SessionId id, UserId owner) {
            return Optional.ofNullable(sessions.get(id)).filter(s -> s.userId().equals(owner));
        }
        public AgentSession pinDefinitionVersion(SessionId id, UserId owner, long version) {
            AgentSession s = findSession(id, owner).orElseThrow();
            if (s.definitionVersionId() == null) {
                s = new AgentSession(s.id(), s.userId(), s.employeeId(), s.status(), s.createdAt(), s.lastActiveAt(),
                        s.rowVersion() + 1, version, s.legacyRuntime());
                sessions.put(id, s);
            }
            return s;
        }
        public AgentRun createRun(AgentRun run, String input, HarnessRunSpec spec) { lastSpec = spec; return run; }
        public Optional<AgentRun> findRun(RunId id, UserId owner) { return Optional.empty(); }
        public List<RunEvent> findEvents(RunId id, UserId owner, int after, int limit) { return List.of(); }
    }
}
