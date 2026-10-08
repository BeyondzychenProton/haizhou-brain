package com.haizhuo.brain.runtime.agentscope.factory;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Narrow path policy around AgentScope's native filesystem. The delegate retains all routing and
 * persistence behavior; this class only prevents the RemoteFilesystemSpec local fallback from
 * exposing arbitrary worker paths and keeps published skill/knowledge assets read-only.
 */
public final class SessionWorkspaceFilesystem implements AbstractFilesystem {

    private final AbstractFilesystem delegate;

    public SessionWorkspaceFilesystem(AbstractFilesystem delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public LsResult ls(RuntimeContext context, String path) {
        requireSession(context);
        String normalized = path(path);
        if (!"/".equals(normalized)) requireReadable(normalized);
        return delegate.ls(context, normalized);
    }

    @Override
    public ReadResult read(RuntimeContext context, String filePath, int offset, int limit) {
        requireSession(context);
        String normalized = path(filePath);
        requireReadable(normalized);
        return delegate.read(context, normalized, offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext context, String filePath, String content) {
        requireSession(context);
        String normalized = path(filePath);
        requireWritable(normalized);
        return delegate.write(context, normalized, content);
    }

    @Override
    public EditResult edit(RuntimeContext context, String filePath, String oldString, String newString,
                           boolean replaceAll) {
        requireSession(context);
        String normalized = path(filePath);
        requireWritable(normalized);
        return delegate.edit(context, normalized, oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(RuntimeContext context, String pattern, String directory, String glob) {
        requireSession(context);
        String normalized = path(directory);
        requireReadable(normalized);
        rejectTraversalPattern(glob);
        return delegate.grep(context, pattern, normalized, glob);
    }

    @Override
    public GlobResult glob(RuntimeContext context, String pattern, String directory) {
        requireSession(context);
        String normalized = path(directory);
        rejectTraversalPattern(pattern);
        if ("/".equals(normalized)) {
            if (!scopedGlob(pattern)) throw denied("glob must name an approved workspace root");
        } else {
            requireReadable(normalized);
        }
        return delegate.glob(context, pattern, normalized);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
        requireSession(context);
        for (Map.Entry<String, byte[]> file : Objects.requireNonNull(files, "files")) {
            requireWritable(path(file.getKey()));
        }
        return delegate.uploadFiles(context, files);
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        requireSession(context);
        for (String file : Objects.requireNonNull(paths, "paths")) requireReadable(path(file));
        return delegate.downloadFiles(context, paths);
    }

    @Override
    public WriteResult delete(RuntimeContext context, String target) {
        requireSession(context);
        String normalized = path(target);
        requireWritable(normalized);
        return delegate.delete(context, normalized);
    }

    @Override
    public WriteResult move(RuntimeContext context, String fromPath, String toPath) {
        requireSession(context);
        String from = path(fromPath);
        String to = path(toPath);
        requireWritable(from);
        requireWritable(to);
        return delegate.move(context, from, to);
    }

    @Override
    public boolean exists(RuntimeContext context, String target) {
        requireSession(context);
        String normalized = path(target);
        requireReadable(normalized);
        return delegate.exists(context, normalized);
    }

    private static void requireSession(RuntimeContext context) {
        if (context == null || context.getSessionId() == null || context.getSessionId().isBlank()
                || context.getUserId() == null || context.getUserId().isBlank()) {
            throw denied("trusted userId and sessionId are required for workspace access");
        }
    }

    private static String path(String value) {
        AbstractFilesystem.validatePath(value);
        String portable = value.strip().replace('\\', '/');
        if (portable.startsWith("//") || portable.indexOf('\0') >= 0)
            throw denied("workspace paths must stay inside the virtual workspace");
        // AgentScope's WorkspaceManager uses relative names for managed files such as AGENTS.md;
        // convert those at the adapter boundary, then apply the same root allowlist.
        if (!portable.startsWith("/")) portable = "/" + portable;
        String[] segments = portable.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment)) continue;
            if (segment.indexOf(':') >= 0) throw denied("drive-qualified paths are not allowed");
            normalized.append('/').append(segment);
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }

    private static void requireReadable(String path) {
        if ("/AGENTS.md".equals(path) || "/MEMORY.md".equals(path)
                || under(path, "skills") || under(path, "knowledge")
                || under(path, "plans") || under(path, "outputs")) return;
        throw denied("workspace path is outside approved read roots");
    }

    private static void requireWritable(String path) {
        if (under(path, "plans") || under(path, "outputs")) return;
        throw denied("only plans/ and outputs/ are writable");
    }

    private static boolean under(String path, String root) {
        return path.equals("/" + root) || path.startsWith("/" + root + "/");
    }

    private static boolean scopedGlob(String pattern) {
        if (pattern == null) return false;
        String normalized = pattern.replace('\\', '/');
        for (String root : List.of("skills", "knowledge", "plans", "outputs")) {
            if (normalized.startsWith("/" + root + "/") || normalized.startsWith(root + "/")) return true;
        }
        return false;
    }

    private static void rejectTraversalPattern(String pattern) {
        if (pattern == null) return;
        AbstractFilesystem.validatePath(pattern);
    }

    private static SecurityException denied(String message) {
        return new SecurityException(message);
    }
}
