package com.haizhuo.brain.runtime.agentscope.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.runtime.agentscope.context.HarnessCallContext;
import com.haizhuo.brain.runtime.agentscope.TestRequests;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class CapabilityViewMiddlewareTest {

    private static final Set<String> CATALOG = Set.of("meeting.reserve", "meeting.cancel");

    @Test
    void narrowsExternalCatalogToTheFrozenRunView() {
        List<ToolSchema> tools = List.of(schema("meeting.reserve"), schema("meeting.cancel"));
        AtomicReference<ReasoningInput> observed = new AtomicReference<>();

        new CapabilityViewMiddleware(CATALOG).onReasoning(null, contextWithView(Set.of("meeting.reserve")),
                new ReasoningInput(List.of(), tools, null),
                input -> { observed.set(input); return Flux.empty(); }).blockLast();

        assertEquals(List.of("meeting.reserve"),
                observed.get().tools().stream().map(ToolSchema::getName).toList());
    }

    @Test
    void harnessInternalToolsStayVisibleRegardlessOfTheRunView() {
        List<ToolSchema> tools = List.of(schema("meeting.reserve"), schema("read_file"), schema("write_file"));
        AtomicReference<ReasoningInput> observed = new AtomicReference<>();

        new CapabilityViewMiddleware(CATALOG).onReasoning(null, contextWithView(Set.of()),
                new ReasoningInput(List.of(), tools, null),
                input -> { observed.set(input); return Flux.empty(); }).blockLast();

        assertEquals(List.of("read_file", "write_file"),
                observed.get().tools().stream().map(ToolSchema::getName).toList());
    }

    @Test
    void passesThroughWhenCallContextIsMissing() {
        List<ToolSchema> tools = List.of(schema("meeting.reserve"));
        AtomicReference<ReasoningInput> observed = new AtomicReference<>();

        new CapabilityViewMiddleware(CATALOG).onReasoning(null, RuntimeContext.empty(),
                new ReasoningInput(List.of(), tools, null),
                input -> { observed.set(input); return Flux.empty(); }).blockLast();

        assertEquals(1, observed.get().tools().size());
    }

    private static RuntimeContext contextWithView(Set<String> visible) {
        HarnessCallContext call = new HarnessCallContext(new RunId("run-1"), "attempt-1", 1L, "ws-test",
                TestRequests.constraints(visible), null);
        return RuntimeContext.builder().userId("2").sessionId("hs-test")
                .put(HarnessCallContext.class, call).build();
    }

    private static ToolSchema schema(String name) {
        return ToolSchema.builder().name(name).description("desc " + name)
                .parameters(Map.of("type", "object", "properties", Map.of())).build();
    }
}
