package com.haizhuo.brain.platform.employee.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一个 AgentDefinitionVersion 的不可变、内容寻址编译产物（规格 §6.1）。
 * 在发布时生成。绝不能包含凭据、用户/会话/运行标识或运行时权限状态（I-08）。
 */
public record HarnessDefinitionBundle(long id, long definitionVersionId, String employeeName,
                                      String instructions, String modelProvider, String modelName,
                                      int maxIterations, String workspaceManifestJson, String workspaceContentHash,
                                      List<PublishedToolSchema> toolCatalog, String toolCatalogHash,
                                      String subAgentManifestJson, String policyJson, String bundleHash,
                                      Instant createdAt) {
    public HarnessDefinitionBundle {
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        Objects.requireNonNull(employeeName);
        Objects.requireNonNull(instructions);
        Objects.requireNonNull(modelProvider);
        Objects.requireNonNull(modelName);
        Objects.requireNonNull(workspaceManifestJson);
        Objects.requireNonNull(workspaceContentHash);
        toolCatalog = List.copyOf(Objects.requireNonNull(toolCatalog));
        Objects.requireNonNull(toolCatalogHash);
        Objects.requireNonNull(bundleHash);
        Objects.requireNonNull(createdAt);
    }
}
