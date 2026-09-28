package com.haizhuo.brain.platform.mcp;

import java.util.Objects;

/** 经审核的 Streamable HTTP 端点，不保存用户凭证。 */
public record McpConnection(long id, String code, String displayName, String endpoint,
                            long revision, boolean enabled) {
    public McpConnection {
        if (id < 0 || revision < 1) throw new IllegalArgumentException("Invalid MCP connection identity");
        Objects.requireNonNull(code);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(endpoint);
    }
}
