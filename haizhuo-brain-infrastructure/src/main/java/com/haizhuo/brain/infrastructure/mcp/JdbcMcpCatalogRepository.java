package com.haizhuo.brain.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.mcp.McpCatalogRepository;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.platform.mcp.McpToolDescriptor;
import com.haizhuo.brain.platform.mcp.McpToolSource;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcMcpCatalogRepository implements McpCatalogRepository {
    private static final TypeReference<List<McpToolDescriptor>> TOOLS = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> SCHEMA = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public JdbcMcpCatalogRepository(JdbcTemplate jdbc, ObjectMapper json,
                                    PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override public List<McpConnection> listConnections() {
        return jdbc.query("SELECT * FROM mcp_connection ORDER BY id", (rs, n) -> mapConnection(rs));
    }

    @Override public Optional<McpConnection> findConnection(long id) {
        return jdbc.query("SELECT * FROM mcp_connection WHERE id=?",
                (rs, n) -> mapConnection(rs), id).stream().findFirst();
    }

    @Override public McpConnection createConnection(String code, String name, String endpoint,
                                                     long actor, String reason) {
        return tx.execute(status -> {
            Instant now = Instant.now();
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO mcp_connection(connection_code,display_name,endpoint_url,config_revision,enabled,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,1,TRUE,?,?,?,?)",
                        new String[]{"id"});
                statement.setString(1, code);
                statement.setString(2, name);
                statement.setString(3, endpoint);
                statement.setLong(4, actor);
                statement.setTimestamp(5, Timestamp.from(now));
                statement.setLong(6, actor);
                statement.setTimestamp(7, Timestamp.from(now));
                return statement;
            }, key);
            long id = key.getKey().longValue();
            audit(actor, "MCP_CONNECTION_CREATED", "MCP_CONNECTION", String.valueOf(id), reason,
                    "{}", CanonicalJson.write(Map.of("code", code, "endpoint", endpoint, "enabled", true)));
            return new McpConnection(id, code, name, endpoint, 1, true);
        });
    }

    @Override public void setConnectionEnabled(long id, boolean enabled, long actor, String reason) {
        tx.executeWithoutResult(status -> {
            McpConnection old = jdbc.query("SELECT * FROM mcp_connection WHERE id=? FOR UPDATE",
                    (rs, n) -> mapConnection(rs), id).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("MCP connection not found"));
            jdbc.update("UPDATE mcp_connection SET enabled=?,config_revision=config_revision+1,updated_by=?,updated_at=? WHERE id=?",
                    enabled, actor, Timestamp.from(Instant.now()), id);
            audit(actor, "MCP_CONNECTION_STATUS_CHANGED", "MCP_CONNECTION", String.valueOf(id), reason,
                    CanonicalJson.write(Map.of("enabled", old.enabled(), "revision", old.revision())),
                    CanonicalJson.write(Map.of("enabled", enabled, "revision", old.revision() + 1)));
        });
    }

    @Override public long saveDiscovery(long connectionId, long revision, long actor,
                                        List<McpToolDescriptor> tools) {
        return tx.execute(status -> {
            McpConnection current = jdbc.query("SELECT * FROM mcp_connection WHERE id=? FOR UPDATE",
                    (rs, n) -> mapConnection(rs), connectionId).stream().findFirst().orElseThrow();
            if (!current.enabled() || current.revision() != revision)
                throw new IllegalStateException("MCP connection changed during discovery");
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO mcp_discovery_snapshot(connection_id,connection_revision,discovered_by,tools_json,created_at) VALUES(?,?,?,?,?)",
                        new String[]{"id"});
                statement.setLong(1, connectionId);
                statement.setLong(2, revision);
                statement.setLong(3, actor);
                statement.setString(4, write(tools));
                statement.setTimestamp(5, Timestamp.from(Instant.now()));
                return statement;
            }, key);
            return key.getKey().longValue();
        });
    }

    @Override public List<DiscoverySnapshot> recentDiscoveries(long connectionId, long actor) {
        return jdbc.query("SELECT id,connection_revision,tools_json,created_at FROM mcp_discovery_snapshot "
                        + "WHERE connection_id=? AND discovered_by=? ORDER BY id DESC LIMIT 2",
                (rs, n) -> new DiscoverySnapshot(rs.getLong("id"), rs.getLong("connection_revision"),
                        rs.getTimestamp("created_at").toInstant(), readTools(rs.getString("tools_json"))),
                connectionId, actor);
    }

    @Override public List<McpToolDescriptor> findDiscovery(long snapshotId, long connectionId, long actor) {
        List<String> rows = jdbc.query("SELECT s.tools_json FROM mcp_discovery_snapshot s JOIN mcp_connection c ON c.id=s.connection_id WHERE s.id=? AND s.connection_id=? AND s.discovered_by=? AND s.connection_revision=c.config_revision AND c.enabled=TRUE AND s.id=(SELECT MAX(latest.id) FROM mcp_discovery_snapshot latest WHERE latest.connection_id=s.connection_id AND latest.discovered_by=s.discovered_by)",
                (rs, n) -> rs.getString(1), snapshotId, connectionId, actor);
        if (rows.isEmpty()) throw new IllegalArgumentException("MCP discovery is missing or stale");
        return readTools(rows.get(0));
    }

    @Override public Optional<McpToolSource> findSource(long capabilityRevisionId) {
        return jdbc.query("SELECT capability_revision_id,connection_id,connection_revision,remote_tool_name,output_schema_json,read_only FROM mcp_tool_revision WHERE capability_revision_id=?",
                (rs, n) -> new McpToolSource(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), readSchema(rs.getString(5)), rs.getBoolean(6)), capabilityRevisionId).stream().findFirst();
    }

    @Override public long approve(long connectionId, long connectionRevision, long snapshotId,
                                  McpToolDescriptor tool,
                                  String code, String revision, String modelName, String displayName,
                                  String description, boolean readOnly, boolean confirm, long actor,
                                  String reason) {
        return tx.execute(status -> {
            List<McpConnection> connections = jdbc.query("SELECT * FROM mcp_connection WHERE id=? FOR UPDATE",
                    (rs, n) -> mapConnection(rs), connectionId);
            if (connections.isEmpty() || !connections.get(0).enabled()
                    || connections.get(0).revision() != connectionRevision)
                throw new IllegalStateException("MCP connection changed before approval");
            List<Long> latest = jdbc.query("SELECT id FROM mcp_discovery_snapshot "
                            + "WHERE connection_id=? AND discovered_by=? ORDER BY id DESC LIMIT 1",
                    (rs, n) -> rs.getLong(1), connectionId, actor);
            if (latest.isEmpty() || latest.get(0) != snapshotId)
                throw new IllegalArgumentException("MCP discovery is missing or stale");
            Integer collision = jdbc.queryForObject("SELECT COUNT(*) FROM capability_revision WHERE tool_name=? AND capability_code<>?",
                    Integer.class, modelName, code);
            if (collision != null && collision > 0)
                throw new IllegalArgumentException("Model tool name belongs to another capability");
            List<String> types = jdbc.query("SELECT capability_type FROM capability_definition WHERE capability_code=? FOR UPDATE",
                    (rs, n) -> rs.getString(1), code);
            if (types.isEmpty()) {
                jdbc.update("INSERT INTO capability_definition(capability_code,capability_type,status,updated_by,updated_at) VALUES(?,'MCP','ACTIVE',?,?)",
                        code, actor, Timestamp.from(Instant.now()));
            } else if (!"MCP".equals(types.get(0))) {
                throw new IllegalArgumentException("Capability code belongs to a different source");
            }
            jdbc.update("INSERT INTO mcp_model_tool_name_claim(tool_name,capability_code) VALUES(?,?) "
                    + "ON DUPLICATE KEY UPDATE tool_name=tool_name", modelName, code);
            String owner = jdbc.queryForObject("SELECT capability_code FROM mcp_model_tool_name_claim WHERE tool_name=?",
                    String.class, modelName);
            if (!code.equals(owner))
                throw new IllegalArgumentException("Model tool name belongs to another capability");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM capability_revision WHERE capability_code=? AND revision=?",
                    Integer.class, code, revision) > 0)
                throw new IllegalArgumentException("Capability revision already exists");
            String input = write(tool.inputSchema());
            String digest = CanonicalJson.sha256(Map.of("code", code, "revision", revision,
                    "remote", tool.name(), "description", description, "schema", tool.inputSchema()));
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO capability_revision(capability_code,revision,display_name,description,tool_name,implementation_key,business_action,input_schema_json,content_hash,requires_confirmation) VALUES(?,?,?,?,?,'mcp-trusted-v1',?,?,?,?)",
                        new String[]{"capability_revision_id"});
                statement.setString(1, code); statement.setString(2, revision);
                statement.setString(3, displayName); statement.setString(4, description);
                statement.setString(5, modelName); statement.setString(6, readOnly ? "mcp.read" : "mcp.write");
                statement.setString(7, input); statement.setString(8, digest);
                statement.setBoolean(9, confirm);
                return statement;
            }, key);
            long id = key.getKey().longValue();
            jdbc.update("INSERT INTO mcp_tool_revision(capability_revision_id,connection_id,connection_revision,remote_tool_name,output_schema_json,read_only,approved_by,approved_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, connectionId, connectionRevision, tool.name(), write(tool.outputSchema()),
                    readOnly, actor, Timestamp.from(Instant.now()));
            audit(actor, "MCP_TOOL_APPROVED", "CAPABILITY", code + "@" + revision, reason, "{}",
                    CanonicalJson.write(Map.of("connectionId", connectionId, "remoteName", tool.name(),
                            "modelToolName", modelName, "readOnly", readOnly)));
            return id;
        });
    }

    private McpConnection mapConnection(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new McpConnection(rs.getLong("id"), rs.getString("connection_code"),
                rs.getString("display_name"), rs.getString("endpoint_url"),
                rs.getLong("config_revision"), rs.getBoolean("enabled"));
    }

    private void audit(long actor, String event, String type, String target, String reason,
                       String before, String after) {
        jdbc.update("INSERT INTO agent_definition_management_audit(actor_user_id,event_type,target_type,target_id,request_id,reason,previous_summary,new_summary,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
                actor, event, type, target, null, reason, before, after, Timestamp.from(Instant.now()));
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("MCP JSON encoding failed", error); }
    }

    private List<McpToolDescriptor> readTools(String value) {
        try { return json.readValue(value, TOOLS); }
        catch (Exception error) { throw new IllegalStateException("Invalid MCP discovery snapshot", error); }
    }

    private Map<String, Object> readSchema(String value) {
        if (value == null) return Map.of();
        try { return json.readValue(value, SCHEMA); }
        catch (Exception error) { throw new IllegalStateException("Invalid MCP approved output schema", error); }
    }
}
