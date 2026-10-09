package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.RunProgressQueryStore.Position;
import com.haizhuo.brain.platform.run.RunProgressQueryStore.SnapshotFacts;
import com.haizhuo.brain.platform.run.RunProgressQueryStore.WorkItemFact;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunProgressQueryServiceTest {
    private static final UserId OWNER = new UserId(17);
    private static final UserId OTHER = new UserId(18);
    private static final RunId RUN = new RunId("run-progress-a");
    private static final Instant T0 = Instant.parse("2026-10-09T04:00:00Z");

    @Test
    void privateWorkItemFactsAreMappedToSafeProgressAndVersionIgnoresCursorOnlyChanges() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(8, T0, List.of(item("ref-a", "expert_role", "PUBLISHED_EXPERT",
                "INTERNAL", "DELEGATION_FINAL", "ACCEPTED", 1)));
        RunProgressQueryService service = service(store);

        var first = service.progress(OWNER, RUN.value(), null).projection();
        store.snapshot = snapshot(19, T0, List.of(item("ref-a", "expert_role",
                "PUBLISHED_EXPERT", "INTERNAL", "DELEGATION_FINAL", "ACCEPTED", 1)));
        var second = service.progress(OWNER, RUN.value(), first.version());

        assertTrue(first.available());
        assertEquals(1, first.summary().acceptedCount());
        assertEquals(1, first.summary().returnedCount());
        assertEquals("PRIVATE", first.workItemsPreview().get(0).latestRevision().resultAvailability());
        assertNull(first.workItemsPreview().get(0).latestRevision().resultId());
        assertEquals("协作成员", first.workItemsPreview().get(0).executorDisplayName());
        assertEquals(first.version(), second.projection().version());
        assertTrue(second.notModified());
        assertFalse(first.version().contains("INTERNAL_RESULT_ID"));
    }

    @Test
    void versionChangesWhenFactsBeyondPreviewChangeWithoutChangingAggregateCounts() {
        FakeStore store = new FakeStore();
        List<WorkItemFact> beforeFacts = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 20; ordinal++)
            beforeFacts.add(itemWithInvocationState("ref-%02d".formatted(ordinal), "NOT_STARTED", ordinal));
        beforeFacts.add(itemWithInvocationState("ref-21", "ACTIVE", 21));
        beforeFacts.add(itemWithInvocationState("ref-22", "ACCEPTED", 22));
        store.snapshot = snapshot(31, T0, beforeFacts);
        RunProgressQueryService service = service(store);
        var before = service.progress(OWNER, RUN.value(), null).projection();

        List<WorkItemFact> afterFacts = new ArrayList<>(beforeFacts);
        afterFacts.set(20, itemWithInvocationState("ref-21", "ACCEPTED", 21));
        afterFacts.set(21, itemWithInvocationState("ref-22", "ACTIVE", 22));
        store.snapshot = snapshot(32, T0, afterFacts);
        var after = service.progress(OWNER, RUN.value(), before.version());

        assertEquals(20, before.workItemsPreview().size());
        assertTrue(before.hasMoreWorkItems());
        assertEquals(before.summary(), after.projection().summary());
        assertEquals(before.workItemsPreview(), after.projection().workItemsPreview());
        assertFalse(before.version().equals(after.projection().version()));
        assertFalse(after.notModified());
        assertFalse(after.projection().version().contains("ref-21"));
    }

    @Test
    void versionIncludesFactDerivedUpdatedAtButStillIgnoresUnrelatedSessionCursorAdvances() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(4, T0, List.of(itemWithInvocationState("ref-a", "ACTIVE", 1)));
        RunProgressQueryService service = service(store);
        var before = service.progress(OWNER, RUN.value(), null).projection();

        store.snapshot = snapshot(5, T0.plusSeconds(20), List.of(itemWithInvocationState("ref-a", "ACTIVE", 1)));
        var after = service.progress(OWNER, RUN.value(), before.version());

        assertFalse(after.notModified());
        assertFalse(before.version().equals(after.projection().version()));
        assertEquals(T0.plusSeconds(20), after.projection().updatedAt());
    }

    @Test
    void versionIsBoundToRunAndFinishedWithoutResultIsNotReportedAsReturned() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(8, T0, List.of(new WorkItemFact("ref-finished", "worker", null,
                "BUILTIN_GENERAL_PURPOSE", true, 1, "FINISHED", "VALID", "PENDING", null,
                null, null, false, T0, T0, T0, T0, 1)));
        var service = service(store);
        var first = service.progress(OWNER, RUN.value(), null).projection();

        assertEquals("UNKNOWN", first.workItemsPreview().get(0).state());
        assertEquals("FINISHED", first.workItemsPreview().get(0).latestRevision().executionState());
        assertEquals("UNAVAILABLE", first.workItemsPreview().get(0).latestRevision().resultAvailability());
        assertEquals(1, first.summary().unknownCount());
        assertFalse(service.progress(OWNER, "run-progress-other", first.version()).notModified());
    }

    @Test
    void notStartedReservationMapsToItsOwnSafeState() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(8, T0, List.of(new WorkItemFact("ref-not-started", "worker", null,
                "BUILTIN_GENERAL_PURPOSE", true, 1, "NOT_STARTED", "PENDING", "PENDING", null,
                null, null, false, T0, T0, T0, T0, 1)));

        var item = service(store).progress(OWNER, RUN.value(), null).projection().workItemsPreview().get(0);

        assertEquals("NOT_STARTED", item.state());
        assertEquals("NOT_STARTED", item.latestRevision().executionState());
    }

    @Test
    void generatedRoleAndResultTextNeverBecomePublicIdentifiers() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(2, T0, List.of(item("ref-dynamic", "secret-prompt", "RUNTIME_GENERATED",
                "INTERNAL", "DELEGATION_FINAL", "PENDING", 1)));
        var projection = service(store).progress(OWNER, RUN.value(), null).projection();
        var item = projection.workItemsPreview().get(0);
        assertEquals("readonly_analyst", item.roleId());
        assertEquals("只读分析", item.executorDisplayName());
        assertEquals("RUNTIME_GENERATED", item.sourceKind());
        assertNull(item.latestRevision().resultId());
    }

    @Test
    void missingOrOtherOwnersRunIsNotFoundAndPageCursorIsBoundToOwnerAndRun() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(1, T0, List.of());
        store.page = List.of(item("ref-a", null, "BUILTIN_GENERAL_PURPOSE", null, null, "PENDING", 1),
                item("ref-b", null, "BUILTIN_GENERAL_PURPOSE", null, null, "PENDING", 2));
        RunProgressQueryService service = service(store);

        assertThrows(RunProgressQueryService.RunProgressNotFoundException.class,
                () -> service.progress(OTHER, RUN.value(), null));
        RunWorkItemPage page = service.workItems(OWNER, RUN.value(), null, 1);
        assertTrue(page.hasMore());
        assertEquals(1, page.items().size());
        assertThrows(RunProgressQueryService.RunProgressNotFoundException.class,
                () -> service.workItems(OTHER, RUN.value(), page.nextCursor(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.workItems(OWNER, "different-run", page.nextCursor(), 1));
    }

    @Test
    void configuredBudgetWithoutCollaborationFactsDoesNotCreateAvailableProgress() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(1, T0, List.of());

        var projection = service(store).progress(OWNER, RUN.value(), null).projection();

        assertFalse(projection.available());
        assertEquals(0, projection.summary().workItemTotal());
        assertNull(projection.delegationBudget());
        assertNull(projection.team());
    }

    @Test
    void budgetShowsReservationsSeparatelyFromAcceptedResults() {
        FakeStore store = new FakeStore();
        store.snapshot = new SnapshotFacts("TEAM_READONLY", "RUNNING", 4, T0,
                List.of(itemWithInvocationState("ref-a", "ACCEPTED", 1)),
                1, 1, 4, 2, null);
        var projection = service(store).progress(OWNER, RUN.value(), null).projection();
        assertEquals(1, projection.delegationBudget().invocationsReserved());
        assertEquals(0, projection.summary().acceptedCount());
        assertEquals(1, projection.summary().activeCount());
    }

    @Test
    void teamRecoveryReasonIsExposedOnlyFromTheRuntimeOwnedSafeCodeSet() {
        FakeStore store = new FakeStore();
        var service = service(store);
        store.snapshot = withTeam("TEAM_TIMEOUT");
        var safe = service.progress(OWNER, RUN.value(), null).projection();
        assertEquals("TEAM_TIMEOUT", safe.team().safeStopReasonCode());

        store.snapshot = withTeam("UPSTREAM_SECRET_MARKER");
        var filtered = service.progress(OWNER, RUN.value(), null).projection();
        assertNull(filtered.team().safeStopReasonCode());
        assertFalse(filtered.toString().contains("UPSTREAM_SECRET_MARKER"));
        assertFalse(filtered.version().contains("UPSTREAM_SECRET_MARKER"));
    }

    @Test
    void resultBodyRequiresUserVisibilityAndMatchingStoredIntegrityFacts() {
        FakeStore store = new FakeStore();
        store.snapshot = snapshot(1, T0, List.of(item("ref-unproven", "expert_role", "PUBLISHED_EXPERT",
                "USER", "DELEGATION_FINAL", "PENDING", 1)));
        var unproven = service(store).progress(OWNER, RUN.value(), null).projection().workItemsPreview().get(0)
                .latestRevision();
        assertEquals("UNAVAILABLE", unproven.resultAvailability());
        assertNull(unproven.resultId());

        store.snapshot = snapshot(1, T0, List.of(new WorkItemFact("ref-proven", "expert_role", "Expert",
                "PUBLISHED_EXPERT", true, 1, "FINISHED", "VALID", "PENDING", "visible-result",
                "DELEGATION_FINAL", "USER", true, T0, T0, T0, T0, 1)));
        var proven = service(store).progress(OWNER, RUN.value(), null).projection().workItemsPreview().get(0)
                .latestRevision();
        assertEquals("USER_VISIBLE", proven.resultAvailability());
        assertEquals("visible-result", proven.resultId());

        String body = "# Public result";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String hash = CanonicalJson.sha256Hex(bytes);
        store.resultBody = new RunProgressQueryStore.WorkItemResultFact("visible-result", "DELEGATION_FINAL",
                "USER", "text/markdown", body, hash, bytes.length, hash, T0);
        RunProgressQueryService service = service(store);

        var visible = service.workItemResult(OWNER, RUN.value(), "ref-a", 1).orElseThrow();
        assertEquals(body, visible.body());
        assertEquals(hash, visible.bodySha256());
        assertEquals(bytes.length, visible.byteSize());

        store.resultBody = new RunProgressQueryStore.WorkItemResultFact("visible-result", "DELEGATION_FINAL",
                "INTERNAL", "text/markdown", body, hash, bytes.length, hash, T0);
        assertTrue(service.workItemResult(OWNER, RUN.value(), "ref-a", 1).isEmpty());

        store.resultBody = new RunProgressQueryStore.WorkItemResultFact("visible-result", "DELEGATION_FINAL",
                "USER", "text/markdown", body, hash, bytes.length + 1L, hash, T0);
        assertTrue(service.workItemResult(OWNER, RUN.value(), "ref-a", 1).isEmpty());

        store.resultBody = new RunProgressQueryStore.WorkItemResultFact("visible-result", "DELEGATION_FINAL",
                "USER", "text/markdown", body, hash, bytes.length, "f".repeat(64), T0);
        assertTrue(service.workItemResult(OWNER, RUN.value(), "ref-a", 1).isEmpty());
    }

    private static WorkItemFact item(String ref, String roleId, String source,
                                     String visibility, String kind, String review, int ordinal) {
        return new WorkItemFact(ref, roleId, null, source, true, 1, "ACCEPTED", "VALID", review,
                "INTERNAL_RESULT_ID", kind, visibility, false, T0, null, T0, T0, ordinal);
    }

    private static WorkItemFact itemWithInvocationState(String ref, String invocationState, int ordinal) {
        return new WorkItemFact(ref, "safe_role", "安全成员", "PUBLISHED_EXPERT", true, 1,
                invocationState, "PENDING", "PENDING", null, null, null, false,
                T0, null, T0, T0, ordinal);
    }

    private static SnapshotFacts snapshot(long cursor, Instant updated, List<WorkItemFact> items) {
        return new SnapshotFacts("TEAM_READONLY", "RUNNING", cursor, updated, items,
                items.size(), 0, 4, 2, null);
    }

    private static SnapshotFacts withTeam(String recoveryReason) {
        var team = new RunProgressQueryStore.TeamFact("team-a", "RECOVERY_REQUIRED", T0, T0,
                recoveryReason, List.of(), List.of(), null);
        return new SnapshotFacts("TEAM_READONLY", "RECOVERY_REQUIRED", 4, T0,
                List.of(), 0, 0, null, null, team);
    }

    private static RunProgressQueryService service(FakeStore store) {
        SessionApplicationService sessions = new SessionApplicationService(null, null, null, null, null,
                java.time.Clock.systemUTC()) {
            @Override public AgentRun getRun(RunId runId, UserId owner) {
                if (!OWNER.equals(owner)) throw new IllegalArgumentException("not found");
                return new AgentRun(runId, new SessionId("session-a"), OWNER, 5, 20,
                        "request", "digest", RunState.RUNNING, T0, T0, null, RuntimeProfile.TEAM_READONLY);
            }
        };
        return new RunProgressQueryService(sessions, store);
    }

    private static final class FakeStore implements RunProgressQueryStore {
        private SnapshotFacts snapshot;
        private List<WorkItemFact> page = List.of();
        private RunProgressQueryStore.WorkItemResultFact resultBody;

        @Override public SnapshotFacts snapshot(UserId owner, RunId runId) {
            return snapshot;
        }
        @Override public List<WorkItemFact> workItems(UserId owner, RunId runId, Position before, int limit) {
            return page.stream().limit(limit).toList();
        }
        @Override public Optional<WorkItemDetailFacts> workItem(UserId owner, RunId runId, String workItemRef) {
            return Optional.empty();
        }
        @Override public Optional<RunProgressQueryStore.WorkItemResultFact> workItemResult(
                UserId owner, RunId runId, String workItemRef, int assignmentRevision) {
            return Optional.ofNullable(resultBody);
        }
    }
}
