package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunArtifactServiceTest {
    private static final UserId OWNER = new UserId(7);
    private static final RunId RUN = new RunId("run-a");
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private static final String BODY = "<script>do-not-execute()</script>\n# answer";

    @Test
    void exportsOnlyCompleteOwnerVisibleRootAndIdempotentlyReturnsSamePrivateBlob() {
        MemoryIndex index = new MemoryIndex();
        MemoryBlobs blobs = new MemoryBlobs();
        RunArtifactService service = service(index, blobs, RunState.SUCCEEDED,
                result("result-a", BODY, EventVisibility.USER, false));

        var first = service.createMarkdown(RUN, OWNER, "export-1");
        var replay = service.createMarkdown(RUN, OWNER, "export-1");
        var download = service.loadContent(first.artifact().artifactId(), OWNER);

        assertEquals("AVAILABLE", first.artifact().state());
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.artifact().artifactId(), replay.artifact().artifactId());
        assertEquals(1, blobs.writes);
        assertArrayEquals(BODY.getBytes(StandardCharsets.UTF_8), download.content());
        assertFalse(first.artifact().toString().contains("blob"), "public item has no storage reference");
    }

    @Test
    void rejectsNonTerminalPrivateLegacyAndCorruptSourcesBeforeStaging() {
        MemoryIndex index = new MemoryIndex();
        MemoryBlobs blobs = new MemoryBlobs();
        assertThrows(RunArtifactService.RunArtifactNotReadyException.class,
                () -> service(index, blobs, RunState.RUNNING, result("result-a", BODY, EventVisibility.USER, false))
                        .createMarkdown(RUN, OWNER, "export-running"));
        assertThrows(RunArtifactService.RunArtifactNotReadyException.class,
                () -> service(index, blobs, RunState.SUCCEEDED, result("result-private", BODY, EventVisibility.INTERNAL, false))
                        .createMarkdown(RUN, OWNER, "export-private"));
        assertThrows(RunArtifactService.RunArtifactNotReadyException.class,
                () -> service(index, blobs, RunState.SUCCEEDED, result("result-summary", BODY, EventVisibility.USER, true))
                        .createMarkdown(RUN, OWNER, "export-summary"));
        AgentResult corrupt = new AgentResult("result-corrupt", RUN, "ROOT_FINAL", "text/markdown", BODY,
                "0".repeat(64), BODY.length(), 2, EventVisibility.USER, "attempt-a", null, Map.of(), NOW, false);
        assertThrows(RunArtifactService.RunArtifactSourceIntegrityException.class,
                () -> service(index, blobs, RunState.SUCCEEDED, corrupt)
                        .createMarkdown(RUN, OWNER, "export-corrupt"));
        assertTrue(index.rows.isEmpty());
    }

    @Test
    void ownerMismatchAndDisabledGateDoNotReadOrWriteAnotherUsersArtifact() {
        MemoryIndex index = new MemoryIndex();
        MemoryBlobs blobs = new MemoryBlobs();
        RunArtifactService notOwned = service(index, blobs, RunState.SUCCEEDED,
                result("result-a", BODY, EventVisibility.USER, false), false, OWNER);
        assertThrows(RunArtifactService.RunArtifactNotFoundException.class,
                () -> notOwned.createMarkdown(RUN, new UserId(8), "export-other"));

        RunArtifactService disabled = service(index, blobs, RunState.SUCCEEDED,
                result("result-a", BODY, EventVisibility.USER, false), false, OWNER, false);
        assertThrows(RunArtifactService.RunArtifactFeatureNotAvailableException.class,
                () -> disabled.createMarkdown(RUN, OWNER, "export-disabled"));
        assertTrue(index.rows.isEmpty());
    }

    @Test
    void missingOrTamperedBlobIsMarkedUnavailableAndNeverReturned() {
        MemoryIndex index = new MemoryIndex();
        MemoryBlobs blobs = new MemoryBlobs();
        RunArtifactService service = service(index, blobs, RunState.SUCCEEDED,
                result("result-a", BODY, EventVisibility.USER, false));
        var created = service.createMarkdown(RUN, OWNER, "export-1").artifact();
        blobs.values.put(created.artifactId(), "tampered".getBytes(StandardCharsets.UTF_8));

        assertThrows(RunArtifactService.RunArtifactUnavailableException.class,
                () -> service.loadContent(created.artifactId(), OWNER));
        assertEquals(RunArtifact.State.UNAVAILABLE,
                index.find(created.artifactId(), OWNER).orElseThrow().state());
    }

    private static RunArtifactService service(MemoryIndex index, MemoryBlobs blobs, RunState state,
                                              AgentResult result) {
        return service(index, blobs, state, result, true, OWNER, true);
    }

    private static RunArtifactService service(MemoryIndex index, MemoryBlobs blobs, RunState state,
                                              AgentResult result, boolean ownerMatched, UserId expectedOwner) {
        return service(index, blobs, state, result, ownerMatched, expectedOwner, true);
    }

    private static RunArtifactService service(MemoryIndex index, MemoryBlobs blobs, RunState state,
                                              AgentResult result, boolean ownerMatched, UserId expectedOwner,
                                              boolean enabled) {
        SessionApplicationService sessions = new SessionApplicationService(null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override public AgentRun getRun(RunId runId, UserId owner) {
                if (!ownerMatched || !expectedOwner.equals(owner)) throw new IllegalArgumentException("not found");
                return new AgentRun(RUN, new SessionId("session-a"), expectedOwner, 11, 101, "request-a", "digest",
                        state, NOW, NOW, state == RunState.SUCCEEDED ? NOW : null, RuntimeProfile.LEGACY_STABLE);
            }
        };
        AgentResultRepository resultRepository = new AgentResultRepository() {
            @Override public Optional<AgentResult> findRoot(RunId runId, UserId owner) {
                return OWNER.equals(owner) ? Optional.of(result) : Optional.empty();
            }
            @Override public Optional<AgentResult> findOwned(String resultId, RunId runId, UserId owner) {
                return Optional.empty();
            }
        };
        return new RunArtifactService(sessions, resultRepository, index, blobs, enabled, 1024 * 1024,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AgentResult result(String resultId, String body, EventVisibility visibility, boolean legacy) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new AgentResult(resultId, RUN, "ROOT_FINAL", "text/markdown", body,
                CanonicalJson.sha256Hex(bytes), bytes.length, 2, visibility, "attempt-a", null, Map.of(), NOW, legacy);
    }

    private static final class MemoryIndex implements RunArtifactIndex {
        private final Map<String, RunArtifact> rows = new HashMap<>();
        private final Map<String, String> requests = new HashMap<>();

        @Override public StageResult stage(RunArtifact proposed) {
            String key = proposed.runId().value() + ":" + proposed.ownerUserId().value() + ":" + proposed.requestId();
            String existingId = requests.get(key);
            if (existingId != null) return new StageResult(rows.get(existingId), false);
            rows.put(proposed.artifactId(), proposed);
            requests.put(key, proposed.artifactId());
            return new StageResult(proposed, true);
        }

        @Override public RunArtifact markAvailable(String artifactId, UserId owner, String sha256, long byteSize) {
            RunArtifact old = find(artifactId, owner).orElseThrow();
            RunArtifact next = new RunArtifact(old.artifactId(), old.runId(), old.resultId(), old.ownerUserId(),
                    old.requestId(), old.requestDigest(), old.title(), old.mediaType(), old.byteSize(), old.sha256(),
                    old.blobRef(), RunArtifact.State.AVAILABLE, old.createdAt());
            rows.put(artifactId, next);
            return next;
        }

        @Override public void markUnavailable(String artifactId, UserId owner) {
            RunArtifact old = find(artifactId, owner).orElseThrow();
            rows.put(artifactId, new RunArtifact(old.artifactId(), old.runId(), old.resultId(), old.ownerUserId(),
                    old.requestId(), old.requestDigest(), old.title(), old.mediaType(), old.byteSize(), old.sha256(),
                    old.blobRef(), RunArtifact.State.UNAVAILABLE, old.createdAt()));
        }

        @Override public Optional<RunArtifact> find(String artifactId, UserId owner) {
            return Optional.ofNullable(rows.get(artifactId)).filter(row -> row.ownerUserId().equals(owner));
        }

        @Override public Page page(RunId runId, UserId owner, Position before, int limit) {
            List<RunArtifact> selected = rows.values().stream()
                    .filter(row -> row.runId().equals(runId) && row.ownerUserId().equals(owner))
                    .sorted(Comparator.comparing(RunArtifact::createdAt).thenComparing(RunArtifact::artifactId).reversed())
                    .limit(limit).toList();
            return new Page(selected, false);
        }
    }

    private static final class MemoryBlobs implements RunArtifactBlobStore {
        private final Map<String, byte[]> values = new HashMap<>();
        private int writes;
        @Override public void write(String artifactId, byte[] content) {
            writes++;
            values.putIfAbsent(artifactId, content.clone());
        }
        @Override public Optional<byte[]> read(String artifactId) {
            byte[] value = values.get(artifactId);
            return Optional.ofNullable(value == null ? null : value.clone());
        }
    }
}
