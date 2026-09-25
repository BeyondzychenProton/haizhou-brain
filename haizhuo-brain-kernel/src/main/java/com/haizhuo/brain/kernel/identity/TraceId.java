package com.haizhuo.brain.kernel.identity;

import java.util.UUID;

public record TraceId(String value) {
    public static TraceId newId() {
        return new TraceId(UUID.randomUUID().toString());
    }
}
