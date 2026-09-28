package com.haizhuo.brain.platform.mcp;

import java.net.URI;
import java.util.Set;

/** 显式主机白名单防止管理员填写的 URL 成为 SSRF 入口。 */
public class McpEndpointPolicy {
    private final Set<String> allowedHosts;
    private final boolean allowLoopbackHttp;

    public McpEndpointPolicy(Set<String> allowedHosts, boolean allowLoopbackHttp) {
        this.allowedHosts = Set.copyOf(allowedHosts);
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    public void validate(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Invalid MCP endpoint");
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        if (allowLoopbackHttp && (host.equals("127.0.0.1") || host.equals("localhost"))
                && "http".equalsIgnoreCase(uri.getScheme())) return;
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHosts.contains(host))
            throw new IllegalArgumentException("MCP endpoint is not in the trusted host allowlist");
    }
}
