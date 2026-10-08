package com.haizhuo.brain.runtime.agentscope.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.event.AgentInternalEvent;
import com.haizhuo.brain.runtime.api.event.AgentPlanUpdatedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCancelledEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeExecutionSourceTest {

    private final AgentScopeEventTranslator translator = new AgentScopeEventTranslator();
    private final RunId runId = new RunId("source-run");

    @Test
    void childResultMustNotCompleteRootRun() {
        var child = new AgentResultEvent(Msg.builder()
                .content(List.of(TextBlock.builder().text("child-result").build())).build())
                .withSource("session/researcher");
        assertFalse(Boolean.TRUE.equals(translator.translate(runId, child)
                .any(AgentRunCompletedEvent.class::isInstance).block()));
    }

    @Test
    void childStopAndExternalWaitMustNotControlRootRun() {
        assertFalse(Boolean.TRUE.equals(translator.translate(runId,
                        new RequestStopEvent("child-stop").withSource("session/researcher"))
                .any(AgentRunCancelledEvent.class::isInstance).block()));
        assertFalse(Boolean.TRUE.equals(translator.translate(runId,
                        new RequireExternalExecutionEvent("child-reply",
                                List.of(new ToolUseBlock("child-tool", "private-tool", Map.of())))
                                .withSource("session/researcher"))
                .any(AgentToolSuspendedEvent.class::isInstance).block()));
    }

    @Test
    void childDeltaMustNotJoinRootAnswer() {
        assertFalse(Boolean.TRUE.equals(translator.translate(runId,
                        new TextBlockDeltaEvent("child-reply", "child-block", "private-child-text")
                                .withSource("session/researcher"))
                .any(AgentTextDeltaEvent.class::isInstance).block()));
    }

    @Test
    void childPlanMustNotUpdateRootPlan() {
        translator.translate(runId, new ToolCallDeltaEvent("child-reply", "same-tool", "plan_write",
                "{\"plan\":\"child-plan\"}").withSource("session/researcher")).blockLast();
        assertFalse(Boolean.TRUE.equals(translator.translate(runId,
                        new ToolResultEndEvent("child-reply", "same-tool", "plan_write",
                                ToolResultState.SUCCESS).withSource("session/researcher"))
                .any(AgentPlanUpdatedEvent.class::isInstance).block()));
    }

    @Test
    void preservesNativeDescriptorAndTrustedAttemptWithoutCopyingArbitraryMetadata() {
        try (var scoped = translator.forExecution("session", "attempt-7", 7L)) {
            var nativeEvent = new TextBlockDeltaEvent("native-event", "2026-10-07T14:00:00Z",
                    "child-reply", "child-block", "child-text").withSource("session/researcher")
                    .withMetadataEntry(io.agentscope.core.event.AgentEvent.METADATA_TASK_ID, "native-task")
                    .withMetadataEntry(io.agentscope.core.event.AgentEvent.METADATA_PARENT_SESSION_ID, "session")
                    .withMetadataEntry("private-tool-arguments", Map.of("secret", "not-for-projection"));
            var event = assertInstanceOf(AgentInternalEvent.class, scoped.translate(runId, nativeEvent).blockFirst());
            var descriptor = event.descriptor();
            assertEquals(AgentExecutionRole.CHILD, descriptor.executionRole());
            assertEquals(nativeEvent.getId(), descriptor.nativeEventId());
            assertEquals(nativeEvent.getCreatedAt(), descriptor.nativeCreatedAt());
            assertEquals("session/researcher", descriptor.nativeSource());
            assertEquals("child-reply", descriptor.replyId());
            assertEquals("child-block", descriptor.blockId());
            assertEquals("native-task", descriptor.nativeTaskId());
            assertEquals("session", descriptor.nativeParentSessionId());
            assertEquals("attempt-7", descriptor.attemptId());
            assertEquals(7L, descriptor.fenceToken());
        }
    }

    @Test
    void unrecognizedSourceNeverFallsBackToRoot() {
        try (var scoped = translator.forExecution("session", "attempt-1", 1L)) {
            for (String source : List.of("another-session/researcher", "", "session/")) {
                var nativeEvent = new AgentResultEvent(Msg.builder()
                        .content(List.of(TextBlock.builder().text("untrusted-result").build())).build())
                        .withSource(source);
                var event = assertInstanceOf(AgentInternalEvent.class,
                        scoped.translate(runId, nativeEvent).blockFirst());
                assertEquals(AgentExecutionRole.UNKNOWN, event.descriptor().executionRole());
            }
        }
    }

    @Test
    void differentAttemptsAndRepliesDoNotSharePlanArguments() {
        try (var first = translator.forExecution("session", "attempt-1", 1L);
             var second = translator.forExecution("session", "attempt-2", 2L)) {
            first.translate(runId, new ToolCallDeltaEvent("reply", "same-tool", "plan_write",
                    "{\"plan\":\"attempt-one\"}")).blockLast();
            second.translate(runId, new ToolCallDeltaEvent("reply", "same-tool", "plan_write",
                    "{\"plan\":\"attempt-two\"}")).blockLast();
            var secondPlan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                    second.translate(runId, new ToolResultEndEvent("reply", "same-tool", "plan_write",
                            ToolResultState.SUCCESS)).blockFirst());
            assertEquals("attempt-two", secondPlan.plan());
            assertEquals("attempt-2", secondPlan.descriptor().attemptId());

            first.translate(runId, new ToolCallDeltaEvent("another-reply", "same-tool", "plan_write",
                    "{\"plan\":\"another-reply-plan\"}")).blockLast();
            var firstPlan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                    first.translate(runId, new ToolResultEndEvent("reply", "same-tool", "plan_write",
                            ToolResultState.SUCCESS)).blockFirst());
            assertEquals("attempt-one", firstPlan.plan());
            var anotherPlan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                    first.translate(runId, new ToolResultEndEvent("another-reply", "same-tool", "plan_write",
                            ToolResultState.SUCCESS)).blockFirst());
            assertEquals("another-reply-plan", anotherPlan.plan());
        }
    }

    @Test
    void childResultDoesNotDiscardPendingRootPlan() {
        try (var scoped = translator.forExecution("session", "attempt-1", 1L)) {
            scoped.translate(runId, new ToolCallDeltaEvent("root-reply", "plan-tool", "plan_write",
                    "{\"plan\":\"root-plan\"}")).blockLast();
            scoped.translate(runId, new AgentResultEvent(Msg.builder()
                    .content(List.of(TextBlock.builder().text("child-result").build())).build())
                    .withSource("session/researcher")).blockLast();
            var plan = assertInstanceOf(AgentPlanUpdatedEvent.class,
                    scoped.translate(runId, new ToolResultEndEvent("root-reply", "plan-tool", "plan_write",
                            ToolResultState.SUCCESS)).blockFirst());
            assertEquals("root-plan", plan.plan());
        }
    }

    @Test
    void knownNonPlanArgumentsAreNotBufferedAsPlans() {
        try (var scoped = translator.forExecution("session", "attempt-1", 1L)) {
            assertTrue(scoped.translate(runId, new ToolCallDeltaEvent("reply", "private-call", "private-tool",
                    "x".repeat(256 * 1024))).collectList().block().isEmpty());
            assertTrue(scoped.translate(runId, new ToolResultEndEvent("reply", "private-call", "private-tool",
                    ToolResultState.SUCCESS)).collectList().block().isEmpty());
        }
    }
}
