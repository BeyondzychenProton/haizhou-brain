package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;
import java.util.Objects;

/** Trusted, durable mapping from a published team role to its isolated AgentScope state/workspace keys. */
public record SessionRoleSlot(String id, SessionId sessionId, UserId userId, String roleId,
                              long employeeId, long definitionVersionId,
                              String harnessSessionKey, String workspaceRuntimeKey, Instant createdAt) {
    public SessionRoleSlot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(roleId);
        Objects.requireNonNull(harnessSessionKey);
        Objects.requireNonNull(workspaceRuntimeKey);
        Objects.requireNonNull(createdAt);
        if (id.isBlank() || roleId.isBlank() || harnessSessionKey.isBlank() || workspaceRuntimeKey.isBlank())
            throw new IllegalArgumentException("Role slot keys must not be blank");
        if (employeeId <= 0 || definitionVersionId <= 0)
            throw new IllegalArgumentException("Role slot employee and definition version must be positive");
    }
}
