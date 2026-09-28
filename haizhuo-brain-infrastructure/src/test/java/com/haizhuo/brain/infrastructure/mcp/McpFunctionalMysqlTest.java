package com.haizhuo.brain.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.employee.JdbcAgentDefinitionRepository;
import com.haizhuo.brain.infrastructure.employee.JdbcHarnessDefinitionBundleRepository;
import com.haizhuo.brain.infrastructure.identity.JdbcToolExecutionUserDirectory;
import com.haizhuo.brain.infrastructure.identity.JdbcPlatformIdentityRepository;
import com.haizhuo.brain.infrastructure.session.JdbcHarnessRunSpecRepository;
import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.infrastructure.session.JdbcSessionRunStore;
import com.haizhuo.brain.infrastructure.tool.JdbcToolApprovalRepository;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.platform.employee.runtime.DefaultHarnessDefinitionBundleCompiler;
import com.haizhuo.brain.platform.employee.runtime.DefaultHarnessDefinitionPublisher;
import com.haizhuo.brain.platform.mcp.DefaultMcpToolVisibility;
import com.haizhuo.brain.platform.mcp.McpAdministrationService;
import com.haizhuo.brain.platform.mcp.McpEndpointPolicy;
import com.haizhuo.brain.platform.mcp.McpToolDescriptor;
import com.haizhuo.brain.platform.mcp.McpUserTokenProvider;
import com.haizhuo.brain.platform.run.HarnessRunSpecFactory;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.tool.DefaultToolExecutionGatewayService;
import com.haizhuo.brain.platform.tool.ApprovalState;
import com.haizhuo.brain.platform.tool.DefaultToolResourcePolicy;
import com.haizhuo.brain.platform.tool.ListCapabilityExecutorRegistry;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResolvedCredential;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import com.haizhuo.brain.platform.tool.ToolPreparation;
import com.haizhuo.brain.platform.tool.ToolApproval;
import com.haizhuo.brain.platform.tool.ToolApprovalRepository;
import com.haizhuo.brain.testsupport.mcp.SimulatedMcpServer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Registration → discovery → approval → publish → per-user Run → call → revocation. */
class McpFunctionalMysqlTest {
    @Test
    void endToEndWithoutModelUsesRealMysqlAndMcpHttp() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
             SimulatedMcpServer server = new SimulatedMcpServer(0,
                     "functional-test-secret-at-least-32-characters", "demo-mcp", Clock.systemUTC())) {
            mysql.start();
            server.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO platform_user(id,mobile_normalized,status,auth_version,must_change_password,created_at,updated_at) VALUES(1001,'+15550000001','ACTIVE',1,FALSE,NOW(3),NOW(3)),(1002,'+15550000002','ACTIVE',1,FALSE,NOW(3),NOW(3))");

            var tx = new DataSourceTransactionManager(source);
            var json = new ObjectMapper();
            var catalog = new JdbcMcpCatalogRepository(jdbc, json, tx);
            var definitions = new JdbcAgentDefinitionRepository(jdbc, tx, json);
            var endpointPolicy = new McpEndpointPolicy(Set.of(), true);
            var remote = new SdkMcpRemoteClient(json, endpointPolicy);
            McpUserTokenProvider tokens = new SimulatorMcpUserTokenProvider(
                    new JdbcPlatformIdentityRepository(jdbc, new NamedParameterJdbcTemplate(jdbc)),
                    "functional-test-secret-at-least-32-characters", Clock.systemUTC());
            var admin = new McpAdministrationService(catalog, remote, tokens, endpointPolicy);
            var connection = admin.create("demo-mcp", "Demo", "http://127.0.0.1:"
                    + server.port() + "/mcp", 1001, "functional test");
            var discovery = admin.discover(connection.id(), new UserId(1001));
            assertEquals(3, discovery.tools().size());
            McpToolDescriptor read = discovery.tools().stream().filter(t -> t.name().equals("private_note_read"))
                    .findFirst().orElseThrow();
            McpToolDescriptor write = discovery.tools().stream().filter(t -> t.name().equals("private_note_write"))
                    .findFirst().orElseThrow();
            long readId = admin.approve(connection.id(), discovery.snapshotId(), read.name(),
                    new McpAdministrationService.Approval("mcp.demo.read", "1", "demo_note_read",
                            "读取便笺", "Read owned note", true, false), 1001, "read approved");
            long writeId = admin.approve(connection.id(), discovery.snapshotId(), write.name(),
                    new McpAdministrationService.Approval("mcp.demo.write", "1", "demo_note_write",
                            "更新便笺", "Write owned note", false, true), 1001, "write approved");
            assertThrows(IllegalArgumentException.class, () -> admin.approve(connection.id(),
                    discovery.snapshotId(), write.name(),
                    new McpAdministrationService.Approval("mcp.demo.fake-read", "1", "fake_read",
                            "伪造只读", "Incorrect read-only classification", true, false),
                    1001, "must reject inaccurate classification"));

            var bundles = new JdbcHarnessDefinitionBundleRepository(jdbc, json);
            var executor = new McpCapabilityExecutor(catalog, remote, tokens);
            var executors = new ListCapabilityExecutorRegistry(List.of(executor));
            var publisher = new DefaultHarnessDefinitionPublisher(definitions,
                    new DefaultHarnessDefinitionBundleCompiler(definitions), bundles);
            var management = new AgentDefinitionManagementService(definitions, executors, publisher);
            int draftRevision = management.getDraft(2).draftRevision();
            management.saveDraft(2, draftRevision,
                    new AgentDefinitionManagementService.DraftUpdate("只读取当前用户有权访问的便笺。",
                            "openai", "test-model", List.of(new CapabilitySelection("mcp.demo.read", "1"),
                                    new CapabilitySelection("mcp.demo.write", "1"))), 1001, "bind tools");
            int nextRevision = management.getDraft(2).draftRevision();
            assertTrue(management.validateDraft(2).publishable());
            var published = management.publish(2, nextRevision, "mcp-functional-v1", 1001, "publish");
            assertEquals(2, published.capabilities().size());

            var specs = new JdbcHarnessRunSpecRepository(jdbc);
            var sessions = new JdbcSessionRunStore(jdbc, new JdbcSessionEventProjector(jdbc));
            var specFactory = new HarnessRunSpecFactory(definitions, executors,
                    () -> new DefaultMcpToolVisibility(catalog, remote, tokens));
            var app = new SessionApplicationService(sessions, definitions, bundles, specFactory, Clock.systemUTC());
            var writer = new UserId(1001);
            var reader = new UserId(1002);
            var writerRun = app.createRun(app.create(writer, 2).id(), writer, "writer-run", "read my note");
            var readerRun = app.createRun(app.create(reader, 2).id(), reader, "reader-run", "read my note");
            assertEquals(Set.of("demo_note_read", "demo_note_write"),
                    specs.findByRunId(writerRun.id()).orElseThrow().modelVisibleToolNames());
            assertEquals(Set.of("demo_note_read"),
                    specs.findByRunId(readerRun.id()).orElseThrow().modelVisibleToolNames());

            var gateway = new DefaultToolExecutionGatewayService(definitions, specs,
                    new JdbcToolExecutionUserDirectory(jdbc), new DefaultToolResourcePolicy(),
                    (user, revision) -> ResolvedCredential.none(), executors, catalog, bundles,
                    new JdbcToolApprovalRepository(jdbc));
            PlatformToolExecution ownRead = execution(readerRun.id(), readId, "demo_note_read", "{\"noteId\":\"note-b\"}");
            ToolPreparation prepared = gateway.prepare(ownRead, readerRun);
            assertTrue(prepared.allowed());
            assertEquals("reader's private note", gateway.execute(ownRead, readerRun, prepared).content());
            PlatformToolExecution otherRead = execution(readerRun.id(), readId, "demo_note_read", "{\"noteId\":\"note-a\"}");
            assertEquals("MCP_PERMISSION_DENIED", gateway.execute(otherRead, readerRun,
                    gateway.prepare(otherRead, readerRun)).errorCode());
            PlatformToolExecution hiddenWrite = execution(readerRun.id(), writeId, "demo_note_write",
                    "{\"noteId\":\"note-b\",\"text\":\"bad\"}");
            assertEquals("TOOL_OUT_OF_RUN_VIEW", gateway.prepare(hiddenWrite, readerRun).denialCode());
            PlatformToolExecution writerWrite = execution(writerRun.id(), writeId, "demo_note_write",
                    "{\"noteId\":\"note-a\",\"text\":\"good\"}");
            assertEquals(ToolPreparation.Outcome.APPROVAL_REQUIRED, gateway.prepare(writerWrite, writerRun).outcome());
            ToolApprovalRepository confirmed = new ToolApprovalRepository() {
                @Override public Optional<ToolApproval> findByToolExecutionId(String id) {
                    return id.equals(writerWrite.id()) ? Optional.of(new ToolApproval("confirmed", id,
                            writerRun.id(), writer, ApprovalState.APPROVED, writer,
                            "functional confirmation", Instant.now(), Instant.now())) : Optional.empty();
                }
                @Override public void createPending(PlatformToolExecution ignored, long user) { }
                @Override public boolean decide(String id, ApprovalState decision, long actor, String reason) {
                    return false;
                }
            };
            var approvedGateway = new DefaultToolExecutionGatewayService(definitions, specs,
                    new JdbcToolExecutionUserDirectory(jdbc), new DefaultToolResourcePolicy(),
                    (user, revision) -> ResolvedCredential.none(), executors, catalog, bundles, confirmed);
            var confirmedPreparation = approvedGateway.prepare(writerWrite, writerRun);
            assertTrue(confirmedPreparation.allowed());
            server.dropNextWriteResponseForTest();
            assertTrue(approvedGateway.execute(writerWrite, writerRun, confirmedPreparation)
                    .reconciliationRequired());
            assertEquals("saved:" + writerWrite.idempotencyKey(), remote.call(connection,
                    tokens.tokenFor(writer, connection), "private_note_write_status",
                    Map.of("operationId", writerWrite.idempotencyKey()), "status-check").content());
            server.changeReadSchemaForTest();
            assertEquals("MCP_TOOL_CHANGED", gateway.execute(ownRead, readerRun, prepared).errorCode());
            var changedRun = app.createRun(app.create(reader, 2).id(), reader, "changed-run", "read my note");
            assertFalse(specs.findByRunId(changedRun.id()).orElseThrow().modelVisibleToolNames()
                    .contains("demo_note_read"));
            admin.setEnabled(connection.id(), false, 1001, "revoke");
            assertEquals("MCP_SOURCE_UNAVAILABLE", gateway.prepare(ownRead, readerRun).denialCode());
            assertEquals("MCP_SOURCE_UNAVAILABLE", gateway.execute(ownRead, readerRun, prepared).errorCode());
            admin.setEnabled(connection.id(), true, 1001, "re-enable requires fresh approval");
            assertEquals("MCP_SOURCE_UNAVAILABLE", gateway.prepare(ownRead, readerRun).denialCode());
            var rediscovery = admin.discover(connection.id(), writer);
            long newReadId = admin.approve(connection.id(), rediscovery.snapshotId(), read.name(),
                    new McpAdministrationService.Approval("mcp.demo.read", "2", "demo_note_read",
                            "读取便笺", "Read owned note using reviewed schema", true, false),
                    1001, "approve changed schema and connection revision");
            int recoveryDraft = management.getDraft(2).draftRevision();
            management.saveDraft(2, recoveryDraft,
                    new AgentDefinitionManagementService.DraftUpdate("读取当前用户的便笺。",
                            "openai", "test-model", List.of(new CapabilitySelection("mcp.demo.read", "2"))),
                    1001, "publish reviewed revision");
            management.publish(2, management.getDraft(2).draftRevision(), "mcp-functional-v2", 1001,
                    "publish reviewed revision");
            var recoveredRun = app.createRun(app.create(reader, 2).id(), reader, "recovered-run", "read my note");
            assertEquals(Set.of("demo_note_read"),
                    specs.findByRunId(recoveredRun.id()).orElseThrow().modelVisibleToolNames());
            var recoveredRead = execution(recoveredRun.id(), newReadId, "demo_note_read", "{\"noteId\":\"note-b\"}");
            assertTrue(gateway.prepare(recoveredRead, recoveredRun).allowed());
        }
    }

    private static PlatformToolExecution execution(com.haizhuo.brain.kernel.identity.RunId runId,
                                                    long revision, String name, String input) {
        return new PlatformToolExecution(java.util.UUID.randomUUID().toString(), runId,
                java.util.UUID.randomUUID().toString(), revision, name, input, "i".repeat(64),
                ToolExecutionState.EXECUTING, java.util.UUID.randomUUID().toString(), null,
                null, null, null, null, Instant.now(), Instant.now());
    }
}
