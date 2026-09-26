package com.haizhuo.brain.runtime.agentscope.tool;

import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** 以目录中的名称、描述和 Schema 创建单次 Run 专属工具对象。 */
final class DynamicCapabilityAgentTool implements AgentTool {
    private final RuntimeCapability capability;
    private final AgentExecutionRequest run;
    private final ToolExecutionGateway gateway;

    DynamicCapabilityAgentTool(RuntimeCapability capability, AgentExecutionRequest run, ToolExecutionGateway gateway) {
        this.capability = Objects.requireNonNull(capability);
        this.run = Objects.requireNonNull(run);
        this.gateway = Objects.requireNonNull(gateway);
        if (capability.toolName() == null || capability.description() == null || capability.inputSchema().isEmpty())
            throw new IllegalArgumentException("Capability tool metadata is incomplete");
    }

    @Override public String getName() { return capability.toolName(); }
    @Override public String getDescription() { return capability.description(); }
    @Override public Map<String, Object> getParameters() { return capability.inputSchema(); }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> gateway.execute(run, capability.referenceId(), param.getInput()))
                .map(result -> ToolResultBlock.of(param.getToolUseBlock().getId(), getName(),
                        TextBlock.builder().text(result).build()))
                .onErrorResume(ignored -> Mono.just(ToolResultBlock.error(param.getToolUseBlock().getId(),
                        "该能力未通过执行校验或暂时执行失败。").withIdAndName(param.getToolUseBlock().getId(), getName())));
    }
}
