package com.haizhuo.brain.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LangfusePropertiesTest {

    @Test
    void defaultsKeepContentCaptureOffAndDeriveEndpoints() {
        LangfuseProperties properties = new LangfuseProperties(
                true, "http://langfuse.local/", null, "pk", "sk", null, null,
                null, null, null, null, null);

        assertTrue(properties.isEnabled());
        assertTrue(properties.configured());
        assertFalse(properties.captureContentEnabled());
        assertEquals("haizhuo-brain", properties.serviceName());
        assertEquals("http://langfuse.local/api/public/otel/v1/traces", properties.otlpEndpoint());
        assertEquals("http://langfuse.local/api/public/scores", properties.scoreEndpoint());
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
                        "pk:sk".getBytes(StandardCharsets.UTF_8)), properties.basicAuthorization());
    }

    @Test
    void disabledOrIncompleteCredentialsDoNotCreateScoreAuthorization() {
        LangfuseProperties disabled = new LangfuseProperties(
                false, null, null, "pk", "sk", null, null, null, null, null, null, null);
        LangfuseProperties incomplete = new LangfuseProperties(
                true, null, null, "pk", "", null, null, null, null, null, null, null);

        assertFalse(disabled.configured());
        assertNull(disabled.basicAuthorization());
        assertFalse(incomplete.configured());
        assertNull(incomplete.basicAuthorization());
    }
}
