package com.haizhuo.brain.platform.run;

import java.util.regex.Pattern;

/** 仅对展示内容脱敏；已持久化 ROOT_FINAL 正文及其摘要保持不变。 */
public final class SessionRenderTextSanitizer {
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)(\\b(?:proxy-)?authorization\\s*:\\s*)(?:bearer\\s+)?[^\\r\\n]+" );
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(\\\"?\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|auth[_-]?token|client[_-]?secret|authorization|password|passwd|secret|token)\\b\\\"?\\s*[:=]\\s*)(?:\\[[^\\]]*\\]|\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;\\]}]+)" );
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bbearer\\s+[a-z0-9._~+/-]+=*" );
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?<![\\w])(?:[A-Z]:\\\\)(?:[^\\\\/:*?\\\"<>|\\r\\n]+\\\\)+[^\\\\/:*?\\\"<>|\\s,;]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![\\w])/(?:home|Users|var|etc|workspace|app|opt|srv|tmp|mnt)/[^\\s,;]+",
            Pattern.CASE_INSENSITIVE);

    private SessionRenderTextSanitizer() { }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) return "";
        String safe = AUTHORIZATION_HEADER.matcher(text).replaceAll("$1[redacted]");
        safe = CREDENTIAL.matcher(safe).replaceAll("$1[redacted]");
        safe = BEARER_TOKEN.matcher(safe).replaceAll("Bearer [redacted]");
        safe = WINDOWS_PATH.matcher(safe).replaceAll("[internal path omitted]");
        return UNIX_PATH.matcher(safe).replaceAll("[internal path omitted]");
    }
}
