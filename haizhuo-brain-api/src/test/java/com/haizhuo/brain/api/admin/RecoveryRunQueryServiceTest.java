package com.haizhuo.brain.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RecoveryRunQuery;
import com.haizhuo.brain.platform.run.RecoveryRunQueryStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecoveryRunQueryServiceTest {
    private static final Instant T0 = Instant.parse("2026-10-09T04:00:00Z");

    @Test
    void cursorCarriesStableBoundaryAndIsBoundToActorAndFilters() {
        var store = new RecordingStore();
        store.page = List.of(summary("run-z", T0), summary("run-y", T0.minusSeconds(1)),
                summary("run-x", T0.minusSeconds(2)));
        var service = new RecoveryRunQueryService(store);
        var filter = new RecoveryRunQueryService.Filter("RECOVERY_REQUIRED", 42L, 8L,
                null, null, null, 2);

        var first = service.list(new UserId(9), filter);

        assertEquals(2, first.items().size());
        assertEquals("run-y", first.items().get(1).runId());
        assertEquals(true, first.hasMore());
        assertNotNull(first.nextCursor());
        assertEquals(3, store.lastLimit);

        store.page = List.of(summary("run-x", T0.minusSeconds(2)));
        var second = service.list(new UserId(9), new RecoveryRunQueryService.Filter("RECOVERY_REQUIRED", 42L,
                8L, null, null, first.nextCursor(), 2));
        assertEquals(T0.minusSeconds(1), store.beforeCreatedAt);
        assertEquals("run-y", store.beforeRunId);
        assertEquals("run-x", second.items().get(0).runId());

        assertThrows(IllegalArgumentException.class, () -> service.list(new UserId(10),
                new RecoveryRunQueryService.Filter("RECOVERY_REQUIRED", 42L, 8L,
                        null, null, first.nextCursor(), 2)));
        assertThrows(IllegalArgumentException.class, () -> service.list(new UserId(9),
                new RecoveryRunQueryService.Filter("TERMINATED", 42L, 8L,
                        null, null, first.nextCursor(), 2)));
    }

    @Test
    void validatesStateDateRangeAndPageSize() {
        var service = new RecoveryRunQueryService(new RecordingStore());
        assertThrows(IllegalArgumentException.class, () -> service.list(new UserId(1),
                new RecoveryRunQueryService.Filter("RUNNING", null, null, null, null, null, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.list(new UserId(1),
                new RecoveryRunQueryService.Filter("RECOVERY_REQUIRED", null, null,
                        T0, T0.minusSeconds(1), null, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.list(new UserId(1),
                new RecoveryRunQueryService.Filter("RECOVERY_REQUIRED", null, null,
                        null, null, null, 101)));
    }

    private static RecoveryRunQuery.Summary summary(String runId, Instant createdAt) {
        return new RecoveryRunQuery.Summary(runId, "session-1", 42, 8, 108, "SINGLE_SKILLED",
                "RECOVERY_REQUIRED", "RUNTIME_OUTCOME_UNKNOWN", createdAt, null, null, null, "UNKNOWN");
    }

    private static final class RecordingStore implements RecoveryRunQueryStore {
        private List<RecoveryRunQuery.Summary> page = List.of();
        private Instant beforeCreatedAt;
        private String beforeRunId;
        private int lastLimit;

        @Override
        public List<RecoveryRunQuery.Summary> findPage(RecoveryRunQuery.Filter filter, Instant beforeCreatedAt,
                                                       String beforeRunId, int limit) {
            this.beforeCreatedAt = beforeCreatedAt;
            this.beforeRunId = beforeRunId;
            this.lastLimit = limit;
            return page;
        }

        @Override
        public Optional<RecoveryRunQuery.Detail> findDetail(String runId) {
            return Optional.empty();
        }
    }
}
