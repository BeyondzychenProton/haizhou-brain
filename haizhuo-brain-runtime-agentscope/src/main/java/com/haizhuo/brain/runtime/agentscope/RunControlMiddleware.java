package com.haizhuo.brain.runtime.agentscope;

import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
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
 * Uses AgentScope's official per-reasoning middleware seam. It never interrupts a tool in flight:
 * a persisted control message is observed only before the next model reasoning call.
 */
final class RunControlMiddleware implements MiddlewareBase {
    private final RunControlInbox inbox;

    RunControlMiddleware(RunControlInbox inbox) { this.inbox = inbox; }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext context, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentExecutionRequest run = context.get(AgentExecutionRequest.class);
        if (run == null) return next.apply(input);
        if (inbox.isCancellationRequested(run.runId())) {
            return Flux.just(new RequestStopEvent("platform run cancellation requested"));
        }
        List<RunGuidanceMessage> guidance = inbox.consumeGuidance(run.runId());
        if (guidance.isEmpty()) return next.apply(input);
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
