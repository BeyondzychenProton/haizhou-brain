package com.haizhuo.brain.runtime.api.model;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.List;
import java.util.Objects;

/** Snapshot chosen by the platform when a Run starts, never derived from a channel payload. */
public record AgentExecutionRequest(TenantId tenantId, UserId userId, SessionId sessionId, RunId runId,
                                    TraceId traceId, long definitionVersionId, String employeeName,
                                    String instructions, String modelProvider, String modelName,
                                    List<RuntimeCapability> capabilities, String prompt) {
    public AgentExecutionRequest {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(traceId);
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        capabilities = List.copyOf(capabilities);
    }
}
