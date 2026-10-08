package com.haizhuo.brain.platform.employee;

import java.time.Instant;

/** Run 开始时选定的、不可变的已发布配置。 */
public record AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                     String modelProvider, String modelName, Instant publishedAt,
                                     String contentHash, EmployeeRuntimeConfiguration configuration) {
    public AgentDefinitionVersion {
        configuration = configuration == null ? EmployeeRuntimeConfiguration.legacyStable() : configuration;
    }

    public AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                  String modelProvider, String modelName, Instant publishedAt,
                                  String contentHash) {
        this(id, employeeId, version, instructions, modelProvider, modelName, publishedAt,
                contentHash, EmployeeRuntimeConfiguration.legacyStable());
    }

    public AgentDefinitionVersion(long id, long employeeId, int version, String instructions,
                                  String modelProvider, String modelName, Instant publishedAt) {
        this(id, employeeId, version, instructions, modelProvider, modelName, publishedAt, "",
                EmployeeRuntimeConfiguration.legacyStable());
    }
}
