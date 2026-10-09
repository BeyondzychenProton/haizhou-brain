package com.haizhuo.brain.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelDeliveryAdministrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private static final UserId ADMIN = new UserId(9);

    @Test
    void cursorKeepsUpperBoundAndStablePositionAndBindsFiltersAndAdmin() {
        FakeQuery query = new FakeQuery();
        ChannelDeliveryAdministrationService service = service(query);
        var filter = new ChannelDeliveryAdministrationService.Filter("UNCERTAIN", "binding-a", null,
                null, null, null, null, 2);

        var first = service.list(ADMIN, filter);
        assertEquals(2, first.items().size());
        assertTrue(first.hasMore());
        assertNotNull(first.nextCursor());

        var next = service.list(ADMIN, new ChannelDeliveryAdministrationService.Filter("UNCERTAIN", "binding-a",
                null, null, null, null, first.nextCursor(), 2));
        assertEquals(3, query.lastQuery.limit(), "repository receives one extra row to determine hasMore");
        assertEquals(NOW, query.lastQuery.createdTo(), "cursor retains the first-page time boundary");
        assertEquals(first.items().get(1).createdAt(), query.lastQuery.beforeCreatedAt());
        assertEquals(first.items().get(1).deliveryId(), query.lastQuery.beforeDeliveryId());
        assertEquals(1, next.items().size());
        assertEquals(null, next.nextCursor());

        assertThrows(IllegalArgumentException.class, () -> service.list(ADMIN,
                new ChannelDeliveryAdministrationService.Filter("DELIVERED", "binding-a", null,
                        null, null, null, first.nextCursor(), 2)));
        assertThrows(IllegalArgumentException.class, () -> service.list(new UserId(10),
                new ChannelDeliveryAdministrationService.Filter("UNCERTAIN", "binding-a", null,
                        null, null, null, first.nextCursor(), 2)));
    }

    @Test
    void rejectsInvalidLimitsStatesAndTimeRanges() {
        ChannelDeliveryAdministrationService service = service(new FakeQuery());
        assertThrows(IllegalArgumentException.class, () -> service.list(ADMIN,
                new ChannelDeliveryAdministrationService.Filter("UNKNOWN", null, null, null,
                        null, null, null, 20)));
        assertThrows(IllegalArgumentException.class, () -> service.list(ADMIN,
                new ChannelDeliveryAdministrationService.Filter(null, null, null, null,
                        null, null, null, 101)));
        assertThrows(IllegalArgumentException.class, () -> service.list(ADMIN,
                new ChannelDeliveryAdministrationService.Filter(null, null, null, null,
                        NOW.plusSeconds(1), NOW, null, 20)));
    }

    private static ChannelDeliveryAdministrationService service(FakeQuery query) {
        return new ChannelDeliveryAdministrationService(query, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class FakeQuery implements ChannelDeliveryAdministrationQuery {
        private final List<DeliverySummary> rows = List.of(
                row("d-3", NOW.minusSeconds(1)), row("d-2", NOW.minusSeconds(2)), row("d-1", NOW.minusSeconds(3)));
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
            return Optional.empty();
        }

        private static DeliverySummary row(String id, Instant createdAt) {
            return new DeliverySummary(id, "run", "session", "binding-a", "simulated", "UNCERTAIN", 1,
                    null, "redacted", null, null, createdAt, createdAt, null, "SIMULATED", 1);
        }
    }
}
