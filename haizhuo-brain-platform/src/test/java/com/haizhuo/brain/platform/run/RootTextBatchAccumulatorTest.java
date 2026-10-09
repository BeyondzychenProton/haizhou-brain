package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import com.haizhuo.brain.runtime.api.event.AgentExecutionRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RootTextBatchAccumulatorTest {
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");

    @Test
    void coalescesOnlyAdjacentTextWithSameNativeReplyAndBlockAndKeepsCodePointOffsets() {
        List<Captured> captured = new ArrayList<>();
        RootTextBatchAccumulator accumulator = new RootTextBatchAccumulator((descriptor, streamOffset, from, to, text, at) ->
                captured.add(new Captured(descriptor.replyId(), descriptor.blockId(), streamOffset, from, to, text)));

        accumulator.append(descriptor("reply-a", "text"), 1, 0, "你好", NOW);
        accumulator.append(descriptor("reply-a", "text"), 2, 2, " world", NOW);
        accumulator.flush(NOW);
        accumulator.append(descriptor("reply-b", "text"), 3, 0, "again", NOW);
        accumulator.flush(NOW);

        assertEquals(List.of(new Captured("reply-a", "text", 2, 0, 8, "你好 world"),
                new Captured("reply-b", "text", 3, 0, 5, "again")), captured);
    }

    @Test
    void splitsOversizedBatchAtUtf8CodePointBoundaries() {
        List<String> pieces = new ArrayList<>();
        RootTextBatchAccumulator accumulator = new RootTextBatchAccumulator((descriptor, streamOffset, from, to, text, at) ->
                pieces.add(from + ":" + to + ":" + text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        String value = "🙂".repeat(3000);

        accumulator.append(descriptor("reply-a", "text"), 1, 0, value, NOW);
        accumulator.flush(NOW);

        assertEquals(2, pieces.size());
        assertEquals("0:2048:8192", pieces.get(0));
        assertEquals("2048:3000:3808", pieces.get(1));
    }

    private static AgentEventDescriptor descriptor(String replyId, String blockId) {
        return new AgentEventDescriptor("event", NOW.toString(), "TextBlockDelta", "model", replyId,
                blockId, null, null, null, null, AgentExecutionRole.ROOT, "attempt-1", 19L);
    }

    private record Captured(String replyId, String blockId, long streamOffset, long from, long to, String text) { }
}
