package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.kernel.identity.RunId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;

/** Creates and reads only sessions owned by the authenticated platform user. */
public class SessionApplicationService {
    private static final TenantId DEFAULT_TENANT = new TenantId(1);
    private final SessionRunStore store;
    private final EmployeeCatalog employees;
    private final Clock clock;

    public SessionApplicationService(SessionRunStore store, EmployeeCatalog employees, Clock clock) {
        this.store = store;
        this.employees = employees;
        this.clock = clock;
    }

    public AgentSession create(UserId owner, long employeeId) {
        employees.findPublished(DEFAULT_TENANT, employeeId)
                .orElseThrow(() -> new IllegalArgumentException("Published employee was not found"));
        Instant now = clock.instant();
        return store.createSession(new AgentSession(SessionId.newId(), owner, employeeId,
                AgentSession.Status.ACTIVE, now, now, 0));
    }

    public AgentSession get(SessionId sessionId, UserId owner) {
        return store.findSession(sessionId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Session was not found"));
    }

    public AgentRun createRun(SessionId sessionId, UserId owner, String clientRequestId, String input) {
        AgentSession session = get(sessionId, owner);
        var published = employees.findPublished(DEFAULT_TENANT, session.employeeId()).orElseThrow(() -> new IllegalStateException("Session employee is no longer published"));
        Instant now = clock.instant();
        return store.createRun(new AgentRun(RunId.newId(), session.id(), owner, session.employeeId(), published.definition().id(), clientRequestId, digest(input), RunState.QUEUED, now, null, null), input);
    }

    public AgentRun getRun(RunId runId, UserId owner) { return store.findRun(runId, owner).orElseThrow(() -> new IllegalArgumentException("Run was not found")); }
    public java.util.List<RunEvent> events(RunId runId, UserId owner, int afterSequence) {
        getRun(runId, owner);
        return store.findEvents(runId, owner, Math.max(afterSequence, 0), 200);
    }

    private static String digest(String input) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
