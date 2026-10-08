package com.haizhuo.brain.runtime.agentscope.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.event.AgentInternalEvent;
import com.haizhuo.brain.runtime.api.event.AgentPlanUpdatedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.event.PendingExternalToolCall;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 把 AgentScope 流事件翻译成平台运行时事件模型（规格 §33/§34）。
 * 根事件只驱动业务 Run；子/未知来源的可展示进度以内部事件返回。
 * 思考增量、原始工具参数和没有平台对应物的模型标记不进入平台事件。
 *
 * <p>例外是计划工具：它的计划正文只存在于工具入参的流式增量里，
 * 因此仅根 Plan 调用缓冲到结束再翻译成 {@link AgentPlanUpdatedEvent}（P2 plan.snapshot）。
 * 缓冲随执行订阅关闭；持久化仍由平台负责。</p>
 */
public class AgentScopeEventTranslator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AgentScopeEventTranslator.class);

    /** 计划工具名取自 Harness PlanModeTools（javap 核对取值：plan_enter / plan_write / plan_exit）。 */
    static final String PLAN_ENTER = "plan_enter";
    static final String PLAN_WRITE = "plan_write";
    static final String PLAN_EXIT = "plan_exit";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 计划正文的候选字段；不同模型给计划工具的入参键名不一致，按序取第一个非空文本。 */
    private static final List<String> PLAN_FIELDS =
            List.of("plan", "content", "plan_content", "planContent", "markdown", "text");

    private static final int MAX_PENDING_PLAN_CALLS = 64;
    private static final int MAX_PLAN_ARGUMENT_CHARS = 128 * 1024;

    private final String nativeSessionId;
    private final String attemptId;
    private final Long fenceToken;
    private final Map<PlanKey, PlanBuffer> planBuffers = new HashMap<>();
    private boolean closed;

    /** 保留旧调用方式；执行流应使用 forExecution 创建独立翻译器。 */
    public AgentScopeEventTranslator() {
        this(null, null, null);
    }

    private AgentScopeEventTranslator(String nativeSessionId, String attemptId, Long fenceToken) {
        this.nativeSessionId = nativeSessionId;
        this.attemptId = attemptId;
        this.fenceToken = fenceToken;
    }

    public AgentScopeEventTranslator forExecution(String nativeSessionId, String attemptId, long fenceToken) {
        return new AgentScopeEventTranslator(Objects.requireNonNull(nativeSessionId),
                Objects.requireNonNull(attemptId), fenceToken);
    }

    public synchronized Flux<BrainAgentEvent> translate(RunId runId, AgentEvent event) {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(event);
        if (closed) {
            return Flux.empty();
        }
        AgentEventDescriptor descriptor = describe(event);
        if (descriptor.executionRole() != AgentExecutionRole.ROOT) {
            return translateInternal(runId, event, descriptor);
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            PlanKey key = new PlanKey(runId, delta.getReplyId(), delta.getToolCallId());
            String name = delta.getToolCallName();
            // 已知非计划工具不保留原始参数。无名增量暂存到结束事件确定工具名，且限制容量。
            if (name != null && !name.isBlank() && !isPlanTool(name)) {
                planBuffers.remove(key);
                return Flux.empty();
            }
            if (!planBuffers.containsKey(key) && planBuffers.size() >= MAX_PENDING_PLAN_CALLS) {
                throw new IllegalStateException("Too many pending plan argument streams");
            }
            PlanBuffer buffer = planBuffers.computeIfAbsent(key, ignored -> new PlanBuffer(name));
            if (isPlanTool(name)) {
                buffer.toolName = name;
            }
            buffer.append(delta.getDelta());
            return Flux.empty();
        }
        if (event instanceof ToolCallEndEvent ended) {
            return Flux.fromIterable(flushPlan(runId, ended.getReplyId(), ended.getToolCallId(),
                    ended.getToolCallName(), descriptor));
        }
        if (event instanceof ToolResultEndEvent ended) {
            return Flux.fromIterable(flushPlan(runId, ended.getReplyId(), ended.getToolCallId(),
                    ended.getToolCallName(), descriptor));
        }
        if (event instanceof RequireExternalExecutionEvent suspension) {
            List<PendingExternalToolCall> calls = suspension.getToolCalls().stream()
                    .map(tool -> new PendingExternalToolCall(tool.getId(), tool.getName(), tool.getInput()))
                    .toList();
            if (calls.isEmpty()) {
                return Flux.empty();
            }
            return Flux.just(new AgentToolSuspendedEvent(runId, suspension.getReplyId(), calls, descriptor));
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            return Flux.just(new AgentTextDeltaEvent(runId, delta.getDelta(), descriptor));
        }
        if (event instanceof AgentResultEvent result) {
            forgetRun(runId);
            Msg message = result.getResult();
            String text = message == null ? null : message.getTextContent();
            return Flux.just(new AgentRunCompletedEvent(runId, text == null ? "" : text, descriptor));
        }
        if (event instanceof RequestStopEvent) {
            forgetRun(runId);
            return Flux.just(new AgentRunCancelledEvent(runId, "运行已在安全检查点取消", descriptor));
        }
        // 诊断用：确认事件流实际包含哪些类型（例如计划工具调用是否以事件形式浮现）。
        if (LOG.isDebugEnabled()) {
            LOG.debug("Unmapped AgentScope event: type={} class={}", event.getType(),
                    event.getClass().getSimpleName());
        }
        return Flux.empty();
    }

    /**
     * 工具结束时结算缓冲：只有计划工具才产出计划事件，其余工具的入参直接丢弃。
     * 工具名优先取入参增量携带的名字，其次取结束事件的名字；两者都没有时无法识别，
     * 这与真实事件流一致（Harness 只发增量，名字可能只出现在其中一侧）。
     * 即使没有入参增量，只要结束事件表明是计划工具，也产出只带阶段的事件。
     */
    private List<BrainAgentEvent> flushPlan(RunId runId, String replyId, String toolCallId,
                                           String toolCallName, AgentEventDescriptor descriptor) {
        PlanBuffer buffer = planBuffers.remove(new PlanKey(runId, replyId, toolCallId));
        String name = buffer != null && isPlanTool(buffer.toolName) ? buffer.toolName : toolCallName;
        if (!isPlanTool(name)) {
            return List.of();
        }
        return List.of(new AgentPlanUpdatedEvent(runId, phaseOf(name),
                buffer == null ? null : extractPlan(buffer.raw()), descriptor));
    }

    private AgentEventDescriptor describe(AgentEvent event) {
        String source = event.getSource();
        AgentExecutionRole role = AgentExecutionRole.UNKNOWN;
        // 2.0.3 真实根事件没有 source；本地子转发使用 parentSession/agentType。
        if (source == null) {
            role = AgentExecutionRole.ROOT;
        } else if (nativeSessionId != null && source.startsWith(nativeSessionId + "/")
                && !source.substring(nativeSessionId.length() + 1).isBlank()) {
            role = AgentExecutionRole.CHILD;
        }
        String replyId = null;
        String blockId = null;
        String toolUseId = null;
        String eventSessionId = null;
        if (event instanceof TextBlockDeltaEvent delta) {
            replyId = delta.getReplyId();
            blockId = delta.getBlockId();
        } else if (event instanceof ToolCallDeltaEvent delta) {
            replyId = delta.getReplyId();
            toolUseId = delta.getToolCallId();
        } else if (event instanceof ToolCallEndEvent end) {
            replyId = end.getReplyId();
            toolUseId = end.getToolCallId();
        } else if (event instanceof ToolResultEndEvent end) {
            replyId = end.getReplyId();
            toolUseId = end.getToolCallId();
        } else if (event instanceof RequireExternalExecutionEvent suspension) {
            replyId = suspension.getReplyId();
        } else if (event instanceof AgentStartEvent start) {
            replyId = start.getReplyId();
            eventSessionId = start.getSessionId();
        } else if (event instanceof AgentEndEvent end) {
            replyId = end.getReplyId();
        }
        return new AgentEventDescriptor(event.getId(), event.getCreatedAt(), event.getType().name(),
                source, replyId, blockId, toolUseId, eventSessionId,
                metadataString(event, AgentEvent.METADATA_TASK_ID),
                metadataString(event, AgentEvent.METADATA_PARENT_SESSION_ID), role, attemptId, fenceToken);
    }

    private static String metadataString(AgentEvent event, String key) {
        Object value = event.getMetadata() == null ? null : event.getMetadata().get(key);
        return value instanceof String text ? text : null;
    }

    private static Flux<BrainAgentEvent> translateInternal(RunId runId, AgentEvent event,
                                                          AgentEventDescriptor descriptor) {
        AgentInternalEvent.Kind kind;
        String text = null;
        if (event instanceof TextBlockDeltaEvent delta) {
            kind = AgentInternalEvent.Kind.TEXT_DELTA;
            text = delta.getDelta();
        } else if (event instanceof AgentResultEvent result) {
            kind = AgentInternalEvent.Kind.RESULT;
            text = result.getResult() == null ? "" : result.getResult().getTextContent();
        } else if (event instanceof RequestStopEvent) {
            kind = AgentInternalEvent.Kind.STOPPED;
        } else if (event instanceof RequireExternalExecutionEvent) {
            kind = AgentInternalEvent.Kind.EXTERNAL_TOOL_WAIT;
        } else if (event instanceof AgentStartEvent) {
            kind = AgentInternalEvent.Kind.STARTED;
        } else if (event instanceof AgentEndEvent) {
            kind = AgentInternalEvent.Kind.ENDED;
        } else if (event instanceof ToolCallDeltaEvent || event instanceof ToolCallEndEvent
                || event instanceof ToolResultEndEvent) {
            kind = AgentInternalEvent.Kind.TOOL_PROGRESS;
        } else {
            // 包括思考增量：不携带到平台事件端口。
            return Flux.empty();
        }
        return Flux.just(new AgentInternalEvent(runId, descriptor, kind, text));
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

    /** 运行中断时缓冲可能不会被消费，必须在 Run 终态统一清理，避免常驻内存。 */
    private void forgetRun(RunId runId) {
        planBuffers.keySet().removeIf(key -> key.runId().equals(runId));
    }

    /** 执行订阅的完成、错误和取消均调用，关闭后拒绝迟到事件。 */
    @Override
    public synchronized void close() {
        closed = true;
        planBuffers.clear();
    }

    private record PlanKey(RunId runId, String replyId, String toolCallId) { }

    private static final class PlanBuffer {
        private String toolName;
        private final StringBuilder raw = new StringBuilder();

        private PlanBuffer(String toolName) {
            this.toolName = toolName;
        }

        private void append(String delta) {
            if (delta != null) {
                if (delta.length() > MAX_PLAN_ARGUMENT_CHARS - raw.length()) {
                    throw new IllegalStateException("Plan argument stream exceeds limit");
                }
                raw.append(delta);
            }
        }

        private String raw() {
            return raw.toString();
        }
    }
}
