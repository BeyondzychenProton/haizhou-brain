package com.haizhuo.brain.infrastructure.mcp;

import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.mcp.McpCatalogRepository;
import com.haizhuo.brain.platform.mcp.McpRemoteClient;
import com.haizhuo.brain.platform.mcp.McpRemoteFailure;
import com.haizhuo.brain.platform.mcp.McpUserTokenProvider;
import com.haizhuo.brain.platform.tool.CapabilityExecutionContext;
import com.haizhuo.brain.platform.tool.CapabilityExecutor;
import com.haizhuo.brain.platform.tool.ToolExecutionResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 可信 MCP Server 在每次调用时负责资源级判权。 */
@Component
public class McpCapabilityExecutor implements CapabilityExecutor {
    private final McpCatalogRepository catalog;
    private final McpRemoteClient remote;
    private final McpUserTokenProvider tokens;

    public McpCapabilityExecutor(McpCatalogRepository catalog, McpRemoteClient remote,
                                 McpUserTokenProvider tokens) {
        this.catalog = catalog;
        this.remote = remote;
        this.tokens = tokens;
    }

    @Override public String implementationKey() { return "mcp-trusted-v1"; }

    @Override public boolean supports(CapabilityCatalogEntry entry) {
        if (!implementationKey().equals(entry.implementationKey())) return false;
        var source = catalog.findSource(entry.capabilityRevisionId());
        if (source.isEmpty()) return false;
        return catalog.findConnection(source.get().connectionId())
                .filter(connection -> connection.enabled()
                        && connection.revision() == source.get().connectionRevision()).isPresent();
    }

    @Override public ToolExecutionResult execute(CapabilityExecutionContext context,
                                                  Map<String, Object> arguments) {
        var source = catalog.findSource(context.capability().capabilityRevisionId()).orElseThrow();
        var connection = catalog.findConnection(source.connectionId()).orElseThrow();
        if (!connection.enabled() || connection.revision() != source.connectionRevision())
            return ToolExecutionResult.failed("MCP_SOURCE_UNAVAILABLE", "该工具当前不可用。");
        String token;
        try {
            token = tokens.tokenFor(context.userId(), connection);
        } catch (RuntimeException error) {
            return ToolExecutionResult.failed("CREDENTIAL_UNAVAILABLE", "该操作缺少可用的执行凭据。");
        }
        if (token == null || token.isBlank())
            return ToolExecutionResult.failed("CREDENTIAL_UNAVAILABLE", "该操作缺少可用的执行凭据。");
        try {
            var observed = remote.listTools(connection, token).stream()
                    .filter(tool -> tool.name().equals(source.remoteName())).findFirst();
            if (observed.isEmpty() || !observed.get().inputSchema().equals(context.capability().inputSchema())
                    || !observed.get().outputSchema().equals(source.outputSchema())
                    || (source.readOnly() && !observed.get().readOnlyHint()))
                return ToolExecutionResult.failed("MCP_TOOL_CHANGED", "远端工具已变化，需要重新审核。");
        } catch (McpRemoteFailure error) {
            return switch (error.kind()) {
                case AUTHENTICATION -> ToolExecutionResult.failed("MCP_AUTH_EXPIRED", "登录凭证已失效。");
                case FORBIDDEN -> ToolExecutionResult.failed("MCP_PERMISSION_DENIED", "该工具未获授权。");
                case UNAVAILABLE, RESULT_UNKNOWN -> ToolExecutionResult.failed("MCP_UNAVAILABLE", "远端工具暂不可用。");
            };
        }
        try {
            var result = remote.call(connection, token,
                    source.remoteName(), arguments, context.execution().idempotencyKey());
            if (result.error()) return source.readOnly()
                    ? ToolExecutionResult.failed("MCP_TOOL_ERROR", "远端工具未能完成查询。")
                    : ToolExecutionResult.unknown("写操作结果尚不明确，请核查后再操作。");
            return ToolExecutionResult.succeeded(result.content(), context.execution().idempotencyKey());
        } catch (McpRemoteFailure error) {
            return switch (error.kind()) {
                case AUTHENTICATION -> ToolExecutionResult.failed("MCP_AUTH_EXPIRED", "登录凭证已失效。");
                case FORBIDDEN -> ToolExecutionResult.failed("MCP_PERMISSION_DENIED", "该资源未获授权。");
                case UNAVAILABLE, RESULT_UNKNOWN -> source.readOnly()
                        ? ToolExecutionResult.failed("MCP_UNAVAILABLE", "远端工具暂不可用。")
                        : ToolExecutionResult.unknown("写操作结果尚不明确，请核查后再操作。");
            };
        }
    }
}
