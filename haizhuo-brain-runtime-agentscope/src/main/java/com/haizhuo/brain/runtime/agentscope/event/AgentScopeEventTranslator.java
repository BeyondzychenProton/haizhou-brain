package com.haizhuo.brain.runtime.agentscope.event;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.event.PendingExternalToolCall;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 把 AgentScope 流事件翻译成平台运行时事件模型（规格 §33/§34）。
 * 没有平台对应物的事件类型（思考增量、Harness 内部工具的调用开始/结束、
 * 模型调用标记等）一律丢弃——平台只持久化自己的事件模型。
 */
public class AgentScopeEventTranslator {

    public Flux<BrainAgentEvent> translate(RunId runId, AgentEvent event) {
        if (event instanceof RequireExternalExecutionEvent suspension) {
            List<PendingExternalToolCall> calls = suspension.getToolCalls().stream()
                    .map(tool -> new PendingExternalToolCall(tool.getId(), tool.getName(), tool.getInput()))
                    .toList();
            if (calls.isEmpty()) {
                return Flux.empty();
            }
            return Flux.just(new AgentToolSuspendedEvent(runId, suspension.getReplyId(), calls));
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            return Flux.just(new AgentTextDeltaEvent(runId, delta.getDelta()));
        }
        if (event instanceof AgentResultEvent result) {
            Msg message = result.getResult();
            String text = message == null ? null : message.getTextContent();
            return Flux.just(new AgentRunCompletedEvent(runId, text == null ? "" : text));
        }
        if (event instanceof RequestStopEvent) {
            return Flux.just(new AgentRunCancelledEvent(runId, "运行已在安全检查点取消"));
        }
        return Flux.empty();
    }
}
