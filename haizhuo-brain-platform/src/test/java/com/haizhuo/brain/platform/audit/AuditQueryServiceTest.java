package com.haizhuo.brain.platform.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuditQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    @Test
    void pagesMergeEqualTimestampsGloballyAndBindCursorToActorAndFilters() {
        var store = new FakeStore(List.of(
                entry("USER:00000000000000000003", AuditDomain.USER, "USER_CREATED", NOW),
                entry("CHANNEL:00000000000000000004", AuditDomain.CHANNEL, "CREATE_ACCOUNT", NOW),
                entry("AUTHENTICATION:00000000000000000009", AuditDomain.AUTHENTICATION, "LOGIN_FAILED", NOW),
                entry("USER:00000000000000000001", AuditDomain.USER, "USER_CREATED", NOW.minusSeconds(1))));
        var service = new AuditQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        var first = service.list(7, null, null, null, null, null, null, null, null, null, null, 2);
        assertEquals(List.of("USER:00000000000000000003", "CHANNEL:00000000000000000004"),
                first.items().stream().map(AuditQueryService.AuditSummary::auditId).toList());
        assertTrue(first.hasMore());
        assertTrue(first.nextCursor() != null && !first.nextCursor().contains("2026-10-09"));

        var second = service.list(7, null, null, null, null, null, null, null, null, null,
                first.nextCursor(), 2);
        assertEquals(List.of("AUTHENTICATION:00000000000000000009", "USER:00000000000000000001"),
                second.items().stream().map(AuditQueryService.AuditSummary::auditId).toList());
        assertFalse(second.hasMore());

        var wrongActor = assertThrows(AuditQueryService.AuditQueryException.class,
                () -> service.list(8, null, null, null, null, null, null, null, null, null, first.nextCursor(), 2));
        assertEquals(AuditQueryService.Error.INVALID_QUERY, wrongActor.error());
        var changedFilter = assertThrows(AuditQueryService.AuditQueryException.class,
                () -> service.list(7, "USER", null, null, null, null, null, null, null, null, first.nextCursor(), 2));
        assertEquals(AuditQueryService.Error.INVALID_QUERY, changedFilter.error());
    }

    @Test
    void sensitiveDetailReadIsLoggedWithoutReturningReadDigest() {
        var entry = entry("CHANNEL:00000000000000000004", AuditDomain.CHANNEL, "UPDATE_ACCOUNT", NOW);
        var store = new FakeStore(List.of(entry));
        var service = new AuditQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        var detail = service.detail(7, entry.auditId());

        assertEquals(entry.auditId(), detail.summary().auditId());
        assertEquals(AuditSourceCompleteness.COMPLETE, detail.sourceCompleteness());
        assertEquals(1, store.reads.size());
        assertEquals("DETAIL", store.reads.get(0).accessKind());
        assertTrue(store.reads.get(0).targetDigest().matches("[0-9a-f]{64}"));
        assertFalse(detail.toString().contains(store.reads.get(0).targetDigest()));
    }

    @Test
    void exportRequiresBoundedExplicitRangeAndRecordsOnlyCountAndDigest() {
        var store = new FakeStore(List.of(entry("ASSET:00000000000000000001", AuditDomain.ASSET, "PUBLISHED", NOW)));
        var service = new AuditQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC));

        var badRange = assertThrows(AuditQueryService.AuditQueryException.class,
                () -> service.export(7, null, null, null, null, null, null, null,
                        "2026-09-01T00:00:00Z", "2026-10-10T00:00:00Z"));
        assertEquals(AuditQueryService.Error.INVALID_QUERY, badRange.error());

        var export = service.export(7, "ASSET", null, null, null, null, null, null,
                "2026-10-01T00:00:00Z", "2026-10-10T00:00:00Z");
        assertEquals(1, export.count());
        assertTrue(export.complete());
        assertEquals(1, store.reads.size());
        assertEquals("EXPORT", store.reads.get(0).accessKind());
        assertEquals(1, store.reads.get(0).resultCount());
    }

    @Test
    void missingDetailUsesDedicatedNotFoundAndExportTooLargeIsPreserved() {
        var tooLargeStore = new FakeStore(List.of()) {
            @Override
            public List<AuditEntry> exportSnapshot(AuditFilter filter, Instant upperBound, int maximumRows, int chunkSize) {
                throw AuditQueryService.AuditQueryException.exportTooLarge();
            }
        };
        var service = new AuditQueryService(tooLargeStore, Clock.fixed(NOW, ZoneOffset.UTC));

        var notFound = assertThrows(AuditQueryService.AuditQueryException.class,
                () -> service.detail(7, "USER:00000000000000000010"));
        assertEquals(AuditQueryService.Error.NOT_FOUND, notFound.error());
        var tooLarge = assertThrows(AuditQueryService.AuditQueryException.class,
                () -> service.export(7, null, null, null, null, null, null, null,
                        "2026-10-01T00:00:00Z", "2026-10-10T00:00:00Z"));
        assertEquals(AuditQueryService.Error.EXPORT_TOO_LARGE, tooLarge.error());
    }

    private static AuditEntry entry(String id, AuditDomain domain, String action, Instant createdAt) {
        return new AuditEntry(id, domain, action, 9L, "PLATFORM_USER", "10", "req-safe-01", null,
                action.equals("LOGIN_FAILED") ? AuditOutcome.FAILED : AuditOutcome.SUCCESS, createdAt,
                List.of(), null, domain == AuditDomain.CHANNEL
                ? AuditSourceCompleteness.COMPLETE : AuditSourceCompleteness.LEGACY_PARTIAL);
    }

    private static class FakeStore implements AuditQueryStore {
        private final List<AuditEntry> entries;
        private final List<Read> reads = new ArrayList<>();

        private FakeStore(List<AuditEntry> entries) { this.entries = List.copyOf(entries); }

        @Override
        public List<AuditEntry> findCandidates(AuditFilter filter, Instant upperBound, AuditPosition after, int limit) {
            return entries.stream()
                    .filter(entry -> filter.domain() == null || filter.domain() == entry.domain())
                    .filter(entry -> filter.action() == null || filter.action().equals(entry.action()))
                    .filter(entry -> !entry.createdAt().isAfter(upperBound))
                    .filter(entry -> filter.createdFrom() == null || !entry.createdAt().isBefore(filter.createdFrom()))
                    .filter(entry -> filter.createdTo() == null || entry.createdAt().isBefore(filter.createdTo()))
                    .filter(entry -> after == null || entry.createdAt().isBefore(after.createdAt())
                            || entry.createdAt().equals(after.createdAt()) && entry.auditId().compareTo(after.auditId()) < 0)
                    .sorted(Comparator.comparing(AuditEntry::createdAt).reversed()
                            .thenComparing(AuditEntry::auditId, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<AuditEntry> findById(String auditId) {
            return entries.stream().filter(entry -> entry.auditId().equals(auditId)).findFirst();
        }

        @Override
        public List<AuditEntry> exportSnapshot(AuditFilter filter, Instant upperBound, int maximumRows, int chunkSize) {
            return findCandidates(filter, upperBound, null, maximumRows + 1).stream().limit(maximumRows).toList();
        }

        @Override
        public void recordSensitiveRead(long actorUserId, String accessKind, String targetDigest,
                                        int resultCount, Instant occurredAt) {
            reads.add(new Read(actorUserId, accessKind, targetDigest, resultCount, occurredAt));
        }

        private record Read(long actorUserId, String accessKind, String targetDigest,
                            int resultCount, Instant occurredAt) { }
    }
}
