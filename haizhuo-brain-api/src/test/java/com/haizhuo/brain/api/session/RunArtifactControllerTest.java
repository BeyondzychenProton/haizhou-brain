package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.AgentResult;
import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.RunArtifact;
import com.haizhuo.brain.platform.run.RunArtifactBlobStore;
import com.haizhuo.brain.platform.run.RunArtifactIndex;
import com.haizhuo.brain.platform.run.RunArtifactService;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunArtifactControllerTest {
    private static final UserId OWNER = new UserId(7);
    private static final RunId RUN = new RunId("run-a");
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private static final String BODY = "<script>payload()</script>\n# answer";

    @Test
    void exportsMaliciousMarkdownOnlyAsPrivateAttachmentAndSupportsSingleRanges() {
        MemoryIndex index = new MemoryIndex();
        MemoryBlobs blobs = new MemoryBlobs();
        RunArtifactService service = service(index, blobs);
        RunArtifactController controller = new RunArtifactController(service);
        AuthenticatedUser owner = new AuthenticatedUser(OWNER, Set.of(PlatformRole.USER), 1, false);

        var created = controller.createMarkdown(owner, RUN.value(),
                new RunArtifactController.CreateRequest("export-1")).block();
        var replay = controller.createMarkdown(owner, RUN.value(),
                new RunArtifactController.CreateRequest("export-1")).block();
        var page = controller.list(owner, RUN.value(), null, 20).block();
        int contentSize = BODY.getBytes(StandardCharsets.UTF_8).length;
        var range = controller.content(owner, created.getBody().artifactId(), "bytes=0-5").block();
        var suffixRange = controller.content(owner, created.getBody().artifactId(), "bytes=-6").block();
        var lastByteRange = controller.content(owner, created.getBody().artifactId(),
                "bytes=" + (contentSize - 1) + "-").block();
        var clampedRange = controller.content(owner, created.getBody().artifactId(),
                "bytes=0-" + (contentSize + 10)).block();
        var invalidRange = controller.content(owner, created.getBody().artifactId(), "bytes=30-4").block();

        assertEquals(201, created.getStatusCode().value());
        assertEquals(200, replay.getStatusCode().value());
        assertEquals(created.getBody().artifactId(), replay.getBody().artifactId());
        assertEquals(1, page.getBody().items().size());
        assertTrue(page.getHeaders().getFirst("Cache-Control").contains("no-store"));
        assertEquals(206, range.getStatusCode().value());
        assertArrayEquals("<scrip".getBytes(StandardCharsets.UTF_8), (byte[]) range.getBody());
        assertEquals("bytes 0-5/" + BODY.getBytes(StandardCharsets.UTF_8).length,
                range.getHeaders().getFirst("Content-Range"));
        assertEquals(206, suffixRange.getStatusCode().value());
        assertArrayEquals("answer".getBytes(StandardCharsets.UTF_8), (byte[]) suffixRange.getBody());
        assertEquals("bytes " + (contentSize - 6) + "-" + (contentSize - 1) + "/" + contentSize,
                suffixRange.getHeaders().getFirst("Content-Range"));
        assertEquals(206, lastByteRange.getStatusCode().value());
        assertArrayEquals(new byte[] { BODY.getBytes(StandardCharsets.UTF_8)[contentSize - 1] },
                (byte[]) lastByteRange.getBody());
        assertEquals(206, clampedRange.getStatusCode().value());
        assertEquals("bytes 0-" + (contentSize - 1) + "/" + contentSize,
                clampedRange.getHeaders().getFirst("Content-Range"));
        assertTrue(range.getHeaders().getFirst("Content-Disposition").startsWith("attachment;"));
        assertEquals("nosniff", range.getHeaders().getFirst("X-Content-Type-Options"));
        assertTrue(range.getHeaders().getFirst("Cache-Control").contains("no-store"));
        assertTrue(range.getHeaders().getContentType().toString().startsWith("text/markdown"));
        assertEquals(416, invalidRange.getStatusCode().value());
        assertEquals("bytes */" + contentSize,
                invalidRange.getHeaders().getFirst("Content-Range"));
    }

    private static RunArtifactService service(MemoryIndex index, MemoryBlobs blobs) {
        SessionApplicationService sessions = new SessionApplicationService(null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override public AgentRun getRun(RunId runId, UserId owner) {
                if (!OWNER.equals(owner)) throw new IllegalArgumentException("not found");
                return new AgentRun(RUN, new SessionId("session-a"), OWNER, 11, 101, "request-a", "digest",
                        RunState.SUCCEEDED, NOW, NOW, NOW, RuntimeProfile.LEGACY_STABLE);
            }
        };
        byte[] bytes = BODY.getBytes(StandardCharsets.UTF_8);
        AgentResultRepository results = new AgentResultRepository() {
            @Override public Optional<AgentResult> findRoot(RunId runId, UserId owner) {
                if (!OWNER.equals(owner)) return Optional.empty();
                return Optional.of(new AgentResult("result-a", RUN, "ROOT_FINAL", "text/markdown", BODY,
                        CanonicalJson.sha256Hex(bytes), bytes.length, 2, EventVisibility.USER,
                        "attempt-a", null, Map.of(), NOW, false));
            }
            @Override public Optional<AgentResult> findOwned(String resultId, RunId runId, UserId owner) {
                return Optional.empty();
            }
        };
        return new RunArtifactService(sessions, results, index, blobs, true, 1024 * 1024,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class MemoryIndex implements RunArtifactIndex {
        private final Map<String, RunArtifact> rows = new HashMap<>();
        private final Map<String, String> requestIds = new HashMap<>();

        @Override public StageResult stage(RunArtifact proposed) {
            String key = proposed.runId().value() + ":" + proposed.ownerUserId().value() + ":" + proposed.requestId();
            String existing = requestIds.get(key);
            if (existing != null) return new StageResult(rows.get(existing), false);
            rows.put(proposed.artifactId(), proposed);
            requestIds.put(key, proposed.artifactId());
            return new StageResult(proposed, true);
        }
        @Override public RunArtifact markAvailable(String id, UserId owner, String sha, long size) {
            RunArtifact old = find(id, owner).orElseThrow();
            RunArtifact next = new RunArtifact(old.artifactId(), old.runId(), old.resultId(), old.ownerUserId(),
                    old.requestId(), old.requestDigest(), old.title(), old.mediaType(), old.byteSize(), old.sha256(),
                    old.blobRef(), RunArtifact.State.AVAILABLE, old.createdAt());
            rows.put(id, next);
            return next;
        }
        @Override public void markUnavailable(String id, UserId owner) { }
        @Override public Optional<RunArtifact> find(String id, UserId owner) {
            return Optional.ofNullable(rows.get(id)).filter(row -> row.ownerUserId().equals(owner));
        }
        @Override public Page page(RunId runId, UserId owner, Position before, int limit) {
            List<RunArtifact> selected = new ArrayList<>(rows.values().stream()
                    .filter(row -> row.runId().equals(runId) && row.ownerUserId().equals(owner)).toList());
            return new Page(selected, false);
        }
    }

    private static final class MemoryBlobs implements RunArtifactBlobStore {
        private final Map<String, byte[]> rows = new HashMap<>();
        @Override public void write(String id, byte[] content) { rows.putIfAbsent(id, content.clone()); }
        @Override public Optional<byte[]> read(String id) {
            byte[] bytes = rows.get(id);
            return Optional.ofNullable(bytes == null ? null : bytes.clone());
        }
    }
}
