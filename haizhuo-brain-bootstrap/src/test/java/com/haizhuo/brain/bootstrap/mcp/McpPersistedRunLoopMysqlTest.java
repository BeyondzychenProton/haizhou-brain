package com.haizhuo.brain.bootstrap.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.employee.JdbcAgentDefinitionRepository;
import com.haizhuo.brain.infrastructure.employee.JdbcHarnessDefinitionBundleRepository;
import com.haizhuo.brain.infrastructure.harness.JdbcSessionBridgeSnapshotRepository;
import com.haizhuo.brain.infrastructure.harness.JdbcSessionHarnessBindingRepository;
import com.haizhuo.brain.infrastructure.identity.JdbcPlatformIdentityRepository;
import com.haizhuo.brain.infrastructure.identity.JdbcToolExecutionUserDirectory;
import com.haizhuo.brain.infrastructure.mcp.JdbcMcpCatalogRepository;
import com.haizhuo.brain.infrastructure.mcp.McpCapabilityExecutor;
import com.haizhuo.brain.infrastructure.mcp.SdkMcpRemoteClient;
import com.haizhuo.brain.infrastructure.mcp.SimulatorMcpUserTokenProvider;
import com.haizhuo.brain.infrastructure.run.JdbcRunExecutionStore;
import com.haizhuo.brain.infrastructure.session.JdbcHarnessRunSpecRepository;
import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.infrastructure.session.JdbcSessionRunStore;
import com.haizhuo.brain.infrastructure.tool.JdbcToolApprovalRepository;
import com.haizhuo.brain.infrastructure.tool.JdbcToolExecutionRepository;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import com.haizhuo.brain.platform.channel.ChannelTurnPromoter;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.platform.employee.runtime.DefaultHarnessDefinitionBundleCompiler;
import com.haizhuo.brain.platform.employee.runtime.DefaultHarnessDefinitionPublisher;
import com.haizhuo.brain.platform.harness.SessionBridgeService;
import com.haizhuo.brain.platform.mcp.DefaultMcpToolVisibility;
import com.haizhuo.brain.platform.mcp.McpAdministrationService;
import com.haizhuo.brain.platform.mcp.McpEndpointPolicy;
import com.haizhuo.brain.platform.run.HarnessRunSpecFactory;
import com.haizhuo.brain.platform.run.RunExecutionService;
import com.haizhuo.brain.platform.run.RunRealtimeEventPublisher;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.tool.DefaultToolExecutionGatewayService;
import com.haizhuo.brain.platform.tool.DefaultToolResourcePolicy;
import com.haizhuo.brain.platform.tool.ListCapabilityExecutorRegistry;
import com.haizhuo.brain.platform.tool.ResolvedCredential;
import com.haizhuo.brain.platform.tool.ToolExecutionWorker;
import com.haizhuo.brain.runtime.agentscope.AgentScopeRuntime;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessAgentFactory;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateCache;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.testsupport.mcp.SimulatedMcpServer;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import reactor.core.publisher.Flux;

/** Run worker、工具 worker、Harness 和 MCP HTTP 之间的真实持久交接。 */
class McpPersistedRunLoopMysqlTest {
    private static final String SECRET = "persisted-run-loop-secret-at-least-32-characters";
    @TempDir Path workspace;

    @Test
    void modelToolCallCrossesPersistedWorkersAndRunResumesWithMcpResult() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
             SimulatedMcpServer server = new SimulatedMcpServer(0, SECRET, "demo-mcp", Clock.systemUTC())) {
            mysql.start();
            server.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            assertTrue(Flyway.configure().dataSource(source).locations("classpath:db/migration")
                    .load().migrate().success);
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO platform_user(id,mobile_normalized,status,auth_version,"
                    + "must_change_password,created_at,updated_at) VALUES"
                    + "(1001,'+15550000001','ACTIVE',1,FALSE,NOW(3),NOW(3)),"
                    + "(1002,'+15550000002','ACTIVE',1,FALSE,NOW(3),NOW(3))");
            var transactions = new DataSourceTransactionManager(source);
            var json = new ObjectMapper();
            var catalog = new JdbcMcpCatalogRepository(jdbc, json, transactions);
            var definitions = new JdbcAgentDefinitionRepository(jdbc, transactions, json);
            var endpoints = new McpEndpointPolicy(Set.of(), true);
            var remote = new SdkMcpRemoteClient(json, endpoints);
            var tokens = new SimulatorMcpUserTokenProvider(
                    new JdbcPlatformIdentityRepository(jdbc, new NamedParameterJdbcTemplate(jdbc)),
                    SECRET, Clock.systemUTC());
            var admin = new McpAdministrationService(catalog, remote, tokens, endpoints);
            var connection = admin.create("demo-mcp", "Demo", "http://127.0.0.1:"
                    + server.port() + "/mcp", 1001, "persisted run test");
            var discovery = admin.discover(connection.id(), new UserId(1001));
            long readRevision = admin.approve(connection.id(), discovery.snapshotId(), "private_note_read",
                    new McpAdministrationService.Approval("mcp.demo.read", "1", "demo_note_read",
                            "读取便笺", "Read owned note", true, false), 1001, "read approved");

            var bundles = new JdbcHarnessDefinitionBundleRepository(jdbc, json);
            var executors = new ListCapabilityExecutorRegistry(List.of(
                    new McpCapabilityExecutor(catalog, remote, tokens)));
            var publisher = new DefaultHarnessDefinitionPublisher(definitions,
                    new DefaultHarnessDefinitionBundleCompiler(definitions), bundles);
            var management = new AgentDefinitionManagementService(definitions, executors, publisher);
            management.saveDraft(2, management.getDraft(2).draftRevision(),
                    new AgentDefinitionManagementService.DraftUpdate("读取当前用户的便笺。",
                            "openai", "deterministic-model",
                            List.of(new CapabilitySelection("mcp.demo.read", "1"))),
                    1001, "bind read tool");
            management.publish(2, management.getDraft(2).draftRevision(),
                    "persisted-run-v1", 1001, "publish read tool");

            var projector = new JdbcSessionEventProjector(jdbc);
            var sessions = transactional(new JdbcSessionRunStore(jdbc, projector), transactions);
            var specs = new JdbcHarnessRunSpecRepository(jdbc);
            var runSpecs = new HarnessRunSpecFactory(definitions, executors,
                    () -> new DefaultMcpToolVisibility(catalog, remote, tokens));
            var app = new SessionApplicationService(sessions, definitions, bundles, runSpecs, Clock.systemUTC());
            var reader = new UserId(1002);
            var run = app.createRun(app.create(reader, 2).id(), reader, "persisted-read", "读取我的便笺");
            assertEquals(Set.of("demo_note_read"),
                    specs.findByRunId(run.id()).orElseThrow().modelVisibleToolNames());

            var model = new ReadNoteModel();
            var modelFactory = new AgentScopeModelFactory(new AgentScopeRuntimeProperties(
                    "openai", "deterministic-model", "test-key", "http://localhost", true,
                    workspace.toString()));
            var harnessFactory = new HarnessAgentFactory(modelFactory,
                    new JdbcAgentStateStore(source, new MysqlDialect()), null,
                    new DefinitionWorkspaceMaterializer(workspace), sessions) {
                @Override protected ChatModelBase createModel(RuntimeDefinitionSnapshot ignored) {
                    return model;
                }
            };
            var runtime = new AgentScopeRuntime(new HarnessTemplateCache(harnessFactory),
                    new RuntimeContextFactory(), new AgentScopeEventTranslator());
            var snapshots = new JdbcSessionBridgeSnapshotRepository(jdbc);
            var bridge = new SessionBridgeService(new JdbcSessionHarnessBindingRepository(jdbc),
                    snapshots, sessions, Clock.systemUTC());
            var runStore = transactional(new JdbcRunExecutionStore(jdbc, projector), transactions);
            var toolStore = transactional(new JdbcToolExecutionRepository(jdbc), transactions);
            var approvals = new JdbcToolApprovalRepository(jdbc);
            var gateway = new DefaultToolExecutionGatewayService(definitions, specs,
                    new JdbcToolExecutionUserDirectory(jdbc), new DefaultToolResourcePolicy(),
                    (user, revision) -> ResolvedCredential.none(), executors, catalog, bundles, approvals);
            var runWorker = new RunExecutionService(runStore, bundles, bridge, snapshots,
                    sessions, toolStore, runtime, RunRealtimeEventPublisher.NOOP,
                    ChannelReplyEnqueuer.NOOP, ChannelTurnPromoter.NOOP, Clock.systemUTC());
            var toolWorker = new ToolExecutionWorker(toolStore, approvals, gateway, Clock.systemUTC());

            assertTrue(runWorker.executeNext("test-worker", Duration.ofSeconds(120)));
            assertEquals("WAITING_TOOL", state(jdbc, run.id().value()));
            var pending = toolStore.findByRun(run.id());
            assertEquals(1, pending.size());
            assertEquals(readRevision, pending.get(0).capabilityRevisionId());
            assertEquals("REQUESTED", pending.get(0).state().name());

            assertTrue(toolWorker.executeNext("test-worker"));
            assertEquals("QUEUED", state(jdbc, run.id().value()));
            var ready = toolStore.findByRun(run.id()).get(0);
            assertEquals("SUCCEEDED", ready.state().name());
            assertEquals("READY", ready.resultDeliveryState().name());
            assertEquals("reader's private note", json.readTree(ready.resultJson()).path("content").asText());

            assertTrue(runWorker.executeNext("test-worker", Duration.ofSeconds(120)));
            assertEquals("SUCCEEDED", state(jdbc, run.id().value()));
            assertEquals("DELIVERED", toolStore.findByRun(run.id()).get(0).resultDeliveryState().name());
            assertEquals("读取完成", jdbc.queryForObject("SELECT content FROM platform_agent_run_event "
                    + "WHERE run_id=? AND event_type='RUN_COMPLETED'", String.class, run.id().value()));
            assertEquals(2, model.calls.get());
            assertTrue(model.toolResults.contains("reader's private note"));
        }
    }

    private static String state(JdbcTemplate jdbc, String runId) {
        return jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?", String.class, runId);
    }

    private static <T> T transactional(T target, DataSourceTransactionManager manager) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T wrapped = (T) proxy.getProxy();
        return wrapped;
    }

    private static final class ReadNoteModel extends ChatModelBase {
        private final AtomicInteger calls = new AtomicInteger();
        private List<String> toolResults = List.of();

        @Override protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools,
                                                         GenerateOptions options) {
            if (calls.getAndIncrement() == 0) {
                assertTrue(tools.stream().anyMatch(tool -> "demo_note_read".equals(tool.getName())));
                return Flux.just(ChatResponse.builder().id("read-note")
                        .content(List.of(new ToolUseBlock("tool-use-1", "demo_note_read",
                                Map.of("noteId", "note-b"), "{\"noteId\":\"note-b\"}", null)))
                        .finishReason("tool_calls").build());
            }
            toolResults = messages.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream()).filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast).map(TextBlock::getText).toList();
            return Flux.just(ChatResponse.builder().id("read-complete")
                    .content(List.of(TextBlock.builder().text("读取完成").build()))
                    .finishReason("stop").build());
        }

        @Override public String getModelName() { return "deterministic-persisted-mcp-model"; }
    }
}
