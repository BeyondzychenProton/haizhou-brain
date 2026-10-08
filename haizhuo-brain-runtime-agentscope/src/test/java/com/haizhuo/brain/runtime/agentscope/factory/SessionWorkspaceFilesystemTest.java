package com.haizhuo.brain.runtime.agentscope.factory;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionWorkspaceFilesystemTest {

    private final RuntimeContext context = RuntimeContext.builder().userId("42").sessionId("session-1").build();

    @Test
    void delegatesApprovedSessionReadAndRunFileWrite() {
        RecordingFilesystem nativeFilesystem = new RecordingFilesystem();
        SessionWorkspaceFilesystem guarded = new SessionWorkspaceFilesystem(nativeFilesystem);

        guarded.read(context, "/knowledge/guide.md", 0, 200);
        guarded.read(context, "knowledge/guide.md", 0, 200);
        guarded.write(context, "/outputs/result.md", "result");

        org.junit.jupiter.api.Assertions.assertEquals(3, nativeFilesystem.calls);
        org.junit.jupiter.api.Assertions.assertEquals("/outputs/result.md", nativeFilesystem.lastPath);
    }

    @Test
    void rejectsLocalFallbackAndPublishedAssetWritesBeforeDelegate() {
        RecordingFilesystem nativeFilesystem = new RecordingFilesystem();
        SessionWorkspaceFilesystem guarded = new SessionWorkspaceFilesystem(nativeFilesystem);

        assertThrows(SecurityException.class, () -> guarded.read(context, "/worker-secrets/token.txt", 0, 20));
        assertThrows(SecurityException.class, () -> guarded.write(context, "/skills/sales/SKILL.md", "override"));
        assertThrows(IllegalArgumentException.class, () -> guarded.read(context, "/knowledge/../secret", 0, 20));

        org.junit.jupiter.api.Assertions.assertEquals(0, nativeFilesystem.calls);
    }

    @Test
    void rejectsMissingSessionIdentityInsteadOfUsingRemoteDefaultNamespace() {
        RecordingFilesystem nativeFilesystem = new RecordingFilesystem();
        SessionWorkspaceFilesystem guarded = new SessionWorkspaceFilesystem(nativeFilesystem);

        assertThrows(SecurityException.class,
                () -> guarded.read(RuntimeContext.empty(), "/knowledge/guide.md", 0, 20));

        org.junit.jupiter.api.Assertions.assertEquals(0, nativeFilesystem.calls);
    }

    @Test
    void nativeRemoteRoutePersistsPlanBySessionAndReadsFrozenLocalSkill() throws IOException {
        Path workspace = Path.of(System.getProperty("user.dir"), "target", "session-workspace-tests",
                UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(workspace.resolve("skills/sales"));
        Files.writeString(workspace.resolve("AGENTS.md"), "employee instructions");
        Files.writeString(workspace.resolve("skills/sales/SKILL.md"), "frozen skill content");
        try {
            RemoteFilesystemSpec spec = new RemoteFilesystemSpec(new InMemoryStore())
                    .isolationScope(IsolationScope.SESSION).addSharedPrefix("outputs/");
            AbstractFilesystem remote = spec.toFilesystem(workspace, "employee-version-3", context -> List.of("local"));
            SessionWorkspaceFilesystem guarded = new SessionWorkspaceFilesystem(remote);
            RuntimeContext firstSession = RuntimeContext.builder().userId("42").sessionId("session-a").build();
            RuntimeContext secondSession = RuntimeContext.builder().userId("42").sessionId("session-b").build();

            WriteResult write = guarded.write(firstSession, "/plans/draft.md", "plan A");
            org.junit.jupiter.api.Assertions.assertTrue(write.isSuccess(), write.error());
            var firstRead = guarded.read(firstSession, "/plans/draft.md", 0, 20);
            org.junit.jupiter.api.Assertions.assertTrue(firstRead.isSuccess(), firstRead.error());
            org.junit.jupiter.api.Assertions.assertEquals("plan A", firstRead.fileData().content());
            org.junit.jupiter.api.Assertions.assertFalse(guarded.exists(secondSession, "/plans/draft.md"),
                    "SESSION scope must keep the same key invisible to a different session");
            var skill = guarded.read(firstSession, "/skills/sales/SKILL.md", 0, 20);
            org.junit.jupiter.api.Assertions.assertTrue(skill.isSuccess(), skill.error());
            org.junit.jupiter.api.Assertions.assertEquals("frozen skill content", skill.fileData().content());
        } finally {
            try (var paths = Files.walk(workspace)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                });
            }
        }
    }

    private static final class RecordingFilesystem implements AbstractFilesystem {
        private int calls;
        private String lastPath;

        @Override public LsResult ls(RuntimeContext context, String path) { calls++; lastPath = path; return null; }
        @Override public ReadResult read(RuntimeContext context, String path, int offset, int limit) { calls++; lastPath = path; return null; }
        @Override public WriteResult write(RuntimeContext context, String path, String content) { calls++; lastPath = path; return null; }
        @Override public EditResult edit(RuntimeContext context, String path, String oldString, String newString, boolean replaceAll) { calls++; lastPath = path; return null; }
        @Override public GrepResult grep(RuntimeContext context, String pattern, String path, String glob) { calls++; lastPath = path; return null; }
        @Override public GlobResult glob(RuntimeContext context, String pattern, String path) { calls++; lastPath = path; return null; }
        @Override public List<FileUploadResponse> uploadFiles(RuntimeContext context, List<Map.Entry<String, byte[]>> files) { calls++; return List.of(); }
        @Override public List<FileDownloadResponse> downloadFiles(RuntimeContext context, List<String> paths) { calls++; return List.of(); }
        @Override public WriteResult delete(RuntimeContext context, String path) { calls++; lastPath = path; return null; }
        @Override public WriteResult move(RuntimeContext context, String from, String to) { calls++; lastPath = to; return null; }
        @Override public boolean exists(RuntimeContext context, String path) { calls++; lastPath = path; return false; }
    }
}
