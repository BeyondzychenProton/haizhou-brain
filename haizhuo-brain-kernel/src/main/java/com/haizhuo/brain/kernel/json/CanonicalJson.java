package com.haizhuo.brain.kernel.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 用于内容寻址的确定性 JSON 序列化器。键按序排列、输出为 UTF-8 且不含易变字段，
 * 因此相同输入必然得到相同字节。
 */
public final class CanonicalJson {
    private CanonicalJson() { }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    public static String sha256(Object value) {
        return sha256Hex(write(value).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            appendQuoted(out, text);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("Canonical JSON object keys must be strings");
                sorted.put(key, entry.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) out.append(',');
                first = false;
                appendQuoted(out, entry.getKey());
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value.getClass().isArray() && value instanceof Object[] array) {
            append(out, List.of(array));
        } else if (value instanceof Enum<?> enumeration) {
            appendQuoted(out, enumeration.name());
        } else {
            throw new IllegalArgumentException("Unsupported canonical JSON value type: " + value.getClass().getName());
        }
    }

    private static void appendQuoted(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    /** 解析 {@link #write(Object)} 产出的字符串 JSON 数组。 */
    public static List<String> readStringArray(String json) {
        List<String> values = new ArrayList<>();
        if (json == null) return values;
        String trimmed = json.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']')
            throw new IllegalArgumentException("Not a JSON array: " + trimmed);
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char c = trimmed.charAt(i);
            if (escaped) {
                current.append(switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> c;
                });
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (inString) {
                current.append(c);
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0 || trimmed.length() > 2) values.add(current.toString());
        return values;
    }
}
