package com.haizhuo.brain.runtime.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessAgentFactory;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateCache;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.AgentToolSuspendedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.ExternalToolResult;
import com.haizhuo.brain.runtime.api.model.ExternalToolResultExecutionInput;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;

/**
 * 基于真实 HarnessAgent 的端到端运行时测试：使用确定性模型、不触网络。
 * 覆盖规格 §30–§34 的流程：模板复用、首轮提示词、外部工具挂起，
 * 以及通过共享缓存 agent 完成的工具结果恢复。
 */
class AgentScopeRuntimeTest {

    Path workspaceDir;

    private HarnessTemplateCache templateCache;

    @BeforeEach
    void useBuildDirectoryForWorkspaceMaterialization() throws IOException {
        Path testRoot = Path.of("target", "agent-scope-runtime-test");
        Files.createDirectories(testRoot);
        workspaceDir = Files.createTempDirectory(testRoot, "runtime-");
    }

    @Test
    void refusesMissingStateBeforeConstructingOrInvokingModel() {
        var stateStore = new InMemoryAgentStateStore();
        var cache = new HarnessTemplateCache(new FakeFactory(new TextOnlyModel(), workspaceDir));
        var runtime = new AgentScopeRuntime(cache, new RuntimeContextFactory(), new AgentScopeEventTranslator(),
                com.haizhuo.brain.observability.AgentExecutionObserver.noop(), stateStore);
        var request = TestRequests.request(TestRequests.definition("bundle-required", List.of()),
                TestRequests.constraints(Set.of()),
                com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding.direct("session-1", true),
                new com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput("继续"));
        var events = runtime.execute(request).collectList().block();
        var failure = assertInstanceOf(AgentRunFailedEvent.class, events.get(0));
        assertTrue(failure.message().contains("RUNTIME_STATE_MISSING"));
        assertEquals(0, cache.size(), "不能把缺失状态当成新会话");
    }

    @Test
    void roleSlotRecoveryChecksTheRoleSessionRatherThanTheBusinessSession() {
        AtomicReference<String> checkedSession = new AtomicReference<>();
        io.agentscope.core.state.AgentStateStore stateStore = (io.agentscope.core.state.AgentStateStore)
                Proxy.newProxyInstance(io.agentscope.core.state.AgentStateStore.class.getClassLoader(),
                        new Class<?>[]{io.agentscope.core.state.AgentStateStore.class}, (proxy, method, args) -> {
                            if (method.getName().equals("exists")) {
                                checkedSession.set((String) args[1]);
                                return false;
                            }
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                        });
        var cache = new HarnessTemplateCache(new FakeFactory(new TextOnlyModel(), workspaceDir));
        var runtime = new AgentScopeRuntime(cache, new RuntimeContextFactory(), new AgentScopeEventTranslator(),
                com.haizhuo.brain.observability.AgentExecutionObserver.noop(), stateStore);
        var request = TestRequests.request(TestRequests.definition("bundle-role-required", List.of()),
                TestRequests.constraints(Set.of()),
                com.haizhuo.brain.runtime.api.model.RuntimeSessionBinding.roleSlot(
                        "researcher", "role-session-key", "role-workspace-key", true),
                new com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput("继续"));

        var events = runtime.execute(request).collectList().block();

        assertInstanceOf(AgentRunFailedEvent.class, events.get(0));
        assertEquals("role-session-key", checkedSession.get());
        assertEquals(0, cache.size(), "缺少角色槽状态时不能按空状态继续");
    }

    @Test
    void completesInitialPromptThroughCachedTemplate() {
        AgentScopeRuntime runtime = runtimeWith(new TextOnlyModel());
        RuntimeDefinitionSnapshot definition = TestRequests.definition("bundle-complete", List.of());

        List<BrainAgentEvent> events = runtime.execute(TestRequests.promptRequest(definition, "你好"))
                .collectList().block();
        AgentRunCompletedEvent completed = assertInstanceOf(AgentRunCompletedEvent.class, events.get(events.size() - 1));
        assertEquals("deterministic answer", completed.result());

        runtime.execute(TestRequests.promptRequest(definition, "再来一次")).collectList().block();
        assertEquals(1, templateCache.size(), "same frozen definition must reuse the cached template (I-04)");
    }

    @Test
    void suspendsOnSchemaOnlyToolCallAndResumesWithToolResult() {
        AgentScopeRuntime runtime = runtimeWith(new ToolCallingModel());
        RuntimeDefinitionSnapshot definition = TestRequests.definition("bundle-suspend",
                List.of(TestRequests.tool(1L, "meeting.reserve")));
        var constraints = TestRequests.constraints(Set.of("meeting.reserve"));
        var binding = TestRequests.binding("hs-resume", "ws-resume", null);

        List<BrainAgentEvent> suspended = runtime.execute(TestRequests.request(definition, constraints, binding,
                new com.haizhuo.brain.runtime.api.model.UserPromptExecutionInput("订个会议室"))).collectList().block();
        AgentToolSuspendedEvent suspension = suspended.stream()
                .filter(AgentToolSuspendedEvent.class::isInstance)
                .map(AgentToolSuspendedEvent.class::cast)
                .findFirst().orElseThrow();
        assertEquals("tool-use-1", suspension.toolCalls().get(0).toolUseId());
        assertTrue(suspended.stream().noneMatch(AgentRunCompletedEvent.class::isInstance),
                "a suspended stream must not complete the run (§34)");

        AgentExecutionRequest resume = TestRequests.request(definition, constraints, binding,
                new ExternalToolResultExecutionInput(List.of(
                        new ExternalToolResult("tool-use-1", "meeting.reserve", true, "room available"))));
        List<BrainAgentEvent> resumed = runtime.execute(resume).collectList().block();
        AgentRunCompletedEvent completed = resumed.stream()
                .filter(AgentRunCompletedEvent.class::isInstance)
                .map(AgentRunCompletedEvent.class::cast)
                .findFirst().orElseThrow();
        assertEquals("reservation complete", completed.result());
    }

    @Test
    void templateCacheSeparatesDifferentFrozenContent() {
        AgentScopeRuntime runtime = runtimeWith(new TextOnlyModel());

        runtime.execute(TestRequests.promptRequest(TestRequests.definition("bundle-a", List.of()), "1"))
                .collectList().block();
        runtime.execute(TestRequests.promptRequest(TestRequests.definition("bundle-b", List.of()), "2"))
                .collectList().block();

        assertEquals(2, templateCache.size());
    }

    @Test
    void templateBuildFailureBecomesFailedEvent() {
        HarnessAgentFactory brokenFactory = new FakeFactory(new TextOnlyModel(), workspaceDir) {
            @Override protected ChatModelBase createModel(RuntimeDefinitionSnapshot definition) {
                throw new IllegalStateException("no model configured");
            }
        };
        AgentScopeRuntime runtime = new AgentScopeRuntime(new HarnessTemplateCache(brokenFactory),
                new RuntimeContextFactory(), new AgentScopeEventTranslator());

        List<BrainAgentEvent> events = runtime.execute(TestRequests.promptRequest(
                TestRequests.definition("bundle-broken", List.of()), "你好")).collectList().block();

        assertInstanceOf(AgentRunFailedEvent.class, events.get(events.size() - 1));
    }

    @Test
    @Timeout(30)
    void closesSeparateTranslatorForEveryCompletedSubscription() {
        var tracker = new TrackingTranslator();
        var definition = TestRequests.definition("bundle-lifecycle-complete", List.of());
        var runtime = trackedRuntime(new TextOnlyModel(), tracker);
        var request = TestRequests.promptRequest(definition, "synthetic prompt");
        try {
            runtime.execute(request).collectList().block(Duration.ofSeconds(10));
            runtime.execute(request).collectList().block(Duration.ofSeconds(10));
            assertEquals(2, tracker.scopes.size());
            org.junit.jupiter.api.Assertions.assertNotSame(tracker.scopes.get(0), tracker.scopes.get(1));
            assertClosed(tracker, request);
        } finally {
            templateCache.getOrBuild(definition).agent().close();
        }
    }

    @Test
    @Timeout(30)
    void closesTranslatorWhenNativeExecutionFails() {
        var tracker = new TrackingTranslator();
        var definition = TestRequests.definition("bundle-lifecycle-error", List.of());
        var model = new TextOnlyModel() {
            @Override protected Flux<ChatResponse> doStream(List<io.agentscope.core.message.Msg> messages,
                                                             List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new IllegalStateException("synthetic model failure"));
            }
        };
        var runtime = trackedRuntime(model, tracker);
        var request = TestRequests.promptRequest(definition, "synthetic prompt");
        try {
            var events = runtime.execute(request).collectList().block(Duration.ofSeconds(15));
            assertTrue(events.stream().anyMatch(AgentRunFailedEvent.class::isInstance));
            assertEquals(1, tracker.scopes.size());
            assertClosed(tracker, request);
        } finally {
            templateCache.getOrBuild(definition).agent().close();
        }
    }

    @Test
    @Timeout(30)
    void closesTranslatorWhenSubscriberCancelsNativeExecution() throws InterruptedException {
        var tracker = new TrackingTranslator();
        var definition = TestRequests.definition("bundle-lifecycle-cancel", List.of());
        var model = new TextOnlyModel() {
            @Override protected Flux<ChatResponse> doStream(List<io.agentscope.core.message.Msg> messages,
                                                             List<ToolSchema> tools, GenerateOptions options) {
                return Flux.never();
            }
        };
        var runtime = trackedRuntime(model, tracker);
        var request = TestRequests.promptRequest(definition, "synthetic prompt");
        var subscription = runtime.execute(request).subscribe();
        try {
            assertTrue(tracker.opened.await(5, TimeUnit.SECONDS));
            subscription.dispose();
            assertEquals(1, tracker.scopes.size());
            assertTrue(tracker.closed.await(5, TimeUnit.SECONDS), "取消后必须关闭本次执行的事件翻译器");
            assertClosed(tracker, request);
        } finally {
            subscription.dispose();
            templateCache.getOrBuild(definition).agent().close();
        }
    }

    private AgentScopeRuntime trackedRuntime(ChatModelBase model, TrackingTranslator tracker) {
        templateCache = new HarnessTemplateCache(new FakeFactory(model, workspaceDir));
        return new AgentScopeRuntime(templateCache, new RuntimeContextFactory(), tracker);
    }

    private static void assertClosed(TrackingTranslator tracker, AgentExecutionRequest request) {
        tracker.scopes.forEach(scope -> assertTrue(scope.translate(request.runId(),
                new TextBlockDeltaEvent("late-reply", "late-block", "late-text"))
                .collectList().block().isEmpty(), "已结束订阅不再接收迟到事件"));
    }

    private static final class TrackingTranslator extends AgentScopeEventTranslator {
        private final List<AgentScopeEventTranslator> scopes = new CopyOnWriteArrayList<>();
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public AgentScopeEventTranslator forExecution(String nativeSessionId, String attemptId, long fenceToken) {
            var scope = new AgentScopeEventTranslator() {
                @Override
                public synchronized void close() {
                    super.close();
                    closed.countDown();
                }
            };
            scopes.add(scope);
            opened.countDown();
            return scope;
        }
    }

    private AgentScopeRuntime runtimeWith(ChatModelBase model) {
        templateCache = new HarnessTemplateCache(new FakeFactory(model, workspaceDir));
        return new AgentScopeRuntime(templateCache, new RuntimeContextFactory(), new AgentScopeEventTranslator());
    }

    /** 真实工厂装配：确定性模型 + 内存状态存储，不触网络。 */
    private static class FakeFactory extends HarnessAgentFactory {
        private final ChatModelBase model;

        FakeFactory(ChatModelBase model, Path workspaceDir) {
            super(new AgentScopeModelFactory(new AgentScopeRuntimeProperties(
                            "openai", "test-model", "test-key", "http://localhost", true,
                            workspaceDir.toString())),
                    new InMemoryAgentStateStore(), null,
                    new DefinitionWorkspaceMaterializer(workspaceDir),
                    new NoopInbox());
            this.model = model;
        }

        @Override
        protected ChatModelBase createModel(RuntimeDefinitionSnapshot definition) {
            return model;
        }
    }

    private static final class NoopInbox implements RunControlInbox {
        @Override public List<RunGuidanceMessage> consumeGuidance(com.haizhuo.brain.kernel.identity.RunId runId) {
            return List.of();
        }

        @Override public boolean isCancellationRequested(com.haizhuo.brain.kernel.identity.RunId runId) {
            return false;
        }
    }

    private static class TextOnlyModel extends ChatModelBase {
        @Override
        protected Flux<ChatResponse> doStream(List<io.agentscope.core.message.Msg> messages,
                                              List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(ChatResponse.builder().id("text-response")
                    .content(List.of(TextBlock.builder().text("deterministic answer").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "text-only-model";
        }
    }

    private static final class ToolCallingModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        protected Flux<ChatResponse> doStream(List<io.agentscope.core.message.Msg> messages,
                                              List<ToolSchema> tools, GenerateOptions options) {
            if (calls.getAndIncrement() == 0) {
                return Flux.just(ChatResponse.builder().id("tool-call")
                        .content(List.of(new ToolUseBlock("tool-use-1", "meeting.reserve", Map.of("room", "A-101"))))
                        .finishReason("tool_calls").build());
            }
            return Flux.just(ChatResponse.builder().id("final")
                    .content(List.of(TextBlock.builder().text("reservation complete").build()))
                    .finishReason("stop").build());
        }

        @Override
        public String getModelName() {
            return "tool-calling-model";
        }
    }
}
