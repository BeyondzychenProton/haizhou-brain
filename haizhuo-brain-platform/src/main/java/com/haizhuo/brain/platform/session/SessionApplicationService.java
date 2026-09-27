package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.platform.run.SessionRunStore;
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
}
