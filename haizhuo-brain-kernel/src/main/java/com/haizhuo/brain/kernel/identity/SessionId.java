package com.haizhuo.brain.kernel.identity;

import java.util.UUID;

public record SessionId(String value) {
    public SessionId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("sessionId is required");
    }

    public static SessionId newId() { return new SessionId(UUID.randomUUID().toString()); }
}
