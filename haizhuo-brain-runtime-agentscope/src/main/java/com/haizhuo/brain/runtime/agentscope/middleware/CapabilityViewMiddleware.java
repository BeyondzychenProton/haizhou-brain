package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 调用级、模型可见的工具视图（规格 §27/§28）。静态目录在缓存模板上完整注册；
 * 本中间件按冻结的 RuntimeRunConstraints 在每次调用时收窄 Schema 列表，
 * 因此一个 HarnessAgent 可以服务视图不同的多个 Run（I-04）。
 * Harness 内部工具（不在外部目录中的那些）始终保持可见。
 * 刻意不使用 ToolGroup.activatedGroups：它会泄漏进 AgentState（§28.1）。
 * 可见性不等于授权——执行仍须经过平台网关（I-07）。
 */
public class CapabilityViewMiddleware implements MiddlewareBase {

    private final Set<String> externalCatalogNames;

    public CapabilityViewMiddleware(Set<String> externalCatalogNames) {
        this.externalCatalogNames = Set.copyOf(Objects.requireNonNull(externalCatalogNames));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call == null) {
            // 这类调用已由 SecurityMiddleware 提前拒绝；即便顺序发生变化也保持静默，不做额外处理。
            return next.apply(input);
        }
        Set<String> visible = call.constraints().modelVisibleToolNames();
        List<ToolSchema> filtered = input.tools().stream()
                .filter(tool -> isHarnessInternal(tool) || visible.contains(tool.getName()))
                .toList();
        return next.apply(new ReasoningInput(input.messages(), filtered, input.options()));
    }

    private boolean isHarnessInternal(ToolSchema tool) {
        return !externalCatalogNames.contains(tool.getName());
    }
}
