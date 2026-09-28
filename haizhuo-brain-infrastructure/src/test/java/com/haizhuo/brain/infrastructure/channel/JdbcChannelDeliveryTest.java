package com.haizhuo.brain.infrastructure.channel;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createChannelTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 渠道出站投递（P3）：投递状态机是"不重复发送"的唯一保证，这里逐条守住它。
 * 尤其是结果不确定（UNCERTAIN）绝不能被再次认领——渠道投递无法回滚。
 */
class JdbcChannelDeliveryTest {

    private static final RunId RUN = new RunId("run-1");

    private JdbcTemplate jdbc;
    private JdbcChannelDeliveryOutbox outbox;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("channeldelivery");
        createChannelTables(jdbc);
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
    void claimingFlipsStateSoASecondWorkerCannotTakeTheSameDelivery() {
        outbox.enqueue(delivery("delivery-1", "run-1:reply"));

        assertTrue(outbox.claimNextDelivery().isPresent());
        assertTrue(outbox.claimNextDelivery().isEmpty(), "已被认领的投递不能被第二个 worker 拿走");
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM platform_channel_delivery WHERE delivery_id='delivery-1'", Integer.class));
    }

    @Test
    void outcomeStateMachineKeepsRetryableReclaimableAndUncertainParked() {
        outbox.enqueue(delivery("delivery-retry", "run-1:reply-retry"));
        outbox.claimNextDelivery();
        outbox.recordOutcome("delivery-retry", result(
                ChannelOutboundSender.DeliveryResult.Status.RETRYABLE_FAILURE, null));
        assertTrue(outbox.claimNextDelivery().isPresent(), "可重试失败必须留回队列，等待下一轮认领");

        outbox.recordOutcome("delivery-retry", result(
                ChannelOutboundSender.DeliveryResult.Status.DELIVERED, "ext-1"));
        assertTrue(outbox.claimNextDelivery().isEmpty(), "已投递的不能再次发送");
        assertEquals("ext-1", jdbc.queryForObject(
                "SELECT external_message_id FROM platform_channel_delivery WHERE delivery_id='delivery-retry'",
                String.class));

        outbox.enqueue(delivery("delivery-uncertain", "run-1:reply-uncertain"));
        outbox.claimNextDelivery();
        outbox.recordOutcome("delivery-uncertain", result(
                ChannelOutboundSender.DeliveryResult.Status.UNCERTAIN, null));
        assertTrue(outbox.claimNextDelivery().isEmpty(),
                "结果不确定的投递必须停下等人工核查，绝不能自动重发");
    }

    @Test
    void channelSessionRepliesAreEnqueuedOncePerRun() {
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                        + "credential_ref,default_employee_id,enabled,created_at,updated_at) "
                        + "VALUES('feishu-main',7,'feishu','cli_app','env:feishu-main',11,TRUE,?,?)",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        jdbc.update("INSERT INTO platform_channel_conversation(binding_id,external_conversation_id,session_id,"
                        + "created_at,updated_at) VALUES('feishu-main','chat-1','session-1',?,?)",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        jdbc.update("INSERT INTO platform_channel_inbox(binding_id,provider_event_id,provider,"
                        + "external_conversation_id,external_user_id,user_id,session_id,run_id,reply_target,"
                        + "content,accepted_at) VALUES('feishu-main','event-1','feishu','chat-1','ou-1',23,"
                        + "'session-1','run-1','chat-1','hello',?)", java.sql.Timestamp.from(T0));
        JdbcChannelReplyEnqueuer enqueuer = new JdbcChannelReplyEnqueuer(jdbc, outbox);

        enqueuer.enqueueReply(RUN, new SessionId("session-1"), "答复正文");
        enqueuer.enqueueReply(RUN, new SessionId("session-1"), "答复正文");

        assertEquals(1, count(), "同一个 Run 的答复只能入队一次，幂等键必须是 runId:reply");
        ChannelDelivery queued = outbox.claimNextDelivery().orElseThrow();
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
