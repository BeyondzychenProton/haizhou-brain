package com.haizhuo.brain.platform.employee;

import java.time.Instant;
import java.util.List;

/** 管理员可编辑的员工草稿，发布后会复制为不可变版本。 */
public record AgentDefinitionDraft(long employeeId, int draftRevision, String instructions,
                                   String modelProvider, String modelName,
                                   List<CapabilitySelection> capabilities, Instant updatedAt) {
    public AgentDefinitionDraft {
        capabilities = List.copyOf(capabilities);
    }
}
