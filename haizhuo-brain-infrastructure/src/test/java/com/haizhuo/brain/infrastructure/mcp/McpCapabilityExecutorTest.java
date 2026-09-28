package com.haizhuo.brain.infrastructure.mcp;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.mcp.McpCatalogRepository;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.platform.mcp.McpRemoteClient;
import com.haizhuo.brain.platform.mcp.McpRemoteFailure;
import com.haizhuo.brain.platform.mcp.McpToolDescriptor;
import com.haizhuo.brain.platform.mcp.McpToolSource;
import com.haizhuo.brain.platform.mcp.McpUserTokenProvider;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpecRepository;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import com.haizhuo.brain.platform.tool.DefaultToolExecutionGatewayService;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResolvedCredential;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import com.haizhuo.brain.platform.tool.ToolExecutionUserDirectory;
import com.haizhuo.brain.platform.tool.ToolPreparation;
import com.haizhuo.brain.platform.tool.ToolResourcePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpCapabilityExecutorTest {
    private static final McpConnection CONNECTION = new McpConnection(1, "demo", "Demo",
            "https://mcp.example.test/mcp", 1, true);
    private static final McpToolSource SOURCE = new McpToolSource(42, 1, 1,
            "private_note_write", Map.of(), false);
    private static final CapabilityCatalogEntry CAPABILITY = new CapabilityCatalogEntry(42,
            "mcp.note.write", CapabilityBinding.CapabilityType.MCP, "1", "Write note",
            "Write a note", "note_write", "mcp-trusted-v1", "mcp.write", Map.of(), true, false);
    private static final UserId USER = new UserId(1001);
    private static final RunId RUN_ID = new RunId("run-1");

    @Test
    void credentialFailureIsDefiniteAndDoesNotContactRemoteServer() {
        List<McpUserTokenProvider> unavailable = List.of(
                (user, connection) -> { throw new IllegalStateException("secret provider detail"); },
                (user, connection) -> null,
                (user, connection) -> "  ");
        for (McpUserTokenProvider tokens : unavailable) {
            McpRemoteClient remote = mock(McpRemoteClient.class);
            var gateway = gateway(remote, tokens);

            var result = gateway.execute(execution(), run(), ToolPreparation.allowed(CAPABILITY));

            assertFalse(result.success());
            assertEquals("CREDENTIAL_UNAVAILABLE", result.errorCode());
            assertFalse(result.reconciliationRequired());
            assertFalse(result.content().contains("secret provider detail"));
            verifyNoInteractions(remote);
        }
    }

    @Test
    void uncertainWriteAfterRemoteCallStillRequiresReconciliation() {
        McpRemoteClient remote = mock(McpRemoteClient.class);
        when(remote.listTools(CONNECTION, "user-token")).thenReturn(List.of(
                new McpToolDescriptor("private_note_write", "Write a note", Map.of(), Map.of(), false)));
        when(remote.call(CONNECTION, "user-token", "private_note_write", Map.of(), "operation-1"))
                .thenThrow(new McpRemoteFailure(McpRemoteFailure.Kind.RESULT_UNKNOWN));
        var gateway = gateway(remote, (user, connection) -> "user-token");

        var result = gateway.execute(execution(), run(), ToolPreparation.allowed(CAPABILITY));

        assertEquals("TOOL_RESULT_UNKNOWN", result.errorCode());
        assertTrue(result.reconciliationRequired());
        verify(remote).call(CONNECTION, "user-token", "private_note_write", Map.of(), "operation-1");
    }

    private static DefaultToolExecutionGatewayService gateway(McpRemoteClient remote,
                                                              McpUserTokenProvider tokens) {
        McpCatalogRepository catalog = mock(McpCatalogRepository.class);
        when(catalog.findSource(42)).thenReturn(Optional.of(SOURCE));
        when(catalog.findConnection(1)).thenReturn(Optional.of(CONNECTION));
        AgentDefinitionRepository definitions = mock(AgentDefinitionRepository.class);
        when(definitions.isCapabilityEnabled(CAPABILITY.capabilityCode(), CAPABILITY.revision()))
                .thenReturn(true);
        ToolExecutionUserDirectory users = mock(ToolExecutionUserDirectory.class);
        when(users.isUserActive(USER.value())).thenReturn(true);
        CapabilityExecutorRegistry executors = mock(CapabilityExecutorRegistry.class);
        when(executors.resolve(CAPABILITY))
                .thenReturn(new McpCapabilityExecutor(catalog, remote, tokens));
        return new DefaultToolExecutionGatewayService(definitions, mock(HarnessRunSpecRepository.class),
                users, mock(ToolResourcePolicy.class), (user, revision) -> ResolvedCredential.none(),
                executors, catalog, null, null);
    }

    private static AgentRun run() {
        return new AgentRun(RUN_ID, new SessionId("session-1"), USER, 2, 3,
                "request-1", "digest", RunState.WAITING_TOOL, Instant.EPOCH, null, null);
    }

    private static PlatformToolExecution execution() {
        return new PlatformToolExecution("execution-1", RUN_ID, "use-1", 42, "note_write",
                "{}", "digest", ToolExecutionState.EXECUTING, "operation-1", null, null,
                null, null, null, Instant.EPOCH, Instant.EPOCH);
    }
}
