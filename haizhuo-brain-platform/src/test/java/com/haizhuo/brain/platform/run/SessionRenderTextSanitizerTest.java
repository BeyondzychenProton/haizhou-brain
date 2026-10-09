package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SessionRenderTextSanitizerTest {
    @Test
    void redactsAuthorizationBearerKeyValuesAndInternalPaths() {
        String input = "Authorization: Bearer abc.def-123\n"
                + "proxy-authorization: Basic private-value\n"
                + "api_key=key-value clientSecret: \"client-secret\" authorization=auth-value Bearer free.token-value "
                + "C:\\Users\\Alice Smith\\private\\answer.md /home/alice/private/result.md";

        String safe = SessionRenderTextSanitizer.sanitize(input);

        assertEquals("Authorization: [redacted]\n"
                + "proxy-authorization: [redacted]\n"
                + "api_key=[redacted] clientSecret: [redacted] authorization=[redacted] Bearer [redacted] "
                + "[internal path omitted] [internal path omitted]", safe);
        assertEquals(safe, SessionRenderTextSanitizer.sanitize(safe));
        assertTrue(!safe.contains("abc.def-123"));
        assertTrue(!safe.contains("private-value"));
        assertTrue(!safe.contains("key-value"));
        assertTrue(!safe.contains("auth-value"));
        assertTrue(!safe.contains("Alice Smith"));
        assertTrue(!safe.contains("/home/alice"));
    }

    @Test
    void leavesOrdinaryTextAndNullSafe() {
        assertEquals("ordinary explanation", SessionRenderTextSanitizer.sanitize("ordinary explanation"));
        assertEquals("", SessionRenderTextSanitizer.sanitize(null));
    }
}
