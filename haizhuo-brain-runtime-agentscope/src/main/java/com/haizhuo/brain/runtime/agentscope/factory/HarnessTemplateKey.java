package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Harness 运行时模板的内容寻址缓存键（规格 §18）。该投影刻意排除
 * userId/sessionId/runId/toolViewHash/凭据/workspaceRuntimeKey/bridgeContext，
 * 因此同一个缓存 HarnessAgent 可以服务同一份冻结定义内容下的所有 Run（I-04）。
 */
public record HarnessTemplateKey(String value) {

    /**
     * HarnessAgentFactory 所应用的 P0 Harness 策略开关的哈希（按 I-15 关闭 memory hooks/tools，
     * 按 P0 安全基线关闭 shell 工具与子 agent）。任何策略变更都必须改变这项输入，
     * 以免复用过期模板。
     */
    private static final String HARNESS_POLICY_HASH = CanonicalJson.sha256(Map.of(
            "policy", "haizhuo-p0",
            "memoryHooksEnabled", false,
            "memoryToolsEnabled", false,
            "shellToolEnabled", false,
            "subagentsEnabled", "profile-scoped-fixed-experts"));

    public HarnessTemplateKey {
        Objects.requireNonNull(value);
        if (value.isBlank()) throw new IllegalArgumentException("value must not be blank");
    }

    public static HarnessTemplateKey from(RuntimeDefinitionSnapshot definition) {
        Objects.requireNonNull(definition);
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("definitionBundleHash", definition.definitionBundleHash());
        projection.put("modelProvider", definition.modelProvider());
        projection.put("modelName", definition.modelName());
        projection.put("maxIterations", definition.maxIterations());
        projection.put("staticToolCatalogHash", staticToolCatalogHash(definition.toolCatalog()));
        projection.put("workspaceContentHash", definition.workspaceContentHash());
        projection.put("harnessPolicyHash", HARNESS_POLICY_HASH);
        return new HarnessTemplateKey(CanonicalJson.sha256(projection));
    }

    private static String staticToolCatalogHash(List<RuntimeToolSchema> catalog) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RuntimeToolSchema tool : catalog) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("capabilityRevisionId", tool.capabilityRevisionId());
            item.put("toolName", tool.toolName());
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            item.put("requiresConfirmation", tool.requiresConfirmation());
            entries.add(item);
        }
        return CanonicalJson.sha256(entries);
    }
}
