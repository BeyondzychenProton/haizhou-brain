package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** 根据完整且属主可见的 ROOT_FINAL 结果生成用户请求的 Markdown 导出文件。 */
public final class RunArtifactService {
    private static final int SOURCE_RESULT_LIMIT = 1024 * 1024;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final SessionApplicationService sessions;
    private final AgentResultRepository results;
    private final RunArtifactIndex index;
    private final RunArtifactBlobStore blobs;
    private final boolean enabled;
    private final int maxBytes;
    private final Clock clock;

    public RunArtifactService(SessionApplicationService sessions, AgentResultRepository results,
                              RunArtifactIndex index, RunArtifactBlobStore blobs, boolean enabled,
                              int maxBytes, Clock clock) {
        this.sessions = sessions;
        this.results = results;
        this.index = index;
        this.blobs = blobs;
        this.enabled = enabled;
        this.maxBytes = Math.max(1, Math.min(maxBytes, SOURCE_RESULT_LIMIT));
        this.clock = clock;
    }

    public Submission createMarkdown(RunId runId, UserId owner, String requestId) {
        requireEnabled();
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches())
            throw new IllegalArgumentException("requestId must contain 1 to 128 safe characters");
        var run = ownedRun(runId, owner);
        if (run.state() != RunState.SUCCEEDED) throw new RunArtifactNotReadyException();
        AgentResult source = results.findRoot(runId, owner).orElseThrow(RunArtifactNotFoundException::new);
        if (!runId.equals(source.runId()) || !"ROOT_FINAL".equals(source.kind())
                || source.visibility() != EventVisibility.USER || source.legacySummary()
                || source.resultId() == null || source.body() == null
                || !("text/plain".equalsIgnoreCase(source.mediaType())
                || "text/markdown".equalsIgnoreCase(source.mediaType())
                || "text/x-markdown".equalsIgnoreCase(source.mediaType()))) {
            throw new RunArtifactNotReadyException();
        }

        byte[] content = source.body().getBytes(StandardCharsets.UTF_8);
        String sourceHash = CanonicalJson.sha256Hex(content);
        if (content.length > maxBytes) throw new RunArtifactTooLargeException();
        if (content.length != source.byteSize() || !sourceHash.equalsIgnoreCase(source.bodySha256()))
            throw new RunArtifactSourceIntegrityException();

        String requestDigest = digest(source.resultId() + "|" + sourceHash + "|text/markdown");
        String artifactId = UUID.randomUUID().toString();
        Instant createdAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        RunArtifact proposed = new RunArtifact(artifactId, runId, source.resultId(), owner, requestId,
                requestDigest, "运行结果", "text/markdown", content.length, sourceHash,
                artifactId, RunArtifact.State.STAGED, createdAt);
        RunArtifactIndex.StageResult staged = index.stage(proposed);
        RunArtifact artifact = staged.artifact();
        if (!requestDigest.equals(artifact.requestDigest())) throw new RunArtifactConflictException();
        if (artifact.state() == RunArtifact.State.AVAILABLE) {
            try {
                byte[] existing = blobs.read(artifact.artifactId()).orElse(null);
                if (existing != null && existing.length == artifact.byteSize()
                        && CanonicalJson.sha256Hex(existing).equalsIgnoreCase(artifact.sha256())) {
                    return new Submission(publicItem(artifact), !staged.created());
                }
                index.markUnavailable(artifact.artifactId(), owner);
            } catch (IOException | RunArtifactStorageUnavailableException failure) {
                throw new RunArtifactStorageUnavailableException();
            }
        }

        try {
            blobs.write(artifact.artifactId(), content);
            RunArtifact available = index.markAvailable(artifact.artifactId(), owner, sourceHash, content.length);
            return new Submission(publicItem(available), !staged.created());
        } catch (IOException | RunArtifactStorageUnavailableException failure) {
            throw new RunArtifactStorageUnavailableException();
        }
    }

    public ArtifactPage list(RunId runId, UserId owner, String cursor, int requestedLimit) {
        requireEnabled();
        ownedRun(runId, owner);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        RunArtifactIndex.Position position = decodeCursor(cursor);
        RunArtifactIndex.Page page = index.page(runId, owner, position, limit);
        String next = page.hasMore() && !page.items().isEmpty()
                ? encodeCursor(new RunArtifactIndex.Position(
                        page.items().get(page.items().size() - 1).createdAt(),
                        page.items().get(page.items().size() - 1).artifactId())) : null;
        return new ArtifactPage(page.items().stream().map(RunArtifactService::publicItem).toList(), next,
                page.hasMore());
    }

    public Download loadContent(String artifactId, UserId owner) {
        requireEnabled();
        RunArtifact artifact = index.find(artifactId, owner).orElseThrow(RunArtifactNotFoundException::new);
        if (artifact.state() != RunArtifact.State.AVAILABLE) throw new RunArtifactUnavailableException();
        try {
            byte[] content = blobs.read(artifact.artifactId()).orElse(null);
            if (content == null || content.length != artifact.byteSize()
                    || !CanonicalJson.sha256Hex(content).equalsIgnoreCase(artifact.sha256())) {
                index.markUnavailable(artifact.artifactId(), owner);
                throw new RunArtifactUnavailableException();
            }
            return new Download(publicItem(artifact), content);
        } catch (IOException | RunArtifactStorageUnavailableException failure) {
            throw new RunArtifactStorageUnavailableException();
        }
    }

    private com.haizhuo.brain.platform.run.AgentRun ownedRun(RunId runId, UserId owner) {
        try {
            return sessions.getRun(runId, owner);
        } catch (IllegalArgumentException notOwnedOrMissing) {
            throw new RunArtifactNotFoundException();
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new RunArtifactFeatureNotAvailableException();
    }

    private static ArtifactItem publicItem(RunArtifact item) {
        return new ArtifactItem(item.artifactId(), item.runId().value(), item.title(), item.mediaType(),
                item.byteSize(), item.sha256(), item.state().name(), item.createdAt());
    }

    private static RunArtifactIndex.Position decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 1024) throw new IllegalArgumentException("Invalid artifact cursor");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid artifact cursor");
            Instant createdAt = Instant.parse(parts[0]);
            UUID.fromString(parts[1]);
            return new RunArtifactIndex.Position(createdAt, parts[1]);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid artifact cursor", invalid);
        }
    }

    private static String encodeCursor(RunArtifactIndex.Position position) {
        String value = position.createdAt() + "|" + position.artifactId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public record ArtifactItem(String artifactId, String runId, String title, String mediaType, long byteSize,
                               String sha256, String state, Instant createdAt) { }
    public record ArtifactPage(List<ArtifactItem> items, String nextCursor, boolean hasMore) {
        public ArtifactPage { items = List.copyOf(items); }
    }
    public record Submission(ArtifactItem artifact, boolean replayed) { }
    public record Download(ArtifactItem artifact, byte[] content) {
        public Download { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

    public static class RunArtifactNotFoundException extends RuntimeException { }
    public static class RunArtifactNotReadyException extends RuntimeException { }
    public static class RunArtifactConflictException extends RuntimeException { }
    public static class RunArtifactUnavailableException extends RuntimeException { }
    public static class RunArtifactTooLargeException extends RuntimeException { }
    public static class RunArtifactSourceIntegrityException extends RuntimeException { }
    public static class RunArtifactFeatureNotAvailableException extends RuntimeException { }
    public static class RunArtifactStorageUnavailableException extends RuntimeException { }
}
