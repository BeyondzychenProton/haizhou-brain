package com.haizhuo.brain.platform.mcp;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 每次 Run 预检使用独立的短生命周期实例；调用方不得共享其缓存。 */
public class DefaultMcpToolVisibility implements McpToolVisibility {
    private final McpCatalogRepository catalog;
    private final McpRemoteClient remote;
    private final McpUserTokenProvider tokens;
    private final Map<Long, Map<String, McpToolDescriptor>> listed = new HashMap<>();

    public DefaultMcpToolVisibility(McpCatalogRepository catalog, McpRemoteClient remote,
                                    McpUserTokenProvider tokens) {
        this.catalog = catalog;
        this.remote = remote;
        this.tokens = tokens;
    }

    @Override
    public boolean visible(UserId user, CapabilityCatalogEntry entry) {
        McpToolSource source = catalog.findSource(entry.capabilityRevisionId())
                .orElseThrow(() -> new IllegalStateException("Approved MCP source is missing"));
        McpConnection connection = catalog.findConnection(source.connectionId())
                .orElseThrow(() -> new IllegalStateException("MCP connection is missing"));
        if (!connection.enabled() || connection.revision() != source.connectionRevision()) return false;
        Map<String, McpToolDescriptor> available = listed.computeIfAbsent(connection.id(), ignored ->
                remote.listTools(connection, tokens.tokenFor(user, connection)).stream()
                        .collect(Collectors.toUnmodifiableMap(McpToolDescriptor::name, tool -> tool)));
        McpToolDescriptor observed = available.get(source.remoteName());
        return observed != null && observed.inputSchema().equals(entry.inputSchema())
                && observed.outputSchema().equals(source.outputSchema())
                && (!source.readOnly() || observed.readOnlyHint());
    }
}
