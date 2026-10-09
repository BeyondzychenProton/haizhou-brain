package com.haizhuo.brain.api.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelDeliveryAdministrationQuery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryAdministrationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChannelDeliveryAdministrationControllerTest {
    private static final UserId ADMIN = new UserId(7);
    private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");

    @Test
    void listUsesAuthenticatedAdministratorAndReturnsReadOnlyCursorPage() {
        FakeQuery query = new FakeQuery();
        var controller = new ChannelDeliveryAdministrationController(new ChannelDeliveryAdministrationService(
                query, Clock.fixed(NOW, ZoneOffset.UTC)));
        var actor = new AuthenticatedUser(ADMIN, Set.of(PlatformRole.PLATFORM_ADMIN), 1, false);

        var response = controller.list(actor, "UNCERTAIN", "binding-1", null, null,
                null, null, null, 1).block();

        assertNotNull(response);
        assertEquals(1, response.items().size());
        assertNotNull(response.nextCursor());
        assertTrue(response.hasMore());
        assertEquals("delivery-1", response.items().get(0).deliveryId());
        assertEquals(1, response.items().get(0).revision());
        assertEquals("UNCERTAIN", query.lastQuery.state());
        assertEquals("binding-1", query.lastQuery.bindingId());
        assertEquals(2, query.lastQuery.limit(), "query fetches one extra row to determine hasMore");
    }

    @Test
    void detailMapsAttemptFactsWithoutExposingClaimToken() {
        FakeQuery query = new FakeQuery();
        var controller = new ChannelDeliveryAdministrationController(new ChannelDeliveryAdministrationService(
                query, Clock.fixed(NOW, ZoneOffset.UTC)));

        var response = controller.detail("delivery-1").block();

        assertNotNull(response);
        assertEquals(4, response.summary().revision());
        assertEquals(1, response.attemptsHistory().size());
        assertEquals(2, response.attemptsHistory().get(0).attemptNo());
        assertEquals(3, response.attemptsHistory().get(0).claimGeneration());
        assertEquals("RETRYABLE_FAILURE", response.attemptsHistory().get(0).safeErrorCode());
        assertTrue(response.toString().contains("delivery-1"));
        assertFalse(response.toString().contains("private-claim-token"));
    }

    private static final class FakeQuery implements ChannelDeliveryAdministrationQuery {
        private final List<DeliverySummary> rows = List.of(row("delivery-1"), row("delivery-2"));
        private PageQuery lastQuery;

        @Override
        public List<DeliverySummary> findPage(PageQuery query) {
            this.lastQuery = query;
            return rows.stream().filter(row -> query.beforeCreatedAt() == null
                            || row.createdAt().isBefore(query.beforeCreatedAt())
                            || (row.createdAt().equals(query.beforeCreatedAt())
                            && row.deliveryId().compareTo(query.beforeDeliveryId()) < 0))
                    .limit(query.limit()).toList();
        }

        @Override
        public Optional<DeliveryDetail> findById(String deliveryId) {
            if (!"delivery-1".equals(deliveryId)) return Optional.empty();
            var summary = new DeliverySummary("delivery-1", "run-1", "session-1", "binding-1", "simulated",
                    "RETRYABLE_FAILURE", 2, null, "safe summary", null, "RETRYABLE_FAILURE", NOW, NOW,
                    null, "SIMULATED", 4);
            return Optional.of(new DeliveryDetail(summary, "SUCCEEDED", List.of(new AttemptHistory(2, 3,
                    NOW.minusSeconds(5), NOW, "RETRYABLE_FAILURE", "RETRYABLE_FAILURE")),
                    "PARTIAL", List.of()));
        }

        private static DeliverySummary row(String id) {
            return new DeliverySummary(id, "run-1", "session-1", "binding-1", "simulated", "UNCERTAIN", 1,
                    null, "safe summary", null, "SENDING_LEASE_EXPIRED", NOW, NOW, null, "SIMULATED", 1);
        }
    }
}
