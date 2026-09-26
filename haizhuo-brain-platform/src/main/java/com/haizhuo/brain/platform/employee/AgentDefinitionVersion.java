package com.haizhuo.brain.platform.employee;

import java.time.Instant;

/** Immutable published configuration selected when a Run begins. */
public record AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                     String modelProvider, String modelName, Instant publishedAt,
                                     String contentHash) {
    public AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                  String modelProvider, String modelName, Instant publishedAt) {
        this(id, employeeId, version, instructions, modelProvider, modelName, publishedAt, "");
    }
}
