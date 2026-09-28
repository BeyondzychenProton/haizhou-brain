package com.haizhuo.brain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.channel.ChannelAcceptance;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelDeliveryOutbox;
import com.haizhuo.brain.platform.channel.ChannelDeliveryWorker;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import com.haizhuo.brain.platform.channel.VerifiedChannelMessage;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * P3 渠道闭环真机验证（连本机 MySQL，手工运行，不在常规构建中执行）。
 *
 * <p>渠道入站没有对外 HTTP 入口（渠道适配层尚未接真实 provider），因此这里直接在真库上
 * 驱动平台侧服务，验证四条不变量：重投不重开 Run、身份解绑立即生效、投递幂等、结果不确定不重发。</p>
 *
 * <p>运行：
 * <pre>
 * JAVA_HOME=&lt;corretto-18&gt; mvn -pl haizhuo-brain-bootstrap -am -Denforcer.skip=true \
 *   -Dtest=ChannelIngressProbe -Dsurefire.failIfNoSpecifiedTests=false test
 * </pre>
 * 运行前需保证 {@code config/application-local.yml} 存在且指向真实 MySQL。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.config.additional-location=file:../config/",
                "haizhuo.brain.run-worker.enabled=false"})
@ActiveProfiles("local")
class ChannelIngressProbe {

    private static final String BINDING = "probe-binding";
    private static final String PROVIDER = "probe";
    private static final long EMPLOYEE = 2L;
    private static final long OWNER = 5L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ChannelIngressService ingress;
    @Autowired private ChannelDeliveryOutbox outbox;
    @Autowired private ChannelReplyEnqueuer replyEnqueuer;

    @BeforeEach
    void seedBindingAndIdentity() {
        cleanup();
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                        + "credential_ref,default_employee_id,enabled,created_at,updated_at) "
                        + "VALUES(?,1,?,?,?,?,TRUE,NOW(3),NOW(3))",
                BINDING, PROVIDER, "probe-account", "env:probe", EMPLOYEE);
        jdbc.update("INSERT INTO platform_channel_identity(binding_id,external_user_id,user_id,state,"
                        + "linked_at,updated_at) VALUES(?,'probe-user',?,'LINKED',NOW(3),NOW(3))",
                BINDING, OWNER);
    }

    @AfterEach
    void cleanup() {
        List<String> sessions = jdbc.queryForList(
                "SELECT session_id FROM platform_channel_conversation WHERE binding_id=?", String.class, BINDING);
        for (String sessionId : sessions) {
            List<String> runs = jdbc.queryForList("SELECT run_id FROM platform_agent_run WHERE session_id=?",
                    String.class, sessionId);
            for (String runId : runs) {
                jdbc.update("DELETE FROM platform_tool_execution WHERE run_id=?", runId);
                jdbc.update("DELETE FROM platform_agent_run_event WHERE run_id=?", runId);
                jdbc.update("DELETE FROM platform_run_execution_attempt WHERE run_id=?", runId);
                jdbc.update("DELETE FROM platform_agent_run_spec WHERE run_id=?", runId);
            }
            jdbc.update("DELETE FROM platform_agent_session_event WHERE session_id=?", sessionId);
            jdbc.update("DELETE FROM platform_agent_run WHERE session_id=?", sessionId);
        }
        // 渠道表必须先于 platform_agent_session 删除：conversation 对 session 有外键。
        jdbc.update("DELETE FROM platform_channel_delivery WHERE binding_id=?", BINDING);
        jdbc.update("DELETE FROM platform_channel_inbox WHERE binding_id=?", BINDING);
        jdbc.update("DELETE FROM platform_channel_conversation WHERE binding_id=?", BINDING);
        jdbc.update("DELETE FROM platform_channel_identity WHERE binding_id=?", BINDING);
        jdbc.update("DELETE FROM platform_channel_account WHERE binding_id=?", BINDING);
        for (String sessionId : sessions) {
            jdbc.update("DELETE FROM platform_agent_session WHERE session_id=?", sessionId);
        }
    }

    @Test
    void inboundIsDeduplicatedAndOutboundFlowsThroughTheOutbox() {
        ChannelAccountBinding binding = new ChannelAccountBinding(BINDING, new TenantId(1), PROVIDER,
                "probe-account", "env:probe", EMPLOYEE, true);
        assertTrue(binding.enabled(), "探针绑定必须启用");

        // 1) 首次受理：建会话、建 Run、写收件箱
        ChannelAcceptance first = ingress.accept(message("event-1"));
        assertFalse(first.duplicate(), "首次受理不应被标为重复");
        assertTrue(first.runId().isPresent(), "受理后必须产生一个待执行 Run");
        assertEquals(1, count("platform_channel_inbox"), "收件箱必须记录这次受理");
        assertEquals(1, count("platform_channel_conversation"), "外部会话必须映射到一个平台会话");

        // 2) 重投同一条外部消息：只能返回既有受理，绝不能再建 Run
        ChannelAcceptance again = ingress.accept(message("event-1"));
        assertTrue(again.duplicate(), "重复投递必须被识别");
        assertEquals(first.runId(), again.runId(), "重投必须命中同一个 Run");
        assertEquals(1, count("platform_channel_inbox"), "重投不得产生第二条收件箱记录");
        assertEquals(1, runsOf(first.sessionId()), "重投不得再建 Run");

        // 3) 同一会话进入下一条消息：平台在数据库层强制单会话唯一活动 Run，
        //    因此这条消息必须登记为"已受理、等待中"，而不是撞唯一约束把正常入站变成故障。
        ChannelAcceptance second = ingress.accept(message("event-2"));
        assertEquals(first.sessionId(), second.sessionId(), "同一外部会话必须复用平台会话");
        assertTrue(second.runId().isEmpty(), "会话已有活动 Run 时新消息必须登记为等待");
        assertEquals(1, runsOf(first.sessionId()), "会话忙时不得再建第二个活动 Run");
        assertEquals(2, count("platform_channel_inbox"), "等待中的消息也必须留痕，否则会丢失");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_inbox "
                + "WHERE binding_id=? AND run_id IS NULL", Integer.class, BINDING), "等待中的记录以空 run_id 标记");

        // 4) 解绑后立即不能再解析身份（解绑等于没生效是不可接受的）
        jdbc.update("UPDATE platform_channel_identity SET state='REVOKED' WHERE binding_id=?", BINDING);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_identity "
                + "WHERE binding_id=? AND state='LINKED'", Integer.class, BINDING));
    }

    @Test
    void replyIsEnqueuedOnceAndUncertainDeliveryIsNeverResent() {
        ChannelAccountBinding binding = new ChannelAccountBinding(BINDING, new TenantId(1), PROVIDER,
                "probe-account", "env:probe", EMPLOYEE, true);
        ChannelAcceptance accepted = ingress.accept(message("event-reply"));
        RunId runId = accepted.runId().orElseThrow();

        // 1) Run 的最终答复按 runId:reply 幂等入队
        replyEnqueuer.enqueueReply(runId, accepted.sessionId(), "这是渠道答复");
        replyEnqueuer.enqueueReply(runId, accepted.sessionId(), "这是渠道答复");
        assertEquals(1, count("platform_channel_delivery"), "同一个 Run 的答复只能入队一次");

        // 2) 发送成功的投递不再被认领
        AtomicInteger sent = new AtomicInteger();
        ChannelOutboundSender sender = new ChannelOutboundSender() {
            @Override public String provider() {
                return PROVIDER;
            }

            @Override public DeliveryResult send(ChannelDelivery delivery) {
                sent.incrementAndGet();
                return new DeliveryResult(DeliveryResult.Status.DELIVERED, "probe-ext-1");
            }
        };
        ChannelDeliveryWorker worker = new ChannelDeliveryWorker(outbox, Map.of(PROVIDER, sender));
        assertTrue(worker.deliverNext(), "应认领到一条待投递");
        assertFalse(worker.deliverNext(), "队列已空");
        assertEquals(1, sent.get(), "已投递的不能再次发送");
        assertEquals("DELIVERED", stateOf("probe-ext-1"));

        // 3) 结果不确定的投递必须停下等人工核查
        outbox.enqueue(new ChannelDelivery("probe-delivery-uncertain", runId, BINDING, PROVIDER, "probe-chat",
                "结果不确定的投递", "probe:uncertain"));
        ChannelDeliveryWorker flaky = new ChannelDeliveryWorker(outbox, Map.of(PROVIDER, new ChannelOutboundSender() {
            @Override public String provider() {
                return PROVIDER;
            }

            @Override public DeliveryResult send(ChannelDelivery delivery) {
                throw new IllegalStateException("provider timeout");
            }
        }));
        assertTrue(flaky.deliverNext(), "异常也必须落一条结果");
        assertFalse(flaky.deliverNext(), "结果不确定的投递不得被自动重发");
        assertEquals("UNCERTAIN", jdbc.queryForObject("SELECT state FROM platform_channel_delivery "
                + "WHERE delivery_id=(SELECT delivery_id FROM platform_channel_delivery "
                + "WHERE binding_id=? AND state='UNCERTAIN' LIMIT 1)", String.class, BINDING));
    }

    private String stateOf(String externalMessageId) {
        String state = jdbc.queryForObject("SELECT state FROM platform_channel_delivery WHERE external_message_id=?",
                String.class, externalMessageId);
        assertNotNull(state);
        return state;
    }

    private int runsOf(SessionId sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run WHERE session_id=?",
                Integer.class, sessionId.value());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE binding_id=?", Integer.class, BINDING);
    }

    private static VerifiedChannelMessage message(String eventId) {
        return new VerifiedChannelMessage(BINDING, PROVIDER, eventId, "probe-chat", "probe-user",
                "渠道入站消息 " + eventId, "probe-chat");
    }
}
