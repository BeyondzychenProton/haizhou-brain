package com.haizhuo.brain.kernel.identity;

import java.util.UUID;

public record RunId(String value) {
    public RunId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("runId is required");
    }

    public static RunId newId() { return new RunId(UUID.randomUUID().toString()); }
}
