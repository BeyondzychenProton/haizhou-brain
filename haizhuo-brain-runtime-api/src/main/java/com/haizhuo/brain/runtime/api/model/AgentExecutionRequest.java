package com.haizhuo.brain.runtime.api.model;

import com.haizhuo.brain.kernel.identity.*;

public record AgentExecutionRequest(TenantId tenantId, UserId userId, AgentTaskId taskId, TraceId traceId,
                                    String prompt) {
}
