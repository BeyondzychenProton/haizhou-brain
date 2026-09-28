package com.haizhuo.brain.runtime.agentscope.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.event.AgentPlanUpdatedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeEventTranslatorTest {

    private static final RunId RUN_ID = new RunId("run-1");
    private final AgentScopeEventTranslator translator = new AgentScopeEventTranslator();

    @Test
    void translatesExternalExecutionSuspensionWithToolCallIdentity() {
        RequireExternalExecutionEvent event = new RequireExternalExecutionEvent("reply-1",
                List.of(new ToolUseBlock("tool-use-1", "meeting.reserve", Map.of("room", "A-101"))));

        BrainAgentEvent translated = translator.translate(RUN_ID, event).blockFirst();

        AgentToolSuspendedEvent suspended = assertInstanceOf(AgentToolSuspendedEvent.class, translated);
        assertEquals(RUN_ID, suspended.runId());
        assertEquals("reply-1", suspended.replyId());
        assertEquals(1, suspended.toolCalls().size());
        assertEquals("tool-use-1", suspended.toolCalls().get(0).toolUseId());
        assertEquals("meeting.reserve", suspended.toolCalls().get(0).toolName());
        assertEquals(Map.of("room", "A-101"), suspended.toolCalls().get(0).arguments());
    }

    @Test
    void translatesTextDeltasAndFinalResult() {
        TextBlockDeltaEvent delta = new TextBlockDeltaEvent("reply-1", "block-1", "你好");

        AgentTextDeltaEvent translatedDelta = assertInstanceOf(AgentTextDeltaEvent.class,
                translator.translate(RUN_ID, delta).blockFirst());
        assertEquals(delta.getDelta(), translatedDelta.text());

        Msg answer = Msg.builder().role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("最终答复").build()).build();
        AgentRunCompletedEvent completed = assertInstanceOf(AgentRunCompletedEvent.class,
                translator.translate(RUN_ID, new AgentResultEvent(answer)).blockFirst());
        assertEquals("最终答复", completed.result());
    }

    @Test
    void translatesRequestStopIntoCancellation() {
        AgentRunCancelledEvent cancelled = assertInstanceOf(AgentRunCancelledEvent.class,
                translator.translate(RUN_ID, new RequestStopEvent("stop")).blockFirst());

        assertEquals(RUN_ID, cancelled.runId());
    }

    @Test
    void dropsEventsWithoutPlatformCounterpart() {
        ModelCallStartEvent ignored = new ModelCallStartEvent("reply-1");

        assertEquals(0, translator.translate(RUN_ID, ignored).count().block());
    }

    @Test
    void planToolArgumentsAreBufferedIntoASnapshotOnlyWhenTheCallEnds() {
        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-1", "plan_write"));
        assertEquals(0, translator.translate(RUN_ID,
                new ToolCallDeltaEvent("reply-1", "call-1", "plan_write", "{\"plan\":\"第一步：")).count().block(),
                "入参还在流式到达，此时不得产出半截计划");
        assertEquals(0, translator.translate(RUN_ID,
                new ToolCallDeltaEvent("reply-1", "call-1", "plan_write", "确认需求\"}")).count().block());

        AgentPlanUpdatedEvent plan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-1", "plan_write")).blockFirst());

        assertEquals(AgentPlanUpdatedEvent.Phase.WRITE, plan.phase());
        assertEquals("第一步：确认需求", plan.plan());
    }

    @Test
    void planPhaseToolsProducePhaseOnlySnapshots() {
        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-enter", "plan_enter"));
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-enter", "plan_enter", "{}"));
        AgentPlanUpdatedEvent enter = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-enter", "plan_enter"))
                        .blockFirst());

        assertEquals(AgentPlanUpdatedEvent.Phase.ENTER, enter.phase());
        assertNull(enter.plan(), "enter 的空入参不应被当成计划正文");

        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-exit", "plan_exit"));
        AgentPlanUpdatedEvent exit = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-exit", "plan_exit")).blockFirst());

        assertEquals(AgentPlanUpdatedEvent.Phase.EXIT, exit.phase());
        assertNull(exit.plan());
    }

    @Test
    void nonPlanToolsProduceNoSnapshot() {
        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-9", "meeting.reserve"));
        translator.translate(RUN_ID,
                new ToolCallDeltaEvent("reply-1", "call-9", "meeting.reserve", "{\"room\":\"A-101\"}"));

        assertEquals(0, translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-9", "meeting.reserve"))
                .count().block());
    }

    @Test
    void planBodyFallsBackAcrossFieldNamesAndKeepsMalformedInput() {
        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-content", "plan_write"));
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-content", "plan_write",
                "{\"content\":\"用 content 字段承载计划\"}"));
        AgentPlanUpdatedEvent byContent = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-content", "plan_write"))
                        .blockFirst());
        assertEquals("用 content 字段承载计划", byContent.plan());

        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-text", "plan_write"));
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-text", "plan_write", "纯文本计划"));
        AgentPlanUpdatedEvent malformed = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-text", "plan_write")).blockFirst());
        assertEquals("纯文本计划", malformed.plan(), "入参不是 JSON 时保留原文，不能丢掉整份计划");
    }

    @Test
    void planBuffersAreIsolatedPerRunAndDroppedWhenTheRunEnds() {
        translator.translate(RUN_ID, new ToolCallStartEvent("reply-1", "call-1", "plan_write"));
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-1", "plan_write", "{\"plan\":\"本轮\"}"));

        AgentPlanUpdatedEvent otherRun = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(new RunId("run-2"),
                        new ToolCallEndEvent("reply-1", "call-1", "plan_write")).blockFirst());
        assertNull(otherRun.plan(), "另一个 Run 绝不能读到本 Run 缓存的计划正文");

        Msg answer = Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("结束").build()).build();
        translator.translate(RUN_ID, new AgentResultEvent(answer));

        AgentPlanUpdatedEvent afterRunEnd = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolCallEndEvent("reply-1", "call-1", "plan_write")).blockFirst());
        assertNull(afterRunEnd.plan(), "Run 终止后残留缓冲必须清理，不得再把旧正文当成新计划");
    }

    @Test
    void realEventStreamShapeOnlyNeedsDeltaAndToolResultEnd() {
        // 真机验证：Harness 事件流不含 TOOL_CALL_START/END，实际可用的收尾信号是 TOOL_RESULT_END。
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-1", "plan_write",
                "{\"plan\":\"真机计划\"}"));

        AgentPlanUpdatedEvent plan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolResultEndEvent("reply-1", "call-1", "plan_write",
                        ToolResultState.SUCCESS)).blockFirst());

        assertEquals(AgentPlanUpdatedEvent.Phase.WRITE, plan.phase());
        assertEquals("真机计划", plan.plan());
    }

    @Test
    void deltaWithoutToolNameStillResolvesToPlanAtResultEnd() {
        // 入参增量不一定带工具名，只靠结果结束事件也必须能识别计划工具。
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-1", null, "{\"plan\":\"无名字计划\"}"));

        AgentPlanUpdatedEvent plan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                translator.translate(RUN_ID, new ToolResultEndEvent("reply-1", "call-1", "plan_write",
                        ToolResultState.SUCCESS)).blockFirst());

        assertEquals("无名字计划", plan.plan());
    }

    @Test
    void nonPlanToolArgumentsAreDroppedAtResultEnd() {
        translator.translate(RUN_ID, new ToolCallDeltaEvent("reply-1", "call-9", "meeting.reserve",
                "{\"room\":\"A-101\"}"));

        assertEquals(0, translator.translate(RUN_ID, new ToolResultEndEvent("reply-1", "call-9", "meeting.reserve",
                        ToolResultState.SUCCESS)).count().block(),
                "非计划工具的内部参数不得落成计划事件");
    }
}
