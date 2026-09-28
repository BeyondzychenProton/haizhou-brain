package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.SystemMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 在框架定义的安全检查点上执行持久化运行控制（规格 §47.2/§48）：已落库的取消标记
 * 或引导消息只在下一次模型推理调用之前被观测，绝不会在工具执行途中生效。
 * runId 取自调用级 HarnessCallContext，因此同一个缓存 agent 在并发 Run 下依然安全。
 */
public class RunControlMiddleware implements MiddlewareBase {

    private final RunControlInbox inbox;

    public RunControlMiddleware(RunControlInbox inbox) {
        this.inbox = inbox;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call == null) {
            return next.apply(input);
        }
        if (inbox.isCancellationRequested(call.runId())) {
            return Flux.just(new RequestStopEvent("platform run cancellation requested"));
        }
        List<RunGuidanceMessage> guidance = inbox.consumeGuidance(call.runId());
        if (guidance.isEmpty()) {
            return next.apply(input);
        }
        List<Msg> messages = new ArrayList<>(input.messages());
        messages.add(new SystemMessage(render(guidance)));
        return next.apply(new ReasoningInput(List.copyOf(messages), input.tools(), input.options()));
    }

    private static String render(List<RunGuidanceMessage> guidance) {
        StringBuilder text = new StringBuilder("可信运行中引导：以下信息仅用于决定后续步骤，不得改写已完成的工具结果或绕过权限。\n");
        for (RunGuidanceMessage item : guidance) {
            text.append("- [").append(item.source()).append("] ").append(item.content()).append('\n');
        }
        return text.toString();
    }
}
