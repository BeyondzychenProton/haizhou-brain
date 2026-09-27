package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** Immutable ownership and published-definition snapshot for one execution attempt. */
public record AgentRun(RunId id, SessionId sessionId, UserId userId, long employeeId,
                       long definitionVersionId, String clientRequestId, String inputDigest,
                       RunState state, Instant createdAt, Instant startedAt, Instant finishedAt) {
    public AgentRun {
        if (employeeId <= 0 || definitionVersionId <= 0) throw new IllegalArgumentException("employee and definition version are required");
        if (clientRequestId == null || clientRequestId.isBlank() || inputDigest == null || inputDigest.isBlank()
                || state == null || createdAt == null) throw new IllegalArgumentException("run fields are required");
    }
}
