package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * P0 护栏：拒绝任何没有携带平台下发的 HarnessCallContext 的 Harness 调用，
 * 从而保证 agent 绝不会在未冻结的作用域上运行（没有 runId、没有工具视图、没有工作区键）。
 * 在每个模板上都最先注册。
 */
public class SecurityMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (context.get(HarnessCallContext.class) == null) {
            return Flux.error(new IllegalStateException(
                    "HarnessCallContext is required; refusing to run without a frozen platform scope"));
        }
        return next.apply(input);
    }
}
