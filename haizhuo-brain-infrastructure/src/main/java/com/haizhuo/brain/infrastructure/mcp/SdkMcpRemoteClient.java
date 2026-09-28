package com.haizhuo.brain.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.platform.mcp.McpEndpointPolicy;
import com.haizhuo.brain.platform.mcp.McpRemoteClient;
import com.haizhuo.brain.platform.mcp.McpRemoteFailure;
import com.haizhuo.brain.platform.mcp.McpToolDescriptor;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对 AgentScope 2.0.3 所依赖的 MCP Java SDK 做薄适配。
 * AgentScope 的封装只暴露 tools/list 首页，因此分页直接使用同一底层 SDK；
 * 此处不重新实现 JSON-RPC 传输。
 */
@Component
public class SdkMcpRemoteClient implements McpRemoteClient {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private final ObjectMapper json;
    private final McpEndpointPolicy endpoints;

    public SdkMcpRemoteClient(ObjectMapper json, McpEndpointPolicy endpoints) {
        this.json = json;
        this.endpoints = endpoints;
    }

    @Override public List<McpToolDescriptor> listTools(McpConnection connection, String bearerToken) {
        try (McpSyncClient client = open(connection, bearerToken)) {
            List<McpToolDescriptor> found = new ArrayList<>();
            Set<String> cursors = new HashSet<>();
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                for (McpSchema.Tool tool : page.tools()) {
                    Map<String, Object> schema = json.convertValue(tool.inputSchema(), OBJECT);
                    found.add(new McpToolDescriptor(tool.name(), tool.description(), schema,
                            tool.outputSchema(), tool.annotations() != null
                                    && Boolean.TRUE.equals(tool.annotations().readOnlyHint())));
                }
                cursor = page.nextCursor();
                if (cursor != null && (!cursors.add(cursor) || cursors.size() > 100 || found.size() > 1000))
                    throw new McpRemoteFailure(McpRemoteFailure.Kind.UNAVAILABLE);
            } while (cursor != null && !cursor.isBlank());
            return List.copyOf(found);
        } catch (McpRemoteFailure error) {
            throw error;
        } catch (RuntimeException error) {
            throw classify(error, false);
        }
    }

    @Override public McpCallResult call(McpConnection connection, String bearerToken, String remoteName,
                                        Map<String, Object> arguments, String operationKey) {
        McpSyncClient opened;
        try { opened = open(connection, bearerToken); }
        catch (McpRemoteFailure error) { throw error; }
        catch (RuntimeException error) { throw classify(error, false); }
        try (McpSyncClient client = opened) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    remoteName, arguments, Map.of("operationId", operationKey)));
            if (Boolean.TRUE.equals(result.isError()) && result.structuredContent() instanceof Map<?, ?> details
                    && "PERMISSION_DENIED".equals(details.get("code"))
                    && "NOT_EXECUTED".equals(details.get("effect")))
                throw new McpRemoteFailure(McpRemoteFailure.Kind.FORBIDDEN);
            String content = result.content().stream()
                    .filter(McpSchema.TextContent.class::isInstance)
                    .map(McpSchema.TextContent.class::cast)
                    .map(McpSchema.TextContent::text)
                    .limit(4).reduce((a, b) -> a + "\n" + b).orElse("");
            if (content.length() > 2000) content = content.substring(0, 2000);
            return new McpCallResult(Boolean.TRUE.equals(result.isError()), content);
        } catch (McpRemoteFailure error) {
            throw error;
        } catch (RuntimeException error) {
            throw classify(error, true);
        }
    }

    private McpSyncClient open(McpConnection connection, String token) {
        endpoints.validate(URI.create(connection.endpoint()));
        if (token == null || token.isBlank()) throw new McpRemoteFailure(McpRemoteFailure.Kind.AUTHENTICATION);
        var transport = HttpClientStreamableHttpTransport.builder(connection.endpoint())
                .jsonMapper(io.modelcontextprotocol.json.McpJsonMapper.getDefault())
                .connectTimeout(Duration.ofSeconds(5))
                .openConnectionOnStartup(false)
                .supportedProtocolVersions(List.of("2025-06-18", "2025-03-26", "2024-11-05"))
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(5)).build();
        try {
            client.initialize();
            return client;
        } catch (RuntimeException error) {
            client.close();
            throw error;
        }
    }

    private static McpRemoteFailure classify(RuntimeException error, boolean sentCall) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.matches("(?is).*\\b(?:status\\s+code|status|code|http)\\s*[:= ]\\s*401\\b.*"))
                return new McpRemoteFailure(McpRemoteFailure.Kind.AUTHENTICATION);
            if (message != null && message.matches("(?is).*\\b(?:status\\s+code|status|code|http)\\s*[:= ]\\s*403\\b.*"))
                return new McpRemoteFailure(McpRemoteFailure.Kind.FORBIDDEN);
            if (!sentCall && message != null && message.contains("AggregateResponseEvent[")
                    && message.matches("(?is).*\\b(?:unauthorized|invalid_token)\\b.*"))
                return new McpRemoteFailure(McpRemoteFailure.Kind.AUTHENTICATION);
            current = current.getCause();
        }
        return new McpRemoteFailure(sentCall ? McpRemoteFailure.Kind.RESULT_UNKNOWN
                : McpRemoteFailure.Kind.UNAVAILABLE);
    }
}
