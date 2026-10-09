package com.haizhuo.brain.infrastructure.channel;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createChannelTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryClaim;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import com.haizhuo.brain.platform.channel.DeliveryOutcomeWrite;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Outbox claim 围栏与状态流转。本组 H2 测试只覆盖条件写入；
 * MySQL 锁调度和提供方副作用仍需单独验收。
 */
class JdbcChannelDeliveryTest {

    private static final RunId RUN = new RunId("run-1");

    private JdbcTemplate jdbc;
    private JdbcChannelDeliveryOutbox outbox;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("channeldelivery");
        createChannelTables(jdbc);
        installClaimSchema(jdbc);
        outbox = new JdbcChannelDeliveryOutbox(jdbc, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void enqueueIsIdempotentOnTheIdempotencyKey() {
        ChannelDelivery first = outbox.enqueue(delivery("delivery-1", "run-1:reply"));
        ChannelDelivery again = outbox.enqueue(delivery("delivery-2", "run-1:reply"));

        assertEquals("delivery-1", again.deliveryId(), "同一幂等键必须返回既有记录，不能重复入队");
        assertEquals(first.idempotencyKey(), again.idempotencyKey());
        assertEquals(1, count(), "重复入队不得产生第二行");
    }

    @Test
    void claimIncrementsRevisionAndGenerationAndPersistsOnlyInternalToken() {
        outbox.enqueue(delivery("delivery-1", "run-1:reply"));

        ChannelDeliveryClaim claim = outbox.claimNextDelivery().orElseThrow();
        assertEquals(1, claim.attemptNo());
        assertEquals(1, claim.claimGeneration());
        assertFalse(claim.claimToken().isBlank());
        assertFalse(claim.toString().contains(claim.claimToken()), "claim toString 不应泄露私有 token");
        assertTrue(outbox.claimNextDelivery().isEmpty(), "已被认领的投递不能被第二个 worker 拿走");
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM platform_channel_delivery WHERE delivery_id='delivery-1'", Integer.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT revision FROM platform_channel_delivery WHERE delivery_id='delivery-1'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_delivery_attempt "
                + "WHERE delivery_id='delivery-1' AND claim_token=? AND status='SENDING'", Integer.class,
                claim.claimToken()));
    }

    @Test
    void tokenMismatchIsStaleEvenWhenGenerationMatches() {
        outbox.enqueue(delivery("delivery-token", "run-1:reply-token"));
        ChannelDeliveryClaim claim = outbox.claimNextDelivery().orElseThrow();
        ChannelDeliveryClaim forged = new ChannelDeliveryClaim(claim.delivery(), claim.attemptNo(),
                claim.claimGeneration(), "different-token");

        assertEquals(DeliveryOutcomeWrite.STALE_CLAIM, outbox.recordOutcome(forged, result(
                ChannelOutboundSender.DeliveryResult.Status.DELIVERED, "forged-message")));
        assertEquals("SENDING", jdbc.queryForObject(
                "SELECT state FROM platform_channel_delivery WHERE delivery_id='delivery-token'", String.class));
        assertEquals(DeliveryOutcomeWrite.APPLIED, outbox.recordOutcome(claim, result(
                ChannelOutboundSender.DeliveryResult.Status.DELIVERED, "valid-message")));
        assertEquals("valid-message", jdbc.queryForObject(
                "SELECT external_message_id FROM platform_channel_delivery WHERE delivery_id='delivery-token'",
                String.class));
    }

    @Test
    void previousClaimCannotOverwriteAReclaimedAttempt() {
        outbox.enqueue(delivery("delivery-retry", "run-1:reply-retry"));
        ChannelDeliveryClaim first = outbox.claimNextDelivery().orElseThrow();
        assertEquals(DeliveryOutcomeWrite.APPLIED, outbox.recordOutcome(first, result(
                ChannelOutboundSender.DeliveryResult.Status.RETRYABLE_FAILURE, null)));

        ChannelDeliveryClaim second = outbox.claimNextDelivery().orElseThrow();
        assertEquals(2, second.attemptNo());
        assertEquals(2, second.claimGeneration());
        assertNotEquals(first.claimToken(), second.claimToken());
        assertEquals(DeliveryOutcomeWrite.STALE_CLAIM, outbox.recordOutcome(first, result(
                ChannelOutboundSender.DeliveryResult.Status.DELIVERED, "stale-message")));
        assertEquals("SENDING", jdbc.queryForObject(
                "SELECT state FROM platform_channel_delivery WHERE delivery_id='delivery-retry'", String.class));
        assertEquals(DeliveryOutcomeWrite.APPLIED, outbox.recordOutcome(second, result(
                ChannelOutboundSender.DeliveryResult.Status.DELIVERED, "ext-1")));
        assertTrue(outbox.claimNextDelivery().isEmpty(), "已投递的不能再次发送");
        assertEquals("ext-1", jdbc.queryForObject(
                "SELECT external_message_id FROM platform_channel_delivery WHERE delivery_id='delivery-retry'",
                String.class));
        assertEquals("RETRYABLE_FAILURE", jdbc.queryForObject("SELECT status FROM platform_channel_delivery_attempt "
                + "WHERE delivery_id='delivery-retry' AND attempt_no=1", String.class));
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM platform_channel_delivery_attempt "
                + "WHERE delivery_id='delivery-retry' AND attempt_no=2", String.class));
    }

    @Test
    void expiredLeaseMovesToUncertainAndRejectsTheLateSenderOutcome() {
        outbox.enqueue(delivery("delivery-expired", "run-1:reply-expired"));
        ChannelDeliveryClaim claim = outbox.claimNextDelivery().orElseThrow();
        jdbc.update("UPDATE platform_channel_delivery SET sending_expires_at=? WHERE delivery_id=?",
                Timestamp.from(T0.minusSeconds(1)), "delivery-expired");

        assertTrue(outbox.claimNextDelivery().isEmpty(), "过期租约进入 UNCERTAIN，不得自动重发");
        assertEquals(DeliveryOutcomeWrite.STALE_CLAIM, outbox.recordOutcome(claim, result(
                ChannelOutboundSender.DeliveryResult.Status.DELIVERED, "late-message")));
        assertEquals("UNCERTAIN", jdbc.queryForObject(
                "SELECT state FROM platform_channel_delivery WHERE delivery_id='delivery-expired'", String.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT revision FROM platform_channel_delivery WHERE delivery_id='delivery-expired'", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT external_message_id FROM platform_channel_delivery WHERE delivery_id='delivery-expired'",
                String.class));
        assertEquals("SENDING_LEASE_EXPIRED", jdbc.queryForObject("SELECT safe_error_code "
                + "FROM platform_channel_delivery_attempt WHERE delivery_id='delivery-expired'", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT sending_expires_at FROM platform_channel_delivery WHERE delivery_id='delivery-expired'",
                Timestamp.class));
    }

    @Test
    void uncertainOutcomeRemainsParked() {
        outbox.enqueue(delivery("delivery-uncertain", "run-1:reply-uncertain"));
        ChannelDeliveryClaim claim = outbox.claimNextDelivery().orElseThrow();
        assertEquals(DeliveryOutcomeWrite.APPLIED, outbox.recordOutcome(claim, result(
                ChannelOutboundSender.DeliveryResult.Status.UNCERTAIN, null)));
        assertTrue(outbox.claimNextDelivery().isEmpty(), "结果不确定的投递必须停下等人工核查，绝不能自动重发");
    }

    @Test
    void channelSessionRepliesAreEnqueuedOncePerRun() {
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                        + "credential_ref,default_employee_id,enabled,created_at,updated_at) "
                        + "VALUES('feishu-main',7,'feishu','cli_app','env:feishu-main',11,TRUE,?,?)",
                Timestamp.from(T0), Timestamp.from(T0));
        jdbc.update("INSERT INTO platform_channel_conversation(binding_id,external_conversation_id,session_id,"
                        + "created_at,updated_at) VALUES('feishu-main','chat-1','session-1',?,?)",
                Timestamp.from(T0), Timestamp.from(T0));
        jdbc.update("INSERT INTO platform_channel_inbox(binding_id,provider_event_id,provider,"
                        + "external_conversation_id,external_user_id,user_id,session_id,run_id,reply_target,"
                        + "content,accepted_at) VALUES('feishu-main','event-1','feishu','chat-1','ou-1',23,"
                        + "'session-1','run-1','chat-1','hello',?)", Timestamp.from(T0));
        JdbcChannelReplyEnqueuer enqueuer = new JdbcChannelReplyEnqueuer(jdbc, outbox);

        enqueuer.enqueueReply(RUN, new SessionId("session-1"), "答复正文");
        enqueuer.enqueueReply(RUN, new SessionId("session-1"), "答复正文");

        assertEquals(1, count(), "同一个 Run 的答复只能入队一次，幂等键必须是 runId:reply");
        ChannelDelivery queued = outbox.claimNextDelivery().orElseThrow().delivery();
        assertEquals("feishu", queued.provider());
        assertEquals("chat-1", queued.replyTarget());
        assertEquals("答复正文", queued.text());
    }

    @Test
    void webSessionsNeverEnterTheDeliveryQueue() {
        JdbcChannelReplyEnqueuer enqueuer = new JdbcChannelReplyEnqueuer(jdbc, outbox);

        enqueuer.enqueueReply(RUN, new SessionId("web-session"), "Web 会话的答复");

        assertEquals(0, count(), "没有渠道映射的会话不得产生投递");
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

    private static ChannelDelivery delivery(String deliveryId, String idempotencyKey) {
        return new ChannelDelivery(deliveryId, RUN, "feishu-main", "feishu", "chat-1", "答复", idempotencyKey);
    }

    private static ChannelOutboundSender.DeliveryResult result(
            ChannelOutboundSender.DeliveryResult.Status status, String externalMessageId) {
        return new ChannelOutboundSender.DeliveryResult(status, externalMessageId);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_delivery", Integer.class);
    }
}
