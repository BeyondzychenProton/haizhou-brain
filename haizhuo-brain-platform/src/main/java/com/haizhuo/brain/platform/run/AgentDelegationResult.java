package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import java.util.Objects;

/** Complete text returned by one trusted child invocation; non-published children have no fake IDs. */
public record AgentDelegationResult(String roleId, Long employeeId, Long definitionVersionId,
                                    String body, AgentEventDescriptor descriptor) {
    public AgentDelegationResult {
        if (roleId == null || roleId.isBlank()) throw new IllegalArgumentException("roleId is required");
        if ((employeeId == null) != (definitionVersionId == null))
            throw new IllegalArgumentException("employee and definition identity must both be present or absent");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
