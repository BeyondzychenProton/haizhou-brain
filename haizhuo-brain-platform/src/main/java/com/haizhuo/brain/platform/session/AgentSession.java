package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** 持久化的业务会话属主关系；刻意与浏览器 WebSession 区分开。 */
public record AgentSession(SessionId id, UserId userId, long employeeId, Status status,
                           Instant createdAt, Instant lastActiveAt, long rowVersion,
                           Long definitionVersionId, boolean legacyRuntime) {
    /** 仅供存量会话与旧测试夹具使用；生产新建必须显式固定版本。 */
    public AgentSession(SessionId id, UserId userId, long employeeId, Status status,
                        Instant createdAt, Instant lastActiveAt, long rowVersion) {
        this(id, userId, employeeId, status, createdAt, lastActiveAt, rowVersion, null, true);
    }

    public AgentSession {
        if (definitionVersionId != null && definitionVersionId <= 0)
            throw new IllegalArgumentException("definitionVersionId must be positive");
        if (!legacyRuntime && definitionVersionId == null)
            throw new IllegalArgumentException("New session requires a fixed definition version");
        if (employeeId <= 0) throw new IllegalArgumentException("employeeId must be positive");
        if (status == null || createdAt == null || lastActiveAt == null) throw new IllegalArgumentException("session fields are required");
    }

    public enum Status { ACTIVE, CLOSED }
}
