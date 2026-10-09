package com.haizhuo.brain.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.platform.audit.AuditDomain;
import com.haizhuo.brain.platform.audit.AuditEntry;
import com.haizhuo.brain.platform.audit.AuditFilter;
import com.haizhuo.brain.platform.audit.AuditOutcome;
import com.haizhuo.brain.platform.audit.AuditPosition;
import com.haizhuo.brain.platform.audit.AuditQueryService;
import com.haizhuo.brain.platform.audit.AuditQueryStore;
import com.haizhuo.brain.platform.audit.AuditSourceCompleteness;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;

class AuditQueryControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    void exportIsBoundedAndFormulaCellsAreEscaped() {
        var controller = new AuditQueryController(new AuditQueryService(new FakeStore(List.of(entry(
                "CHANNEL:00000000000000000001", "=HYPERLINK(\"https://invalid\")"))),
                Clock.fixed(NOW, ZoneOffset.UTC)));
        var actor = admin();

        var response = controller.export(actor, null, null, null, null, null, null, null,
                "2026-10-08T00:00:00Z", "2026-10-10T00:00:00Z").block();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("text/csv;charset=UTF-8", response.getHeaders().getContentType().toString());
        assertEquals("1", response.getHeaders().getFirst("X-Audit-Export-Count"));
        assertEquals("true", response.getHeaders().getFirst("X-Audit-Export-Complete"));
        assertTrue(response.getBody().contains("\"'=HYPERLINK(\"\"https://invalid\"\")\""));
        assertFalse(response.getBody().contains("\"=HYPERLINK"));
    }

    @Test
    void controllerMapsSafeAuditErrorsWithoutEchoingDatabaseDetails() {
        var controller = new AuditQueryController(new AuditQueryService(new FakeStore(List.of()),
                Clock.fixed(NOW, ZoneOffset.UTC)));

        var notFound = controller.auditError(new AuditQueryService.AuditQueryException(
                AuditQueryService.Error.NOT_FOUND, "sensitive source key")).block();
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals("AUDIT_NOT_FOUND", notFound.getBody().code());
        assertFalse(notFound.getBody().message().contains("sensitive source key"));

        var tooLarge = controller.auditError(AuditQueryService.AuditQueryException.exportTooLarge()).block();
        assertEquals(HttpStatus.CONFLICT, tooLarge.getStatusCode());
        assertEquals("AUDIT_EXPORT_TOO_LARGE", tooLarge.getBody().code());

        var unavailable = controller.unavailable(new DataAccessResourceFailureException("password=secret table=private")).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("AUDIT_QUERY_UNAVAILABLE", unavailable.getBody().code());
        assertFalse(unavailable.getBody().message().contains("secret"));
    }

    @Test
    void detailResponseIsFlattenedAndKeepsCompletenessEvidence() {
        var entry = entry("CHANNEL:00000000000000000001", "binding-1");
        var controller = new AuditQueryController(new AuditQueryService(new FakeStore(List.of(entry)),
                Clock.fixed(NOW, ZoneOffset.UTC)));

        var response = controller.detail(admin(), entry.auditId()).block();

        assertEquals(entry.auditId(), response.auditId());
        assertEquals(AuditSourceCompleteness.COMPLETE, response.sourceCompleteness());
        assertNotNull(response.safeChanges());
        assertEquals("requestId", response.correlationIds().get(0).type());
    }

    private static AuthenticatedUser admin() {
        return new AuthenticatedUser(new UserId(7), Set.of(PlatformRole.PLATFORM_ADMIN), 1, false);
    }

    private static AuditEntry entry(String auditId, String targetId) {
        return new AuditEntry(auditId, AuditDomain.CHANNEL, "CREATE_ACCOUNT", 7L,
                "CHANNEL_ACCOUNT", targetId, "request-7", null, AuditOutcome.SUCCESS, NOW,
                List.of(), null, AuditSourceCompleteness.COMPLETE);
    }

    private static final class FakeStore implements AuditQueryStore {
        private final List<AuditEntry> entries;
        private FakeStore(List<AuditEntry> entries) { this.entries = List.copyOf(entries); }
        @Override public List<AuditEntry> findCandidates(AuditFilter filter, Instant upperBound, AuditPosition after, int limit) {
            return entries.stream().filter(row -> !row.createdAt().isAfter(upperBound)).limit(limit).toList();
        }
        @Override public Optional<AuditEntry> findById(String auditId) {
            return entries.stream().filter(row -> row.auditId().equals(auditId)).findFirst();
        }
        @Override public List<AuditEntry> exportSnapshot(AuditFilter filter, Instant upperBound, int maximumRows, int chunkSize) {
            return findCandidates(filter, upperBound, null, maximumRows + 1).stream().limit(maximumRows).toList();
        }
        @Override public void recordSensitiveRead(long actorUserId, String accessKind, String targetDigest,
                                                  int resultCount, Instant occurredAt) { }
    }
}
