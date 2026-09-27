package com.haizhuo.brain.runtime.api.model;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Objects;

/**
 * 由平台交给运行时的、完全冻结的执行请求（规格 §7.6）。
 * 运行时所需的一切都在 definition/constraints/binding/input 里；
 * 该请求绝不能携带 implementationKey、凭据、数据库实体或平台仓储。
 */
public record AgentExecutionRequest(TenantId tenantId, UserId userId, SessionId platformSessionId,
                                    RunId runId, TraceId traceId, String attemptId, long fenceToken,
                                    RuntimeDefinitionSnapshot definition, RuntimeRunConstraints constraints,
                                    RuntimeSessionBinding binding, AgentExecutionInput input) {
    public AgentExecutionRequest {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(platformSessionId);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(traceId);
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(definition);
        Objects.requireNonNull(constraints);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(input);
    }
}
