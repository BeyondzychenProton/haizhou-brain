package com.haizhuo.brain.platform.mcp;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** 持久保存连接、发现结果及已批准工具来源的事实。 */
public interface McpCatalogRepository {
    List<McpConnection> listConnections();
    Optional<McpConnection> findConnection(long id);
    McpConnection createConnection(String code, String displayName, String endpoint, long actor, String reason);
    void setConnectionEnabled(long id, boolean enabled, long actor, String reason);
    long saveDiscovery(long connectionId, long connectionRevision, long actor,
                       List<McpToolDescriptor> tools);
    List<DiscoverySnapshot> recentDiscoveries(long connectionId, long actor);
    List<McpToolDescriptor> findDiscovery(long snapshotId, long connectionId, long actor);
    Optional<McpToolSource> findSource(long capabilityRevisionId);
    long approve(long connectionId, long connectionRevision, long snapshotId, McpToolDescriptor tool,
                 String capabilityCode, String revision, String modelToolName,
                 String displayName, String description, boolean readOnly,
                 boolean requiresConfirmation, long actor, String reason);

    record DiscoverySnapshot(long id, long connectionRevision, Instant discoveredAt,
                             List<McpToolDescriptor> tools) { }
}
