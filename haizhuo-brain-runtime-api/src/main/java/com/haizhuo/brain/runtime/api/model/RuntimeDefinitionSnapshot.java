package com.haizhuo.brain.runtime.api.model;

import java.util.List;
import java.util.Objects;

/**
 * 运行时消费的、内容寻址且不可变的定义快照（规格 §7.2）。
 * 在发布时编译完成；运行时不会再去解析草稿或最新版本。
 */
public record RuntimeDefinitionSnapshot(long definitionVersionId, String employeeName, String instructions,
                                        String modelProvider, String modelName, int maxIterations,
                                        String definitionBundleHash, String workspaceProjectionKey,
                                        String workspaceContentHash, String workspaceManifestJson,
                                        List<RuntimeToolSchema> toolCatalog,
                                        RuntimeEmployeeConfiguration configuration,
                                        List<RuntimeWorkspaceFile> workspaceFiles) {
    public RuntimeDefinitionSnapshot {
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        Objects.requireNonNull(employeeName);
        Objects.requireNonNull(instructions);
        Objects.requireNonNull(modelProvider);
        Objects.requireNonNull(modelName);
        Objects.requireNonNull(definitionBundleHash);
        Objects.requireNonNull(workspaceManifestJson);
        toolCatalog = List.copyOf(Objects.requireNonNull(toolCatalog));
        configuration = configuration == null ? RuntimeEmployeeConfiguration.legacyStable() : configuration;
        workspaceFiles = List.copyOf(Objects.requireNonNull(workspaceFiles));
    }

    /** Source-compatible constructor for existing LEGACY_STABLE runtime snapshots. */
    public RuntimeDefinitionSnapshot(long definitionVersionId, String employeeName, String instructions,
                                     String modelProvider, String modelName, int maxIterations,
                                     String definitionBundleHash, String workspaceProjectionKey,
                                     String workspaceContentHash, List<RuntimeToolSchema> toolCatalog) {
        this(definitionVersionId, employeeName, instructions, modelProvider, modelName, maxIterations,
                definitionBundleHash, workspaceProjectionKey, workspaceContentHash,
                "{\"schemaVersion\":1,\"agents\":\"AGENTS.md\",\"skills\":[],\"subagents\":[],\"knowledge\":[]}",
                toolCatalog,
                RuntimeEmployeeConfiguration.legacyStable(), List.of());
    }
}
