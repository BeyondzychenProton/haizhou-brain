package com.haizhuo.brain.kernel.identity;

import java.util.UUID;

public record AgentTaskId(String value) {
    public static AgentTaskId newId() {
        return new AgentTaskId(UUID.randomUUID().toString());
    }
}
