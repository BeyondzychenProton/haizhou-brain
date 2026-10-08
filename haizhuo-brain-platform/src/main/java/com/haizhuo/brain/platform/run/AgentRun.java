package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Instant;

/** 一次执行尝试的不可变属主与已发布定义快照。 */
public record AgentRun(RunId id, SessionId sessionId, UserId userId, long employeeId,
                       long definitionVersionId, String clientRequestId, String inputDigest,
                       RunState state, Instant createdAt, Instant startedAt, Instant finishedAt,
                       RuntimeProfile runtimeProfile) {
    /** Existing fixtures and integrations without a frozen profile retain the legacy retry policy. */
    public AgentRun(RunId id, SessionId sessionId, UserId userId, long employeeId,
                    long definitionVersionId, String clientRequestId, String inputDigest,
                    RunState state, Instant createdAt, Instant startedAt, Instant finishedAt) {
        this(id, sessionId, userId, employeeId, definitionVersionId, clientRequestId, inputDigest,
                state, createdAt, startedAt, finishedAt, RuntimeProfile.LEGACY_STABLE);
    }

    public AgentRun {
        if (employeeId <= 0 || definitionVersionId <= 0) throw new IllegalArgumentException("employee and definition version are required");
        if (clientRequestId == null || clientRequestId.isBlank() || inputDigest == null || inputDigest.isBlank()
                || state == null || createdAt == null || runtimeProfile == null)
            throw new IllegalArgumentException("run fields are required");
    }
}
