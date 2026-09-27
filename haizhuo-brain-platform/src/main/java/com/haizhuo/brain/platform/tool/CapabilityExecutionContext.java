package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.Objects;

/** 执行器完成一次已授权调用所需的全部信息。凭据值只留在进程内。 */
public record CapabilityExecutionContext(RunId runId, UserId userId, PlatformToolExecution execution,
                                         CapabilityCatalogEntry capability, ResolvedCredential credential) {
    public CapabilityExecutionContext {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(capability);
    }
}
