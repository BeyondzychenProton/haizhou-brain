package com.haizhuo.brain.platform.employee.runtime;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性的能力包编译器（规格 §8.3/§8.4）。先校验模型、工具与子 agent 规则，
 * 再对规范化 JSON 投影取哈希。凭据轮换不会改变能力包，因为密钥不属于编译内容（I-09）。
 */
public class DefaultHarnessDefinitionBundleCompiler implements HarnessDefinitionBundleCompiler {
    static final int MAX_ITERATIONS_FLOOR = 1;
    static final int MAX_ITERATIONS_CEILING = 20;

    private final AgentDefinitionRepository repository;

    public DefaultHarnessDefinitionBundleCompiler(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    public HarnessDefinitionBundle compile(long definitionVersionId, String employeeName, AgentDefinitionDraft draft) {
        if (definitionVersionId <= 0) throw new IllegalArgumentException("definitionVersionId must be positive");
        if (employeeName == null || employeeName.isBlank()) throw new IllegalArgumentException("employeeName is required");
        if (draft.modelProvider() == null || draft.modelProvider().isBlank())
            throw new IllegalArgumentException("Model provider must not be blank");
        if (draft.modelName() == null || draft.modelName().isBlank())
            throw new IllegalArgumentException("Model name must not be blank");
        if (draft.instructions() == null || draft.instructions().isBlank())
            throw new IllegalArgumentException("Instructions must not be blank");
        int maxIterations = MAX_ITERATIONS_FLOOR; // 草稿版本尚未携带迭代策略，先钳到合法区间

        List<PublishedToolSchema> catalog = new ArrayList<>();
        Set<String> toolNames = new HashSet<>();
        for (CapabilitySelection selection : draft.capabilities()) {
            CapabilityCatalogEntry entry = repository.findCapability(selection.capabilityCode(), selection.revision())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Capability revision does not exist: " + selection.capabilityCode() + "@" + selection.revision()));
            if (entry.type() != CapabilityBinding.CapabilityType.TOOL)
                throw new IllegalArgumentException("Only tool capabilities can enter the runtime bundle: " + selection.capabilityCode());
            if (entry.toolName() == null || entry.toolName().isBlank())
                throw new IllegalArgumentException("Tool name must not be blank: " + selection.capabilityCode());
            if (entry.description() == null || entry.description().isBlank())
                throw new IllegalArgumentException("Tool description must not be blank: " + selection.capabilityCode());
            if (entry.inputSchema() == null || entry.inputSchema().isEmpty()
                    || !"object".equals(entry.inputSchema().get("type")))
                throw new IllegalArgumentException("Tool input schema must be a JSON Schema object: " + selection.capabilityCode());
            if (!toolNames.add(entry.toolName()))
                throw new IllegalArgumentException("Duplicate tool name in draft: " + entry.toolName());
            catalog.add(new PublishedToolSchema(entry.capabilityRevisionId(), entry.capabilityCode(), entry.toolName(),
                    entry.description(), entry.inputSchema(), false, entry.requiresConfirmation(), entry.businessAction()));
        }

        Map<String, Object> workspaceManifest = new LinkedHashMap<>();
        workspaceManifest.put("schemaVersion", 1);
        workspaceManifest.put("agents", "AGENTS.md");
        workspaceManifest.put("skills", List.of());
        workspaceManifest.put("subagents", List.of());
        workspaceManifest.put("knowledge", List.of());
        String workspaceManifestJson = CanonicalJson.write(workspaceManifest);
        String workspaceContentHash = CanonicalJson.sha256(
                Map.of("instructions", draft.instructions(), "manifestJson", workspaceManifestJson));

        String toolCatalogHash = CanonicalJson.sha256(canonicalCatalog(catalog));
        String subAgentManifestJson = CanonicalJson.write(Map.of("schemaVersion", 1, "subagents", List.of()));
        String policyJson = CanonicalJson.write(Map.of(
                "memoryHooksEnabled", false,
                "memoryToolsEnabled", false,
                "maxIterations", maxIterations));

        Map<String, Object> bundleProjection = new LinkedHashMap<>();
        bundleProjection.put("definitionVersionId", definitionVersionId);
        bundleProjection.put("employeeName", employeeName);
        bundleProjection.put("instructions", draft.instructions());
        bundleProjection.put("modelProvider", draft.modelProvider());
        bundleProjection.put("modelName", draft.modelName());
        bundleProjection.put("maxIterations", maxIterations);
        bundleProjection.put("workspaceContentHash", workspaceContentHash);
        bundleProjection.put("toolCatalog", canonicalCatalog(catalog));
        bundleProjection.put("toolCatalogHash", toolCatalogHash);
        bundleProjection.put("subAgentManifestJson", subAgentManifestJson);
        bundleProjection.put("policyJson", policyJson);
        String bundleHash = CanonicalJson.sha256(bundleProjection);

        return new HarnessDefinitionBundle(0, definitionVersionId, employeeName, draft.instructions(),
                draft.modelProvider(), draft.modelName(), maxIterations, workspaceManifestJson, workspaceContentHash,
                catalog, toolCatalogHash, subAgentManifestJson, policyJson, bundleHash, Instant.now());
    }

    private static List<Map<String, Object>> canonicalCatalog(List<PublishedToolSchema> catalog) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (PublishedToolSchema tool : catalog) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityRevisionId", tool.capabilityRevisionId());
            item.put("capabilityReferenceId", tool.capabilityReferenceId());
            item.put("toolName", tool.toolName());
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            item.put("readOnly", tool.readOnly());
            item.put("requiresConfirmation", tool.requiresConfirmation());
            item.put("businessAction", tool.businessAction());
            entries.add(item);
        }
        return entries;
    }
}
