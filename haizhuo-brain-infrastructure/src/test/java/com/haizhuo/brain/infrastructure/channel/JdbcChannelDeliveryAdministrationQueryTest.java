package com.haizhuo.brain.infrastructure.channel;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createChannelTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.channel.ChannelDeliveryAdministrationService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcChannelDeliveryAdministrationQueryTest {
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private JdbcTemplate jdbc;
    private ChannelDeliveryAdministrationService service;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("channeldeliveryadmin");
        createChannelTables(jdbc);
        installClaimSchema(jdbc);
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(64) PRIMARY KEY,session_id VARCHAR(64),state VARCHAR(32))");
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,state) VALUES('run-1','session-1','SUCCEEDED')");
        service = new ChannelDeliveryAdministrationService(new JdbcChannelDeliveryAdministrationQuery(jdbc),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void paginatesByCreatedTimeAndIdAndReturnsOnlyAllowlistedProjection() {
        addDelivery("delivery-a", T0.plusSeconds(1), "UNCERTAIN", "secret-target", "secret-idem",
                "Authorization: Bearer very-secret-token; contact 13800138000", "raw provider response with secret");
        addDelivery("delivery-b", T0.plusSeconds(1), "DELIVERED", "secret-target-2", "secret-idem-2",
                "A safe short answer", "DELIVERED");
        addDelivery("delivery-c", T0.plusSeconds(2), "SENDING", "secret-target-3", "secret-idem-3",
                "A third result", null);

        var first = service.list(new UserId(7), new ChannelDeliveryAdministrationService.Filter(
                null, null, null, null, null, null, null, 2));
        assertEquals(2, first.items().size());
        assertEquals("delivery-c", first.items().get(0).deliveryId());
        assertEquals("delivery-b", first.items().get(1).deliveryId(), "same-time records sort by ID descending");
        assertTrue(first.hasMore());

        var second = service.list(new UserId(7), new ChannelDeliveryAdministrationService.Filter(
                null, null, null, null, null, null, first.nextCursor(), 2));
        assertEquals(1, second.items().size());
        assertEquals("delivery-a", second.items().get(0).deliveryId());
        assertFalse(second.hasMore());

        var detail = service.detail("delivery-a").orElseThrow();
        assertEquals("SUCCEEDED", detail.runState());
        assertEquals("LEGACY_UNRECORDED", detail.attemptsHistoryState());
        assertTrue(detail.attemptsHistory().isEmpty());
        assertTrue(detail.verifications().isEmpty());
        assertEquals("PROVIDER_ERROR", detail.summary().lastErrorCode());
        assertFalse(detail.summary().contentPreview().contains("very-secret-token"));
        assertFalse(detail.summary().contentPreview().contains("13800138000"));
        assertFalse(detail.summary().contentPreview().contains("secret-target"));
        assertEquals("SIMULATED", detail.summary().evidenceSource());
        assertEquals(1, detail.summary().revision());
    }

    @Test
    void filtersUseOnlyDeliveryFactsAndReadRunStateWithoutFollowingDeliveryTargets() {
        addDelivery("delivery-a", T0.plusSeconds(1), "UNCERTAIN", "private-reply", "private-idempotency",
                "summary", "SENDING_LEASE_EXPIRED");
        var page = service.list(new UserId(7), new ChannelDeliveryAdministrationService.Filter(
                "UNCERTAIN", "binding-a", "simulated", "run-missing", T0, null, null, 20));
        assertEquals(0, page.items().size());

        var detail = service.detail("delivery-a").orElseThrow();
        assertEquals("SUCCEEDED", detail.runState());
        assertEquals("SENDING_LEASE_EXPIRED", detail.summary().lastErrorCode());
    }

    @Test
    void detailReturnsBoundedAttemptFactsWithoutClaimToken() {
        addDelivery("delivery-history", T0.plusSeconds(3), "RETRYABLE_FAILURE", "private-reply",
                "private-idempotency", "safe body", "RETRYABLE_FAILURE");
        jdbc.update("UPDATE platform_channel_delivery SET attempts=2,claim_generation=2,revision=5 "
                + "WHERE delivery_id='delivery-history'");
        jdbc.update("INSERT INTO platform_channel_delivery_attempt(delivery_id,attempt_no,claim_generation,"
                        + "claim_token,started_at,finished_at,status,safe_error_code) VALUES(?,?,?,?,?,?,?,?)",
                "delivery-history", 2, 2, "private-claim-token", Timestamp.from(T0.plusSeconds(1)),
                Timestamp.from(T0.plusSeconds(2)), "RETRYABLE_FAILURE", "RETRYABLE_FAILURE");

        var detail = service.detail("delivery-history").orElseThrow();

        assertEquals(5, detail.summary().revision());
        assertEquals("PARTIAL", detail.attemptsHistoryState(), "更早的聚合尝试没有历史明细时不补造记录");
        assertEquals(1, detail.attemptsHistory().size());
        var attempt = detail.attemptsHistory().get(0);
        assertEquals(2, attempt.attemptNo());
        assertEquals(2, attempt.claimGeneration());
        assertEquals("RETRYABLE_FAILURE", attempt.status());
        assertEquals("RETRYABLE_FAILURE", attempt.safeErrorCode());
        assertFalse(attempt.toString().contains("private-claim-token"));
    }

    private void addDelivery(String id, Instant createdAt, String state, String target,
                            String idempotencyKey, String content, String lastError) {
        jdbc.update("INSERT INTO platform_channel_delivery(delivery_id,run_id,binding_id,provider,reply_target,"
                        + "content,idempotency_key,state,external_message_id,attempts,last_error,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, id.equals("delivery-a") || id.equals("delivery-b") || id.equals("delivery-c")
                        ? "run-1" : "run-missing",
                "binding-a", "simulated", target, content, idempotencyKey, state,
                "DELIVERED".equals(state) ? "external-1" : null, 2, lastError,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    private static void installClaimSchema(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE platform_channel_delivery ADD COLUMN revision BIGINT NOT NULL DEFAULT 1");
        jdbc.execute("ALTER TABLE platform_channel_delivery ADD COLUMN claim_generation BIGINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE platform_channel_delivery ADD COLUMN claim_token CHAR(36) NULL");
        jdbc.execute("CREATE TABLE platform_channel_delivery_attempt(delivery_id VARCHAR(64) NOT NULL,"
                + "attempt_no INT NOT NULL,claim_generation BIGINT NOT NULL,claim_token CHAR(36) NOT NULL,"
                + "started_at TIMESTAMP NOT NULL,finished_at TIMESTAMP NULL,status VARCHAR(24) NOT NULL,"
                + "safe_error_code VARCHAR(32) NULL,PRIMARY KEY(delivery_id,attempt_no),"
                + "UNIQUE(delivery_id,claim_generation))");
    }
}
