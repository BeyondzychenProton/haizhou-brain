package com.haizhuo.brain.runtime.agentscope.team;

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

/** Binds AgentScope WorkspaceMessageBus's empty RuntimeContext to one isolated Team filesystem root. */
public final class TeamBusFilesystem implements AbstractFilesystem {
    private final AbstractFilesystem delegate;
    private final RuntimeContext trustedContext;
    private final String allowedRoot;

    public TeamBusFilesystem(AbstractFilesystem delegate, RuntimeContext trustedContext, String allowedRoot) {
        this.delegate = Objects.requireNonNull(delegate);
        this.trustedContext = Objects.requireNonNull(trustedContext);
        this.allowedRoot = normalizeRoot(allowedRoot);
        if (trustedContext.getUserId() == null || trustedContext.getUserId().isBlank()
                || trustedContext.getSessionId() == null || trustedContext.getSessionId().isBlank())
            throw new IllegalArgumentException("trusted Team bus user and session are required");
    }

    @Override public LsResult ls(RuntimeContext ignored, String path) {
        return delegate.ls(trustedContext, requirePath(path));
    }

    @Override public ReadResult read(RuntimeContext ignored, String path, int offset, int limit) {
        return delegate.read(trustedContext, requirePath(path), offset, limit);
    }

    @Override public WriteResult write(RuntimeContext ignored, String path, String content) {
        return delegate.write(trustedContext, requirePath(path), content);
    }

    @Override public WriteResult delete(RuntimeContext ignored, String path) {
        return delegate.delete(trustedContext, requirePath(path));
    }

    @Override public boolean exists(RuntimeContext ignored, String path) {
        return delegate.exists(trustedContext, requirePath(path));
    }

    @Override public EditResult edit(RuntimeContext context, String filePath, String oldString, String newString,
                                     boolean replaceAll) {
        throw new UnsupportedOperationException("Team bus filesystem is not an editing surface");
    }

    @Override public GrepResult grep(RuntimeContext context, String pattern, String directory, String glob) {
        throw new UnsupportedOperationException("Team bus filesystem is not a search surface");
    }

    @Override public GlobResult glob(RuntimeContext context, String pattern, String directory) {
        throw new UnsupportedOperationException("Team bus filesystem is not a glob surface");
    }

    @Override public List<FileUploadResponse> uploadFiles(RuntimeContext context,
                                                           List<Map.Entry<String, byte[]>> files) {
        throw new UnsupportedOperationException("Team bus filesystem does not accept uploads");
    }

    @Override public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) {
        throw new UnsupportedOperationException("Team bus filesystem does not serve downloads");
    }

    @Override public WriteResult move(RuntimeContext context, String fromPath, String toPath) {
        throw new UnsupportedOperationException("Team bus filesystem does not move files");
    }

    private String requirePath(String value) {
        AbstractFilesystem.validatePath(value);
        String portable = value == null ? "" : value.strip().replace('\\', '/');
        while (portable.startsWith("/")) portable = portable.substring(1);
        if (portable.indexOf('\0') >= 0 || portable.indexOf(':') >= 0
                || portable.equals("..") || portable.startsWith("../") || portable.contains("/../"))
            throw new SecurityException("Team bus path is outside its execution namespace");
        if (!portable.equals(allowedRoot) && !portable.startsWith(allowedRoot + "/"))
            throw new SecurityException("Team bus path is outside its execution namespace");
        return "/" + portable;
    }

    private static String normalizeRoot(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Team bus root is required");
        String root = value.strip().replace('\\', '/');
        while (root.startsWith("/")) root = root.substring(1);
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (root.isBlank() || root.indexOf(':') >= 0 || root.contains(".."))
            throw new IllegalArgumentException("invalid Team bus root");
        return root;
    }
}
