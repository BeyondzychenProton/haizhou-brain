package com.haizhuo.brain.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RecoveryRunQuery;
import com.haizhuo.brain.platform.run.RecoveryRunQueryStore;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RecoveryRunQueryControllerTest {
    @Test
    void adminPrincipalIsUsedForCursorAndResponseHasNoLeaseSecret() {
        RecoveryRunQueryStore store = new RecoveryRunQueryStore() {
            @Override
            public List<RecoveryRunQuery.Summary> findPage(RecoveryRunQuery.Filter filter,
                                                           Instant beforeCreatedAt, String beforeRunId, int limit) {
                assertEquals("RECOVERY_REQUIRED", filter.state());
                return List.of(new RecoveryRunQuery.Summary("run-1", "session-1", 42, 8, 108,
                        "SINGLE_SKILLED", "RECOVERY_REQUIRED", "LEASE_LOST", Instant.EPOCH,
                        null, null, null, "UNKNOWN"));
            }

            @Override
            public Optional<RecoveryRunQuery.Detail> findDetail(String runId) {
                return Optional.empty();
            }
        };
        var controller = new RecoveryRunQueryController(new RecoveryRunQueryService(store));
        var actor = new AuthenticatedUser(new UserId(9), Set.of(PlatformRole.PLATFORM_ADMIN), 2, false);

        var response = controller.list(actor, "RECOVERY_REQUIRED", null, null,
                null, null, null, null).block();

        assertEquals("run-1", response.items().get(0).runId());
        assertFalse(List.of(RecoveryRunQueryService.DetailResponse.class.getRecordComponents()).stream()
                .anyMatch(component -> component.getName().toLowerCase().contains("lease_token")));
    }

    @Test
    void missingRunHasDedicated404AndQueryFailureHasSafe503() {
        var controller = new RecoveryRunQueryController(new RecoveryRunQueryService(new RecoveryRunQueryStore() {
            @Override
            public List<RecoveryRunQuery.Summary> findPage(RecoveryRunQuery.Filter filter,
                                                           Instant beforeCreatedAt, String beforeRunId, int limit) {
                return List.of();
            }

            @Override
            public Optional<RecoveryRunQuery.Detail> findDetail(String runId) {
                return Optional.empty();
            }
        }));
        var missing = assertThrows(RecoveryRunQueryService.RecoveryRunNotFoundException.class,
                () -> controller.detail("missing").block());
        var notFound = controller.notFound(missing).block();
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertEquals("RECOVERY_RUN_NOT_FOUND", notFound.getBody().code());

        var unavailable = controller.unavailable(new org.springframework.dao.DataAccessResourceFailureException("private sql detail")).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("RECOVERY_QUERY_UNAVAILABLE", unavailable.getBody().code());
        assertFalse(unavailable.getBody().message().contains("private sql detail"));
    }
}
