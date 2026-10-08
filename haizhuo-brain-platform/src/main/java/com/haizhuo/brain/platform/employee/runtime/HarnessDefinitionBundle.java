package com.haizhuo.brain.platform.employee.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeWorkspaceFile;

/**
 * 一个 AgentDefinitionVersion 的不可变、内容寻址编译产物（规格 §6.1）。
 * 在发布时生成。绝不能包含凭据、用户/会话/运行标识或运行时权限状态（I-08）。
 */
public record HarnessDefinitionBundle(long id, long definitionVersionId, String employeeName,
                                      String instructions, String modelProvider, String modelName,
                                      int maxIterations, String workspaceManifestJson, String workspaceContentHash,
                                      List<PublishedToolSchema> toolCatalog, String toolCatalogHash,
                                      String subAgentManifestJson, String policyJson, String bundleHash,
                                      EmployeeRuntimeConfiguration configuration,
                                      List<RuntimeWorkspaceFile> workspaceFiles, Instant createdAt) {
    public HarnessDefinitionBundle {
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        Objects.requireNonNull(employeeName);
        Objects.requireNonNull(instructions);
        Objects.requireNonNull(modelProvider);
        Objects.requireNonNull(modelName);
        Objects.requireNonNull(workspaceManifestJson);
        Objects.requireNonNull(workspaceContentHash);
        toolCatalog = List.copyOf(Objects.requireNonNull(toolCatalog));
        configuration = configuration == null ? EmployeeRuntimeConfiguration.legacyStable() : configuration;
        workspaceFiles = List.copyOf(Objects.requireNonNull(workspaceFiles));
        Objects.requireNonNull(toolCatalogHash);
        Objects.requireNonNull(bundleHash);
        Objects.requireNonNull(createdAt);
    }

    /** Source-compatible constructor for existing LEGACY_STABLE bundles. */
    public HarnessDefinitionBundle(long id, long definitionVersionId, String employeeName,
                                   String instructions, String modelProvider, String modelName,
                                   int maxIterations, String workspaceManifestJson, String workspaceContentHash,
                                   List<PublishedToolSchema> toolCatalog, String toolCatalogHash,
                                   String subAgentManifestJson, String policyJson, String bundleHash,
                                   Instant createdAt) {
        this(id, definitionVersionId, employeeName, instructions, modelProvider, modelName, maxIterations,
                workspaceManifestJson, workspaceContentHash, toolCatalog, toolCatalogHash, subAgentManifestJson,
                policyJson, bundleHash, EmployeeRuntimeConfiguration.legacyStable(), List.of(), createdAt);
    }
}
