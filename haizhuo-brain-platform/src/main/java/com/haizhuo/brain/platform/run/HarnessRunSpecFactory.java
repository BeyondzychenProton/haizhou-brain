package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.PublishedToolSchema;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.mcp.McpToolVisibility;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 Run 创建时计算冻结的工具视图（规格 §11）：
 * 已发布目录 ∩ 能力已启用 ∩ 供应商可用 ∩ 用户可见授权。
 * 该视图在执行阶段可以收窄，但绝不能放宽（§11.2）。
 */
public class HarnessRunSpecFactory {
    private final AgentDefinitionRepository definitions;
    private final CapabilityExecutorRegistry executors;
    private final java.util.function.Supplier<McpToolVisibility> mcpVisibility;

    public HarnessRunSpecFactory(AgentDefinitionRepository definitions, CapabilityExecutorRegistry executors) {
        this(definitions, executors, () -> (user, entry) -> {
            throw new IllegalStateException("MCP user listing is not configured");
        });
    }

    public HarnessRunSpecFactory(AgentDefinitionRepository definitions, CapabilityExecutorRegistry executors,
                                 java.util.function.Supplier<McpToolVisibility> mcpVisibility) {
        this.definitions = definitions;
        this.executors = executors;
        this.mcpVisibility = mcpVisibility;
    }

    public HarnessRunSpec create(RunId runId, UserId userId, HarnessDefinitionBundle bundle, String channelType) {
        return create(runId, userId, bundle, channelType, false);
    }

    /** Team roots and directly selected team members only receive read-only tools. */
    public HarnessRunSpec create(RunId runId, UserId userId, HarnessDefinitionBundle bundle,
                                 String channelType, boolean readOnlyOnly) {
        List<String> visible = new ArrayList<>();
        List<String> effective = new ArrayList<>();
        McpToolVisibility mcp = mcpVisibility.get();
        for (PublishedToolSchema tool : bundle.toolCatalog()) {
            if (readOnlyOnly && !tool.readOnly()) continue;
            var entry = definitions.findCapabilityByRevisionId(tool.capabilityRevisionId());
            if (entry.isEmpty()) continue;
            if (!definitions.isCapabilityEnabled(entry.get().capabilityCode(), entry.get().revision())) continue;
            if (!executors.supports(entry.get().implementationKey(), entry.get().capabilityCode())) continue;
            if (entry.get().type() == CapabilityBinding.CapabilityType.MCP) {
                if (!mcp.visible(userId, entry.get())) continue;
            } else if (!definitions.hasUserCapabilityGrant(userId.value(), entry.get().capabilityCode())) continue;
            visible.add(tool.toolName());
            effective.add(entry.get().capabilityCode() + "@" + entry.get().revision() + ":" + tool.toolName());
        }
        List<String> sortedVisible = visible.stream().sorted().toList();
        String toolViewHash = CanonicalJson.sha256(
                projection(bundle.bundleHash(), userId.value(), sortedVisible));
        String effectiveCapabilityHash = CanonicalJson.sha256(
                projection(bundle.bundleHash(), userId.value(), effective.stream().sorted().toList()));
        return new HarnessRunSpec(runId, bundle.definitionVersionId(), bundle.id(), bundle.bundleHash(),
                HarnessRunSpec.toolNamesJson(sortedVisible), toolViewHash, effectiveCapabilityHash,
                channelType, Instant.now());
    }

    private static java.util.Map<String, Object> projection(String bundleHash, long userId, List<String> items) {
        java.util.Map<String, Object> projection = new java.util.LinkedHashMap<>();
        projection.put("bundleHash", bundleHash);
        projection.put("userId", userId);
        projection.put("items", items);
        return projection;
    }
}
