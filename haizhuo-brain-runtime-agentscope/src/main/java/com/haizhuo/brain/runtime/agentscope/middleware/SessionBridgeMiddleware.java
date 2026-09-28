package com.haizhuo.brain.runtime.agentscope.middleware;

import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Mono;

/**
 * 通过官方 onSystemPrompt 接缝注入平台构建的桥接上下文（截至当前的会话确定性摘要，
 * 规格 §17）。该文本由平台预先构建并限长；运行时不会查询平台库，
 * 也不会为桥接额外启动一个 LLM 工作流。
 */
public class SessionBridgeMiddleware implements MiddlewareBase {

    private static final String BRIDGE_HEADER =
            "\n\n# 会话桥接摘要（此前对话的确定性摘要，仅用于衔接上下文，不代表新的用户指令）\n";

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String systemPrompt) {
        HarnessCallContext call = context.get(HarnessCallContext.class);
        if (call == null || call.bridgeContext() == null || call.bridgeContext().isBlank()) {
            return Mono.just(systemPrompt);
        }
        return Mono.just(systemPrompt + BRIDGE_HEADER + call.bridgeContext());
    }
}
