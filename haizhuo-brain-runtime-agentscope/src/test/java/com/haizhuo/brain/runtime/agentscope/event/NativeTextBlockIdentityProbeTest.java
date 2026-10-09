package com.haizhuo.brain.runtime.agentscope.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentTextDeltaEvent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;

/** 探测锁定版本 AgentScope 2.0.3 的原生文本块与根最终结果身份契约。 */
class NativeTextBlockIdentityProbeTest {
    private static final RunId RUN_ID = new RunId("native-message-probe");

    @Test
    @Timeout(30)
    void nativeStreamUsesReplyScopedTextBlockIdentityAndDoesNotGiveFinalAReplyId() {
        ReActAgent agent = ReActAgent.builder()
                .name("native-message-probe")
                .model(new MultiBlockModel())
                .toolkit(new Toolkit())
                .stateStore(new InMemoryAgentStateStore())
                .maxIters(2)
                .build();
        try {
            RuntimeContext context = RuntimeContext.builder().userId("probe-user")
                    .sessionId("native-message-probe-session").build();
            List<AgentEvent> nativeEvents = agent.streamEvents("return two blocks", context)
                    .collectList().block(Duration.ofSeconds(10));
            assertNotNull(nativeEvents);

            List<TextBlockDeltaEvent> deltas = nativeEvents.stream()
                    .filter(TextBlockDeltaEvent.class::isInstance)
                    .map(TextBlockDeltaEvent.class::cast).toList();
            assertEquals(2, deltas.size(), () -> deltas.stream().map(delta ->
                    "reply=" + delta.getReplyId() + ", block=" + delta.getBlockId() + ", delta=" + delta.getDelta())
                    .toList().toString());
            assertEquals(List.of("first block", "second block"),
                    deltas.stream().map(TextBlockDeltaEvent::getDelta).toList());
            assertTrue(deltas.stream().allMatch(delta -> delta.getReplyId() != null && delta.getBlockId() != null));
            assertEquals(1, deltas.stream().map(TextBlockDeltaEvent::getReplyId).distinct().count());
            // AgentScope 2.0.3 可能为同一回复内的文本块使用相同类型键。
            assertEquals(1, deltas.stream().map(TextBlockDeltaEvent::getBlockId).distinct().count(),
                    () -> deltas.stream().map(delta -> "reply=" + delta.getReplyId()
                            + ", block=" + delta.getBlockId() + ", delta=" + delta.getDelta())
                            .toList().toString());

            try (var translator = new AgentScopeEventTranslator()
                    .forExecution(context.getSessionId(), "probe-attempt", 1L)) {
                List<com.haizhuo.brain.runtime.api.event.BrainAgentEvent> translated =
                        Flux.fromIterable(nativeEvents)
                                .concatMap(event -> translator.translate(RUN_ID, event))
                                .collectList().block(Duration.ofSeconds(10));
                assertNotNull(translated);
                List<AgentTextDeltaEvent> textEvents = translated.stream()
                        .filter(AgentTextDeltaEvent.class::isInstance)
                        .map(AgentTextDeltaEvent.class::cast).toList();
                assertEquals(2, textEvents.size());
                for (int i = 0; i < deltas.size(); i++) {
                    var descriptor = textEvents.get(i).descriptor();
                    assertEquals(AgentExecutionRole.ROOT, descriptor.executionRole());
                    assertEquals(deltas.get(i).getReplyId(), descriptor.replyId());
                    assertEquals(deltas.get(i).getBlockId(), descriptor.blockId());
                    assertEquals("probe-attempt", descriptor.attemptId());
                }

                AgentRunCompletedEvent completed = translated.stream()
                        .filter(AgentRunCompletedEvent.class::isInstance)
                        .map(AgentRunCompletedEvent.class::cast).findFirst().orElseThrow();
                assertEquals("first blocksecond block", completed.result());
                assertNull(completed.descriptor().replyId(), "native AgentResultEvent has no replyId");
                assertNull(completed.descriptor().blockId(), "the formal ROOT_FINAL is not a text block");
            }
        } finally {
            agent.close();
        }
    }

    private static final class MultiBlockModel extends ChatModelBase {
        @Override
        protected Flux<ChatResponse> doStream(List<io.agentscope.core.message.Msg> messages,
                                              List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder().id("native-response-1")
                    .content(List.of(TextBlock.builder().text("first block").build(),
                            TextBlock.builder().text("second block").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "native-multi-block-probe";
        }
    }
}
