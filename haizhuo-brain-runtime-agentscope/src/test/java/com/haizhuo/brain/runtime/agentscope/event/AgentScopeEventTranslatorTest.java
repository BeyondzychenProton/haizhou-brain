package com.haizhuo.brain.runtime.agentscope.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.haizhuo.brain.kernel.identity.RunId;
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
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
}
