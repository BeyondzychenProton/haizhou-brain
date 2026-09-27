package com.haizhuo.brain.security.identity;

/** First-release policy: China mainland mobiles are stored as a canonical +86 E.164-like value. */
public final class MobileNormalizer {
    private MobileNormalizer() {
    }

    public static String normalizeChinaMainland(String raw) {
        if (raw == null) throw new IllegalArgumentException("手机号格式不正确");
        String compact = raw.trim().replaceAll("[\\s()\\-]", "");
        if (compact.matches("1[3-9]\\d{9}")) return "+86" + compact;
        if (compact.matches("\\+861[3-9]\\d{9}")) return compact;
        throw new IllegalArgumentException("手机号格式不正确");
    }
}
