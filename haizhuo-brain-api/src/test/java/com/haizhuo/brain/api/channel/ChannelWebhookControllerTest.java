package com.haizhuo.brain.api.channel;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.channel.ChannelAcceptance;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.SessionScope;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

/**
 * 渠道回调的拒绝语义（P3 补全）：这是唯一一个匿名可达的写入口，
 * 因此每一条拒绝路径都必须"先拒绝、后处理"，且不得触碰受理链路。
 */
@ExtendWith(MockitoExtension.class)
class ChannelWebhookControllerTest {

    private static final String SECRET = "e2e-webhook-secret";
    private static final String PROVIDER = "simulated";
    private static final ChannelAccountBinding BINDING = new ChannelAccountBinding("sim-account", new TenantId(1),
            PROVIDER, "sim-key", "env:sim", 2L, SessionScope.PER_PEER, true);

    @Mock ChannelIngressService ingress;
    @Mock ChannelAccountDirectory accounts;

    @Test
    void invalidSignatureIsRejectedWithoutTouchingIngress() {
        ChannelWebhookController controller = controller(SECRET);

        ResponseEntity<Object> response = controller.inbound(PROVIDER, "deadbeef", body()).block();

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(ingress, accounts);
    }

    @Test
    void missingSignatureIsRejectedAsWell() {
        ChannelWebhookController controller = controller(SECRET);

        assertEquals(401, controller.inbound(PROVIDER, null, body()).block().getStatusCode().value());
        verifyNoInteractions(ingress);
    }

    @Test
    void unconfiguredSecretRefusesAllTraffic() {
        // 没有密钥就无法区分真伪渠道，宁可整体拒服务，也不能放行伪造消息。
        ChannelWebhookController controller = controller("");

        assertEquals(503, controller.inbound(PROVIDER, "anything", body()).block().getStatusCode().value());
        verifyNoInteractions(ingress, accounts);
    }

    @Test
    void bindingMismatchOrMissingBindingIsRejected() throws Exception {
        ChannelWebhookController controller = controller(SECRET);
        when(accounts.findById("sim-account")).thenReturn(Optional.empty());

        ResponseEntity<Object> response = controller.inbound(PROVIDER, signature(body()), body()).block();

        assertEquals(403, response.getStatusCode().value());
        verifyNoInteractions(ingress);
    }

    @Test
    void signedRequestIsAcceptedAndReturnsRunIdentity() throws Exception {
        ChannelWebhookController controller = controller(SECRET);
        when(accounts.findById("sim-account")).thenReturn(Optional.of(BINDING));
        when(ingress.accept(any())).thenReturn(new ChannelAcceptance(new SessionId("session-1"),
                Optional.of(new RunId("run-1")), false));

        ResponseEntity<Object> response = controller.inbound(PROVIDER, signature(body()), body()).block();

        assertEquals(200, response.getStatusCode().value());
        ChannelWebhookController.WebhookAccepted accepted =
                (ChannelWebhookController.WebhookAccepted) response.getBody();
        assertEquals("session-1", accepted.sessionId());
        assertEquals("run-1", accepted.runId());
        assertTrue(!accepted.duplicate());
        verify(ingress).accept(any());
    }

    @Test
    void waitingAcceptanceReturnsNoRunId() throws Exception {
        // 会话忙时平台只受理不建 Run：回调必须如实回一个空 runId，而不是装作已执行。
        ChannelWebhookController controller = controller(SECRET);
        when(accounts.findById("sim-account")).thenReturn(Optional.of(BINDING));
        when(ingress.accept(any())).thenReturn(new ChannelAcceptance(new SessionId("session-1"),
                Optional.empty(), false));

        ResponseEntity<Object> response = controller.inbound(PROVIDER, signature(body()), body()).block();

        assertEquals(200, response.getStatusCode().value());
        assertNull(((ChannelWebhookController.WebhookAccepted) response.getBody()).runId());
    }

    @Test
    void malformedPayloadIsRejectedBeforeBindingLookup() throws Exception {
        ChannelWebhookController controller = controller(SECRET);
        String malformed = "{\"eventId\":\"evt-1\"}";

        ResponseEntity<Object> response = controller.inbound(PROVIDER, signature(malformed), malformed).block();

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(ingress, accounts);
    }

    @Test
    void ingressRejectionIsReportedAsForbiddenWithoutLeakingReason() throws Exception {
        ChannelWebhookController controller = controller(SECRET);
        when(accounts.findById("sim-account")).thenReturn(Optional.of(BINDING));
        when(ingress.accept(any())).thenThrow(new IllegalArgumentException("Channel user is not linked"));

        ResponseEntity<Object> response = controller.inbound(PROVIDER, signature(body()), body()).block();

        assertEquals(403, response.getStatusCode().value());
        assertEquals("Channel message was rejected",
                ((ChannelWebhookController.WebhookError) response.getBody()).message(),
                "拒绝原因不得回给渠道侧");
    }

    private ChannelWebhookController controller(String secret) {
        return new ChannelWebhookController(ingress, accounts, secret);
    }

    private static String body() {
        return "{\"bindingId\":\"sim-account\",\"eventId\":\"evt-1\",\"conversationId\":\"chat-1\","
                + "\"senderId\":\"ou-1\",\"text\":\"你好\"}";
    }

    private static String signature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(UTF_8)));
    }
}
