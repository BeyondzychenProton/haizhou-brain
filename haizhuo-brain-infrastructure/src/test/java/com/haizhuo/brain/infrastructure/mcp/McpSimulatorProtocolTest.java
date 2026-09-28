package com.haizhuo.brain.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.mcp.SdkMcpRemoteClient;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.platform.mcp.McpEndpointPolicy;
import com.haizhuo.brain.platform.mcp.McpRemoteFailure;
import com.haizhuo.brain.testsupport.mcp.SimulatedMcpServer;
import java.time.Clock;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpSimulatorProtocolTest {
    private static final String SECRET = "integration-test-secret-at-least-32-characters";

    @Test
    void pagedListingAndResourceAuthorizationArePerEndUser() throws Exception {
        try (var server = new SimulatedMcpServer(0, SECRET, "demo-mcp", Clock.systemUTC())) {
            server.start();
            var connection = new McpConnection(1, "demo-mcp", "Demo", "http://127.0.0.1:"
                    + server.port() + "/mcp", 1, true);
            var client = new SdkMcpRemoteClient(new ObjectMapper(), new McpEndpointPolicy(Set.of(), true));
            String writer = server.issueToken("+15550000001");
            String reader = server.issueToken("+15550000002");

            assertEquals(3, client.listTools(connection, writer).size());
            assertEquals(1, client.listTools(connection, reader).size());
            assertEquals("reader's private note", client.call(connection, reader,
                    "private_note_read", Map.of("noteId", "note-b"), "read-1").content());
            McpRemoteFailure denied = assertThrows(McpRemoteFailure.class, () -> client.call(connection, reader,
                    "private_note_read", Map.of("noteId", "note-a"), "read-2"));
            assertEquals(McpRemoteFailure.Kind.FORBIDDEN, denied.kind());

            assertEquals("saved:write-1", client.call(connection, writer, "private_note_write",
                    Map.of("noteId", "note-a", "text", "changed"), "write-1").content());
            assertEquals("saved:write-1", client.call(connection, writer, "private_note_write",
                    Map.of("noteId", "note-a", "text", "changed"), "write-1").content());
            assertEquals("changed", client.call(connection, writer, "private_note_read",
                    Map.of("noteId", "note-a"), "read-3").content());
            assertEquals("saved:write-1", client.call(connection, writer, "private_note_write_status",
                    Map.of("operationId", "write-1"), "status-1").content());
        }
    }

    @Test
    void configuredDemoUsersKeepTheirOwnToolsAndNotes() throws Exception {
        try (var server = new SimulatedMcpServer(0, SECRET, "demo-mcp", Clock.systemUTC(),
                "+15551110001", "+15551110002")) {
            server.start();
            var connection = new McpConnection(1, "demo-mcp", "Demo", "http://127.0.0.1:"
                    + server.port() + "/mcp", 1, true);
            var client = new SdkMcpRemoteClient(new ObjectMapper(), new McpEndpointPolicy(Set.of(), true));
            String writer = server.issueToken("+15551110001");
            String reader = server.issueToken("+15551110002");

            assertEquals(3, client.listTools(connection, writer).size());
            assertEquals(1, client.listTools(connection, reader).size());
            assertEquals("writer's private note", client.call(connection, writer,
                    "private_note_read", Map.of("noteId", "note-a"), "read-writer").content());
            assertEquals("reader's private note", client.call(connection, reader,
                    "private_note_read", Map.of("noteId", "note-b"), "read-reader").content());
            assertEquals(McpRemoteFailure.Kind.FORBIDDEN,
                    assertThrows(McpRemoteFailure.class, () -> client.call(connection, reader,
                            "private_note_read", Map.of("noteId", "note-a"), "read-other")).kind());
        }
    }

    @Test
    void forgedExpiredAndWrongAudienceTokensAreRejected() throws Exception {
        try (var server = new SimulatedMcpServer(0, SECRET, "demo-mcp", Clock.systemUTC());
             var otherAudience = new SimulatedMcpServer(0, SECRET, "wrong-audience", Clock.systemUTC())) {
            server.start();
            String valid = server.issueToken("+15550000001");
            assertEquals(401, initialize(server.port(), null));
            assertEquals(401, initialize(server.port(), valid + "tampered"));
            assertEquals(401, initialize(server.port(), server.issueToken("+15550000001",
                    Instant.now().minusSeconds(10))));
            assertEquals(401, initialize(server.port(), otherAudience.issueToken("+15550000001")));
            assertEquals(200, initialize(server.port(), valid));
            var connection = new McpConnection(1, "demo-mcp", "Demo", "http://127.0.0.1:"
                    + server.port() + "/mcp", 1, true);
            var client = new SdkMcpRemoteClient(new ObjectMapper(), new McpEndpointPolicy(Set.of(), true));
            var expired = server.issueToken("+15550000001", Instant.now().minusSeconds(10));
            assertEquals(McpRemoteFailure.Kind.AUTHENTICATION,
                    assertThrows(McpRemoteFailure.class, () -> client.listTools(connection, expired)).kind());
        }
    }

    private static int initialize(int port, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
