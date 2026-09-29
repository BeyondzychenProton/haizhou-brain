package com.haizhuo.brain.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import org.junit.jupiter.api.Test;

class LangfuseTraceIdsTest {

    @Test
    void runTraceIdIsStableAndOtelCompatible() {
        RunId run = new RunId("run-123");

        String first = LangfuseTraceIds.forRun(run);
        String second = LangfuseTraceIds.forRun(run);

        assertEquals(first, second);
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]{32}"));
        assertNotEquals(first, LangfuseTraceIds.forRun(new RunId("run-456")));
    }

    @Test
    void spanIdIsSixteenHexCharacters() {
        String traceId = LangfuseTraceIds.forRun(new RunId("run-123"));
        String spanId = LangfuseTraceIds.spanIdFor(traceId, "tool-1");

        assertEquals(16, spanId.length());
        assertTrue(spanId.matches("[0-9a-f]{16}"));
    }
}
