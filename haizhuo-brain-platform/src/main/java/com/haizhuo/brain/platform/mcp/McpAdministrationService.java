package com.haizhuo.brain.platform.mcp;

import com.haizhuo.brain.kernel.identity.UserId;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** 远端工具发现与数字员工能力目录之间的人工审核关口。 */
public class McpAdministrationService {
    private final McpCatalogRepository catalog;
    private final McpRemoteClient remote;
    private final McpUserTokenProvider tokens;
    private final McpEndpointPolicy endpoints;

    public McpAdministrationService(McpCatalogRepository catalog, McpRemoteClient remote,
                                    McpUserTokenProvider tokens, McpEndpointPolicy endpoints) {
        this.catalog = catalog;
        this.remote = remote;
        this.tokens = tokens;
        this.endpoints = endpoints;
    }

    public List<McpConnection> connections() { return catalog.listConnections(); }

    public McpConnection create(String code, String name, String endpoint, long actor, String reason) {
        if (code == null || !code.matches("[a-z][a-z0-9-]{2,63}"))
            throw new IllegalArgumentException("Invalid connection code");
        if (name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Invalid connection name");
        requireReason(reason);
        endpoints.validate(URI.create(endpoint));
        return catalog.createConnection(code, name.trim(), endpoint, actor, reason);
    }

    public void setEnabled(long id, boolean enabled, long actor, String reason) {
        requireReason(reason);
        catalog.setConnectionEnabled(id, enabled, actor, reason);
    }

    public Discovery discover(long id, UserId actor) {
        McpConnection connection = active(id);
        List<McpToolDescriptor> tools = remote.listTools(connection, tokens.tokenFor(actor, connection));
        long snapshot = catalog.saveDiscovery(id, connection.revision(), actor.value(), tools);
        return new Discovery(snapshot, tools);
    }

    public DiscoveryDiff recentDiff(long id, long actor) {
        catalog.findConnection(id).orElseThrow(() -> new IllegalArgumentException("MCP connection not found"));
        List<McpCatalogRepository.DiscoverySnapshot> snapshots = catalog.recentDiscoveries(id, actor);
        SnapshotSummary current = snapshots.isEmpty() ? null : summarize(snapshots.get(0));
        SnapshotSummary previous = snapshots.size() < 2 ? null : summarize(snapshots.get(1));
        if (previous == null) return new DiscoveryDiff(previous, current, List.of());

        Map<String, McpToolDescriptor> before = byName(snapshots.get(1).tools());
        Map<String, McpToolDescriptor> after = byName(snapshots.get(0).tools());
        List<ToolChange> changes = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            McpToolDescriptor oldTool = before.get(name);
            McpToolDescriptor newTool = after.get(name);
            if (oldTool == null) {
                changes.add(new ToolChange(name, ChangeType.ADDED, List.of(), null, newTool));
            } else if (newTool == null) {
                changes.add(new ToolChange(name, ChangeType.REMOVED, List.of(), oldTool, null));
            } else {
                List<String> fields = new ArrayList<>();
                if (!Objects.equals(oldTool.description(), newTool.description())) fields.add("description");
                if (!Objects.equals(oldTool.inputSchema(), newTool.inputSchema())) fields.add("inputSchema");
                if (!Objects.equals(oldTool.outputSchema(), newTool.outputSchema())) fields.add("outputSchema");
                if (oldTool.readOnlyHint() != newTool.readOnlyHint()) fields.add("readOnlyHint");
                if (!fields.isEmpty()) changes.add(new ToolChange(name, ChangeType.CHANGED,
                        List.copyOf(fields), oldTool, newTool));
            }
        }
        return new DiscoveryDiff(previous, current, List.copyOf(changes));
    }

    public long approve(long id, long snapshotId, String remoteName, Approval approval,
                        long actor, String reason) {
        McpConnection connection = active(id);
        requireReason(reason);
        McpToolDescriptor observed = catalog.findDiscovery(snapshotId, id, actor).stream()
                .filter(tool -> tool.name().equals(remoteName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tool was not observed in this discovery"));
        if (!"object".equals(observed.inputSchema().get("type")))
            throw new IllegalArgumentException("MCP input schema must be an object");
        if (approval.readOnly() && !observed.readOnlyHint())
            throw new IllegalArgumentException("MCP read-only approval requires a read-only declaration");
        if (!approval.readOnly() && !approval.requiresConfirmation())
            throw new IllegalArgumentException("MCP write tools require confirmation");
        if (approval.capabilityCode() == null || !approval.capabilityCode().matches("mcp[.][a-z0-9._-]{3,120}")
                || approval.revision() == null || approval.revision().isBlank()
                || approval.modelToolName() == null || !approval.modelToolName().matches("[a-zA-Z][a-zA-Z0-9_]{2,127}")
                || approval.displayName() == null || approval.displayName().isBlank()
                || approval.description() == null || approval.description().isBlank())
            throw new IllegalArgumentException("Invalid approved tool metadata");
        return catalog.approve(id, connection.revision(), snapshotId, observed, approval.capabilityCode(),
                approval.revision(), approval.modelToolName(), approval.displayName(),
                approval.description(), approval.readOnly(), approval.requiresConfirmation(), actor, reason);
    }

    private static SnapshotSummary summarize(McpCatalogRepository.DiscoverySnapshot snapshot) {
        return new SnapshotSummary(snapshot.id(), snapshot.connectionRevision(),
                snapshot.discoveredAt(), snapshot.tools().size());
    }

    private static Map<String, McpToolDescriptor> byName(List<McpToolDescriptor> tools) {
        Map<String, McpToolDescriptor> named = new TreeMap<>();
        for (McpToolDescriptor tool : tools) {
            if (named.putIfAbsent(tool.name(), tool) != null)
                throw new IllegalStateException("Duplicate MCP tool name in discovery snapshot");
        }
        return named;
    }

    private McpConnection active(long id) {
        McpConnection connection = catalog.findConnection(id)
                .orElseThrow(() -> new IllegalArgumentException("MCP connection not found"));
        if (!connection.enabled()) throw new IllegalStateException("MCP connection is disabled");
        endpoints.validate(URI.create(connection.endpoint()));
        return connection;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("A reason of at most 500 characters is required");
    }

    public record Discovery(long snapshotId, List<McpToolDescriptor> tools) { }
    public record SnapshotSummary(long snapshotId, long connectionRevision, Instant discoveredAt,
                                  int toolCount) { }
    public enum ChangeType { ADDED, REMOVED, CHANGED }
    public record ToolChange(String name, ChangeType type, List<String> changedFields,
                             McpToolDescriptor before, McpToolDescriptor after) { }
    public record DiscoveryDiff(SnapshotSummary previous, SnapshotSummary current,
                                List<ToolChange> changes) { }
    public record Approval(String capabilityCode, String revision, String modelToolName,
                           String displayName, String description, boolean readOnly,
                           boolean requiresConfirmation) { }
}
