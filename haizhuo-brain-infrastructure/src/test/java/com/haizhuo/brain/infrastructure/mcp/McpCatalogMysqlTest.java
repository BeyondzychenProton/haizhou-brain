package com.haizhuo.brain.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.infrastructure.employee.JdbcAgentDefinitionRepository;
import com.haizhuo.brain.platform.mcp.McpToolDescriptor;
import com.haizhuo.brain.platform.mcp.McpAdministrationService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL migration and governance round-trip; Docker is required. */
class McpCatalogMysqlTest {
    @Test
    void migrationDiscoveryApprovalAndNewRevisionAreDurable() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            var migration = Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration").load().migrate();
            assertTrue(migration.success);

            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new DataSourceTransactionManager(dataSource);
            var catalog = new JdbcMcpCatalogRepository(jdbc, new ObjectMapper(), transactions);
            var definitions = new JdbcAgentDefinitionRepository(jdbc, transactions, new ObjectMapper());
            var connection = catalog.createConnection("demo-mcp", "Demo", "https://mcp.example.test/mcp", 1001, "test");
            var tool = new McpToolDescriptor("private_note_read", "Read note",
                    Map.of("type", "object", "properties", Map.of("noteId", Map.of("type", "string")),
                            "required", List.of("noteId")), Map.of(), true);
            long snapshot = catalog.saveDiscovery(connection.id(), connection.revision(), 1001, List.of(tool));
            assertEquals(List.of(tool), catalog.findDiscovery(snapshot, connection.id(), 1001));
            assertThrows(IllegalArgumentException.class,
                    () -> catalog.findDiscovery(snapshot, connection.id(), 1002));

            long first = catalog.approve(connection.id(), connection.revision(), snapshot, tool,
                    "mcp.demo.note", "1", "demo_note_read", "读取私有便笺", "Read private note", true,
                    false, 1001, "approved for test");
            assertEquals(CapabilityBinding.CapabilityType.MCP,
                    definitions.findCapabilityByRevisionId(first).orElseThrow().type());
            assertEquals("private_note_read", catalog.findSource(first).orElseThrow().remoteName());
            long second = catalog.approve(connection.id(), connection.revision(), snapshot, tool,
                    "mcp.demo.note", "2", "demo_note_read", "读取私有便笺", "Read private note", true,
                    false, 1001, "new revision");
            assertNotEquals(first, second);
            assertThrows(IllegalArgumentException.class, () -> catalog.approve(connection.id(), connection.revision(), snapshot, tool,
                    "mcp.other.note", "1", "demo_note_read", "冲突", "Conflict", true,
                    false, 1001, "collision"));
            catalog.setConnectionEnabled(connection.id(), false, 1001, "maintenance");
            assertFalse(catalog.findConnection(connection.id()).orElseThrow().enabled());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM capability_revision WHERE capability_code='mcp.other.note'", Integer.class));
        }
    }

    @Test
    void recentDiscoveryDiffIsDurableScopedAndCannotApproveHistoricalSnapshot() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var transactions = new DataSourceTransactionManager(dataSource);
            var catalog = new JdbcMcpCatalogRepository(jdbc, new ObjectMapper(), transactions);
            var connection = catalog.createConnection("demo-mcp", "Demo", "https://mcp.example.test/mcp", 1001, "test");
            var another = catalog.createConnection("other-mcp", "Other", "https://mcp.example.test/mcp", 1001, "test");
            var old = new McpToolDescriptor("alpha", "Old description",
                    Map.of("type", "object", "properties", Map.of("old", Map.of("type", "string"))),
                    Map.of("type", "object", "properties", Map.of("old", Map.of("type", "string"))), true);
            var changed = new McpToolDescriptor("alpha", "New description",
                    Map.of("type", "object", "properties", Map.of("new", Map.of("type", "number"))),
                    Map.of("type", "object", "properties", Map.of("new", Map.of("type", "number"))), false);
            var gone = new McpToolDescriptor("gone", "Gone", Map.of("type", "object"), Map.of(), true);
            var added = new McpToolDescriptor("added", "Added", Map.of("type", "object"), Map.of(), true);
            var hidden = new McpToolDescriptor("hidden", "Other user only", Map.of("type", "object"), Map.of(), true);
            long older = catalog.saveDiscovery(connection.id(), connection.revision(), 1001, List.of(gone));
            long previous = catalog.saveDiscovery(connection.id(), connection.revision(), 1001, List.of(old, gone));
            long otherUser = catalog.saveDiscovery(connection.id(), connection.revision(), 1002, List.of(hidden));
            catalog.saveDiscovery(another.id(), another.revision(), 1001, List.of(hidden));
            long current = catalog.saveDiscovery(connection.id(), connection.revision(), 1001, List.of(changed, added));

            var afterRestart = new JdbcMcpCatalogRepository(jdbc, new ObjectMapper(), transactions);
            var admin = new McpAdministrationService(afterRestart, null, null,
                    new com.haizhuo.brain.platform.mcp.McpEndpointPolicy(Set.of("mcp.example.test"), false));
            var diff = admin.recentDiff(connection.id(), 1001);
            assertEquals(previous, diff.previous().snapshotId());
            assertEquals(current, diff.current().snapshotId());
            assertEquals(connection.revision(), diff.current().connectionRevision());
            assertNotNull(diff.current().discoveredAt());
            assertEquals(2, diff.current().toolCount());
            assertEquals(List.of("added", "alpha", "gone"), diff.changes().stream().map(c -> c.name()).toList());
            assertEquals(McpAdministrationService.ChangeType.ADDED, diff.changes().get(0).type());
            assertEquals(List.of("description", "inputSchema", "outputSchema", "readOnlyHint"),
                    diff.changes().get(1).changedFields());
            assertEquals(McpAdministrationService.ChangeType.REMOVED, diff.changes().get(2).type());
            assertTrue(diff.changes().stream().noneMatch(change -> change.name().equals("hidden")));
            assertNotEquals(older, diff.previous().snapshotId());
            var isolated = admin.recentDiff(connection.id(), 1002);
            assertNull(isolated.previous());
            assertEquals(otherUser, isolated.current().snapshotId());
            assertTrue(isolated.changes().isEmpty());
            assertNull(admin.recentDiff(another.id(), 1002).current());
            assertThrows(IllegalArgumentException.class,
                    () -> afterRestart.findDiscovery(previous, connection.id(), 1001));
            assertThrows(IllegalArgumentException.class, () -> afterRestart.approve(
                    connection.id(), connection.revision(), previous, old, "mcp.demo.old", "1",
                    "demo_old", "Old", "Old", true, false, 1001, "stale snapshot"));

            afterRestart.setConnectionEnabled(connection.id(), false, 1001, "test disable");
            assertEquals(current, admin.recentDiff(connection.id(), 1001).current().snapshotId());
            assertThrows(IllegalArgumentException.class,
                    () -> afterRestart.findDiscovery(current, connection.id(), 1001));
            afterRestart.setConnectionEnabled(connection.id(), true, 1001, "test enable");
            assertEquals(current, admin.recentDiff(connection.id(), 1001).current().snapshotId());
            assertThrows(IllegalArgumentException.class,
                    () -> afterRestart.findDiscovery(current, connection.id(), 1001));
        }
    }
}
