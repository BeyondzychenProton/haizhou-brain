package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 在 agent/推理边界上的最小结构化日志（规格 §5.4）。只记录标识与计数——
 * 绝不记录消息正文、工具参数、供应商响应或密钥。
 */
public class ObservabilityMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityMiddleware.class);

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext context, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call != null) {
            log.info("harness.call.started runId={} attemptId={} fenceToken={}",
                    call.runId().value(), call.attemptId(), call.fenceToken());
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call != null) {
            log.debug("harness.reasoning.started runId={} toolCount={}",
                    call.runId().value(), input.tools().size());
        }
        return next.apply(input);
    }
}
