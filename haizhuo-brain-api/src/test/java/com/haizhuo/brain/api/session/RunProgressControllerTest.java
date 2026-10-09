package com.haizhuo.brain.api.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.RunProgressQueryService;
import com.haizhuo.brain.platform.run.RunProgressQueryStore;
import com.haizhuo.brain.platform.run.RunProgressQueryStore.Position;
import com.haizhuo.brain.platform.run.RunProgressQueryStore.SnapshotFacts;
import com.haizhuo.brain.platform.run.RunProgressQueryStore.WorkItemFact;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunProgressControllerTest {
    private static final UserId OWNER = new UserId(7);
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private static final RunId RUN = new RunId("run-progress-api");

    @Test
    void progressReturnsPrivateNoStoreAndSupportsNotModifiedByVersion() {
        ControllerFixture fixture = new ControllerFixture();
        var user = user(OWNER);

        var first = fixture.controller.getProgress(user, RUN.value(), null).block();
        var second = fixture.controller.getProgress(user, RUN.value(), first.getBody().version()).block();

        assertEquals(200, first.getStatusCode().value());
        assertEquals("private, no-store, max-age=0", first.getHeaders().getCacheControl());
        assertTrue(first.getHeaders().getETag().contains(first.getBody().version()));
        assertEquals(304, second.getStatusCode().value());
        assertEquals(1, first.getBody().schemaVersion());
    }

    @Test
    void ownerMismatchMapsToHiddenNotFoundAndMissingWorkItemIs404() {
        ControllerFixture fixture = new ControllerFixture();
        assertThrows(RunProgressQueryService.RunProgressNotFoundException.class,
                () -> fixture.controller.getProgress(user(new UserId(8)), RUN.value(), null).block());
        assertEquals(404, fixture.controller.notFound(new RunProgressQueryService.RunProgressNotFoundException())
                .block().getStatusCode().value());
        assertEquals(404, fixture.controller.workItem(user(OWNER), RUN.value(), "missing").block()
                .getStatusCode().value());
    }

    @Test
    void resultRouteReturnsOnlyVerifiedUserVisibleRevisionAndUsesNoStore() {
        ControllerFixture fixture = new ControllerFixture();
        String body = "# Authorized result";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String hash = CanonicalJson.sha256Hex(bytes);
        fixture.resultBody = new RunProgressQueryStore.WorkItemResultFact("result-a", "DELEGATION_FINAL", "USER",
                "text/markdown", body, hash, bytes.length, hash, NOW);

        var visible = fixture.controller.workItemResult(user(OWNER), RUN.value(), "item-a", 1).block();

        assertEquals(200, visible.getStatusCode().value());
        assertEquals("private, no-store, max-age=0", visible.getHeaders().getCacheControl());
        assertEquals(body, visible.getBody().body());

        fixture.resultBody = new RunProgressQueryStore.WorkItemResultFact("result-a", "DELEGATION_FINAL", "INTERNAL",
                "text/markdown", body, hash, bytes.length, hash, NOW);
        assertEquals(404, fixture.controller.workItemResult(user(OWNER), RUN.value(), "item-a", 1).block()
                .getStatusCode().value());
        fixture.resultBody = null;
        assertEquals(404, fixture.controller.workItemResult(user(OWNER), RUN.value(), "item-a", 1).block()
                .getStatusCode().value());
    }

    private static AuthenticatedUser user(UserId id) {
        return new AuthenticatedUser(id, Set.of(PlatformRole.USER), 1, false);
    }

    private static final class ControllerFixture {
        private final RunProgressController controller;
        private RunProgressQueryStore.WorkItemResultFact resultBody;
        private ControllerFixture() {
            RunProgressQueryStore store = new RunProgressQueryStore() {
                @Override public SnapshotFacts snapshot(UserId owner, RunId runId) {
                    return new SnapshotFacts("TEAM_READONLY", "RUNNING", 5, NOW, List.of(), 0, 0, 4, 2, null);
                }
                @Override public List<WorkItemFact> workItems(UserId owner, RunId runId, Position before, int limit) {
                    return List.of();
                }
                @Override public Optional<WorkItemDetailFacts> workItem(UserId owner, RunId runId, String workItemRef) {
                    return Optional.empty();
                }
                @Override public Optional<RunProgressQueryStore.WorkItemResultFact> workItemResult(
                        UserId owner, RunId runId, String workItemRef, int assignmentRevision) {
                    return Optional.ofNullable(ControllerFixture.this.resultBody);
                }
            };
            SessionApplicationService sessions = new SessionApplicationService(null, null, null, null, null,
                    Clock.fixed(NOW, ZoneOffset.UTC)) {
                @Override public AgentRun getRun(RunId runId, UserId owner) {
                    if (!OWNER.equals(owner) || !RUN.equals(runId)) throw new IllegalArgumentException("not found");
                    return new AgentRun(RUN, new SessionId("session-a"), OWNER, 9, 91,
                            "request", "digest", RunState.RUNNING, NOW, NOW, null, RuntimeProfile.TEAM_READONLY);
                }
            };
            this.controller = new RunProgressController(new RunProgressQueryService(sessions, store));
        }
    }
}
