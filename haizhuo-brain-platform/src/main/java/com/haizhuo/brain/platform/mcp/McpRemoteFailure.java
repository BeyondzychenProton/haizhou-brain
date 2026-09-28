package com.haizhuo.brain.platform.mcp;

/** 对远端失败进行安全分类；刻意不保留提供方原始报文或 Bearer Token。 */
public class McpRemoteFailure extends RuntimeException {
    public enum Kind { AUTHENTICATION, FORBIDDEN, UNAVAILABLE, RESULT_UNKNOWN }
    private final Kind kind;

    public McpRemoteFailure(Kind kind) {
        super("MCP " + kind.name());
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
