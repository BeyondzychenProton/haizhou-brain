package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** Persistent business conversation ownership; this is deliberately distinct from a browser WebSession. */
public record AgentSession(SessionId id, UserId userId, long employeeId, Status status,
                           Instant createdAt, Instant lastActiveAt, long rowVersion) {
    public AgentSession {
        if (employeeId <= 0) throw new IllegalArgumentException("employeeId must be positive");
        if (status == null || createdAt == null || lastActiveAt == null) throw new IllegalArgumentException("session fields are required");
    }

    public enum Status { ACTIVE, CLOSED }
}
