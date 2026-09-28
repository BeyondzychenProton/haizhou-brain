package com.haizhuo.brain.runtime.agentscope.tool;

import com.haizhuo.brain.runtime.api.model.RuntimeToolSchema;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

/**
 * 把企业工具以“仅 Schema”的条目注册到模板 toolkit 上（规格 §29.1）。
 * Harness 只能看到名称、描述与 JSON Schema，没有本地实现，
 * 因此对这些工具的每次调用都会通过 RequireExternalExecutionEvent 挂起，
 * 而不会在 Harness 内部产生副作用（I-06）。快照绝不携带 implementationKey、
 * MCP 服务地址或凭据（§29.2，I-08）。
 */
public final class ExternalToolSchemaAssembler {

    private ExternalToolSchemaAssembler() {
    }

    public static Toolkit assemble(List<RuntimeToolSchema> catalog) {
        Toolkit toolkit = new Toolkit();
        for (RuntimeToolSchema tool : catalog) {
            toolkit.registerSchema(ToolSchema.builder()
                    .name(tool.toolName())
                    .description(tool.description())
                    .parameters(tool.inputSchema())
                    .build());
        }
        return toolkit;
    }
}
