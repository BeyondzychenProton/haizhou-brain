package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Session-scoped native filesystem view for immutable AgentScope-generated dyn-* declarations. */
public final class DynamicSubagentFilesystem implements AbstractFilesystem {
    private static final int MAX_SPEC_CHARS = 32_768;
    private final AbstractFilesystem delegate;

    public DynamicSubagentFilesystem(AbstractFilesystem delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override public LsResult ls(RuntimeContext context, String path) {
        requireSession(context);
        if (!"/subagents".equals(path(path))) throw denied("dynamic agents may only be listed in subagents/");
        return delegate.ls(context, "/subagents");
    }

    @Override public ReadResult read(RuntimeContext context, String filePath, int offset, int limit) {
        requireSession(context);
        String normalized = specPath(filePath);
        ReadResult result = delegate.read(context, normalized, offset, limit);
        if (result.isSuccess() && result.fileData() != null && result.fileData().content().length() > MAX_SPEC_CHARS)
            throw denied("dynamic agent definition exceeds the size limit");
        return result;
    }

    @Override public WriteResult write(RuntimeContext context, String filePath, String content) {
        requireSession(context);
        String normalized = specPath(filePath);
        if (content == null || content.length() > MAX_SPEC_CHARS)
            throw denied("dynamic agent definition exceeds the size limit");
        if (delegate.exists(context, normalized)) throw denied("dynamic agent definitions are immutable within a Session");
        return delegate.write(context, normalized, content);
    }

    @Override public EditResult edit(RuntimeContext context, String filePath, String oldString,
                                     String newString, boolean replaceAll) {
        throw denied("dynamic agent definitions cannot be edited after creation");
    }

    @Override public GrepResult grep(RuntimeContext context, String pattern, String directory, String glob) {
        requireSession(context);
        if (!"/subagents".equals(path(directory))) throw denied("dynamic agent search is limited to subagents/");
        return delegate.grep(context, pattern, "/subagents", "dyn-*.md");
    }

    @Override public GlobResult glob(RuntimeContext context, String pattern, String directory) {
        requireSession(context);
        if (!"/subagents".equals(path(directory))) throw denied("dynamic agent lookup is limited to subagents/");
        return delegate.glob(context, "dyn-*.md", "/subagents");
    }

    @Override public List<FileUploadResponse> uploadFiles(RuntimeContext context,
                                                           List<Map.Entry<String, byte[]>> files) {
        throw denied("dynamic agent definitions must be created through AgentScope agent_generate");
    }

    @Override public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        requireSession(context);
        for (String file : paths) specPath(file);
        return delegate.downloadFiles(context, paths);
    }

    @Override public WriteResult delete(RuntimeContext context, String target) {
        throw denied("dynamic agent definitions cannot be deleted during a Run");
    }

    @Override public WriteResult move(RuntimeContext context, String fromPath, String toPath) {
        throw denied("dynamic agent definitions cannot be moved");
    }

    @Override public boolean exists(RuntimeContext context, String target) {
        requireSession(context);
        return delegate.exists(context, specPath(target));
    }

    public String definitionHash(RuntimeContext context, String roleId) {
        ReadResult result = read(context, "/subagents/" + roleId + ".md", 0, MAX_SPEC_CHARS + 1);
        if (!result.isSuccess() || result.fileData() == null || result.fileData().content().isBlank())
            throw denied("runtime-generated expert definition is missing from this Session");
        return CanonicalJson.sha256Hex(result.fileData().content().getBytes(StandardCharsets.UTF_8));
    }

    private static String specPath(String value) {
        String normalized = path(value);
        String name = normalized.substring("/subagents/".length());
        if (!name.matches("dyn-[a-z0-9][a-z0-9-]{0,58}\\.md"))
            throw denied("only dyn-* markdown definitions are available");
        return normalized;
    }

    private static String path(String value) {
        AbstractFilesystem.validatePath(value);
        String portable = value.strip().replace('\\', '/');
        if (portable.startsWith("//") || portable.indexOf('\0') >= 0 || portable.indexOf(':') >= 0)
            throw denied("dynamic expert path must stay inside the Session workspace");
        if (!portable.startsWith("/")) portable = "/" + portable;
        String[] segments = portable.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment)) continue;
            if ("..".equals(segment)) throw denied("parent traversal is not allowed");
            normalized.append('/').append(segment);
        }
        return normalized.toString();
    }

    private static void requireSession(RuntimeContext context) {
        if (context == null || context.getSessionId() == null || context.getSessionId().isBlank()
                || context.getUserId() == null || context.getUserId().isBlank())
            throw denied("trusted user and Session identity are required");
    }

    private static SecurityException denied(String message) { return new SecurityException(message); }
}
