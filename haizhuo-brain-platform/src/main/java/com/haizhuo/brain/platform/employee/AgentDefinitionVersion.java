package com.haizhuo.brain.platform.employee;

import java.time.Instant;

/** Run 开始时选定的、不可变的已发布配置。 */
public record AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                     String modelProvider, String modelName, Instant publishedAt,
                                     String contentHash) {
    public AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                  String modelProvider, String modelName, Instant publishedAt) {
        this(id, employeeId, version, instructions, modelProvider, modelName, publishedAt, "");
    }
}
