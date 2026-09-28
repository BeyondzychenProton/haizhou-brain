package com.haizhuo.brain.runtime.agentscope.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.event.AgentPlanUpdatedEvent;
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
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * 把 AgentScope 流事件翻译成平台运行时事件模型（规格 §33/§34）。
 * 没有平台对应物的事件类型（思考增量、Harness 内部工具的调用开始/结束、
 * 模型调用标记等）一律丢弃——平台只持久化自己的事件模型。
 *
 * <p>例外是计划工具：它的计划正文只存在于工具入参的流式增量里，
 * 因此必须缓冲到调用结束再翻译成 {@link AgentPlanUpdatedEvent}（P2 plan.snapshot）。</p>
 */
public class AgentScopeEventTranslator {

    /** 计划工具名取自 Harness PlanModeTools（javap 核对取值：plan_enter / plan_write / plan_exit）。 */
    static final String PLAN_ENTER = "plan_enter";
    static final String PLAN_WRITE = "plan_write";
    static final String PLAN_EXIT = "plan_exit";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 计划正文的候选字段；不同模型给计划工具的入参键名不一致，按序取第一个非空文本。 */
    private static final List<String> PLAN_FIELDS =
            List.of("plan", "content", "plan_content", "planContent", "markdown", "text");

    /**
     * 计划工具入参按 toolCallId 缓冲，key 带 runId，便于 Run 终止时整体清理；
     * 同一 toolCallId 的增量在同一线程内顺序到达，因此缓冲本身不需要再加锁。
     */
    private final Map<String, PlanBuffer> planBuffers = new ConcurrentHashMap<>();

    public Flux<BrainAgentEvent> translate(RunId runId, AgentEvent event) {
        if (event instanceof ToolCallStartEvent started && isPlanTool(started.getToolCallName())) {
            // 计划工具在调用开始就建立缓冲：enter/exit 可能完全没有入参，
            // 但平台仍需要它们来表达计划模式的开关。
            planBuffers.putIfAbsent(key(runId, started.getToolCallId()),
                    new PlanBuffer(runId, started.getToolCallName()));
            return Flux.empty();
        }
        if (event instanceof ToolCallDeltaEvent delta && isPlanTool(delta.getToolCallName())) {
            planBuffers.computeIfAbsent(key(runId, delta.getToolCallId()),
                    ignored -> new PlanBuffer(runId, delta.getToolCallName())).append(delta.getDelta());
            return Flux.empty();
        }
        if (event instanceof ToolCallEndEvent ended) {
            // 结束事件不一定带工具名，所以按 toolCallId 取缓冲而不是按名字判断。
            PlanBuffer buffer = planBuffers.remove(key(runId, ended.getToolCallId()));
            return buffer == null ? Flux.empty() : Flux.just(planEvent(buffer));
        }
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
            forgetRun(runId);
            Msg message = result.getResult();
            String text = message == null ? null : message.getTextContent();
            return Flux.just(new AgentRunCompletedEvent(runId, text == null ? "" : text));
        }
        if (event instanceof RequestStopEvent) {
            forgetRun(runId);
            return Flux.just(new AgentRunCancelledEvent(runId, "运行已在安全检查点取消"));
        }
        return Flux.empty();
    }

    private static BrainAgentEvent planEvent(PlanBuffer buffer) {
        return new AgentPlanUpdatedEvent(buffer.runId, phaseOf(buffer.toolName), extractPlan(buffer.raw()));
    }

    private static AgentPlanUpdatedEvent.Phase phaseOf(String toolName) {
        if (PLAN_ENTER.equals(toolName)) {
            return AgentPlanUpdatedEvent.Phase.ENTER;
        }
        return PLAN_EXIT.equals(toolName) ? AgentPlanUpdatedEvent.Phase.EXIT : AgentPlanUpdatedEvent.Phase.WRITE;
    }

    /** 入参不是合法 JSON 时保留原文：宁可展示未解析的计划，也不要丢掉整份计划。 */
    private static String extractPlan(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(json);
            for (String field : PLAN_FIELDS) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
            // 合法 JSON 但没有正文候选字段（例如 plan_enter 的 {}）不算计划正文。
            return null;
        } catch (Exception malformed) {
            // 入参不是合法 JSON 时保留原文：宁可展示未解析的计划，也不要丢掉整份计划。
            return json;
        }
    }

    private static boolean isPlanTool(String toolCallName) {
        return PLAN_ENTER.equals(toolCallName) || PLAN_WRITE.equals(toolCallName) || PLAN_EXIT.equals(toolCallName);
    }

    private static String key(RunId runId, String toolCallId) {
        return runId.value() + ":" + toolCallId;
    }

    /** 运行中断时缓冲可能不会被消费，必须在 Run 终态统一清理，避免常驻内存。 */
    private void forgetRun(RunId runId) {
        String prefix = runId.value() + ":";
        planBuffers.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static final class PlanBuffer {
        private final RunId runId;
        private final String toolName;
        private final StringBuilder raw = new StringBuilder();

        private PlanBuffer(RunId runId, String toolName) {
            this.runId = runId;
            this.toolName = toolName;
        }

        private void append(String delta) {
            if (delta != null) {
                raw.append(delta);
            }
        }

        private String raw() {
            return raw.toString();
        }
    }
}
