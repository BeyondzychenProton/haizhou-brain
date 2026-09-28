package com.haizhuo.brain.api.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.channel.ChannelAcceptance;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountDirectory;
import com.haizhuo.brain.platform.channel.ChannelIngressService;
import com.haizhuo.brain.platform.channel.VerifiedChannelMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 渠道入站回调（P3 补全）：模拟 provider 的真实 HTTP 入口。
 *
 * <p>三件事的顺序不可调换，否则渠道侧会把平台当成可被伪造的消息源：</p>
 * <ol>
 *   <li>先校验签名（HMAC-SHA256，常量时间比较）——不通过一律 401，且**不写任何数据**；</li>
 *   <li>再按消息里的 bindingId 反查服务端持有的绑定（路由信息绝不采用消息体自报值）；</li>
 *   <li>最后交给 {@link ChannelIngressService} 受理（去重、会话映射与 Run 排队都在那里）。</li>
 * </ol>
 *
 * <p>换真实 IM 时只需替换验签实现与载荷解析：本类的三段结构与拒绝语义保持不变。</p>
 */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SIGNATURE_HEADER = "X-Channel-Signature";

    private final ChannelIngressService ingress;
    private final ChannelAccountDirectory accounts;
    private final byte[] webhookSecret;

    public ChannelWebhookController(ChannelIngressService ingress, ChannelAccountDirectory accounts,
                                    @Value("${haizhuo.brain.channel.webhook-secret:}") String webhookSecret) {
        this.ingress = ingress;
        this.accounts = accounts;
        this.webhookSecret = webhookSecret == null ? new byte[0] : webhookSecret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/{provider}/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> inbound(@PathVariable String provider,
                                                @RequestHeader(value = SIGNATURE_HEADER, required = false)
                                                String signature,
                                                @RequestBody String body) {
        return Mono.fromCallable(() -> handle(provider, signature, body))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Object> handle(String provider, String signature, String body) {
        if (webhookSecret.length == 0) {
            // 未配置密钥就不能受理：否则任何人都能伪造渠道消息。
            log.warn("channel.webhook.rejected reason=no-secret provider={}", provider);
            return ResponseEntity.status(503).body(new WebhookError("CHANNEL_WEBHOOK_DISABLED",
                    "Channel webhook is not configured"));
        }
        if (!validSignature(signature, body)) {
            log.warn("channel.webhook.rejected reason=signature provider={}", provider);
            return ResponseEntity.status(401).body(new WebhookError("CHANNEL_SIGNATURE_INVALID",
                    "Channel webhook signature is invalid"));
        }
        InboundPayload payload;
        try {
            payload = parse(body);
        } catch (IllegalArgumentException malformed) {
            return ResponseEntity.badRequest().body(new WebhookError("CHANNEL_PAYLOAD_INVALID",
                    "Channel webhook payload is invalid"));
        }
        Optional<ChannelAccountBinding> binding = accounts.findById(payload.bindingId());
        if (binding.isEmpty() || !binding.get().provider().equals(provider)) {
            // 绑定不存在或与 URL 上的 provider 不一致：不暴露差异细节。
            return ResponseEntity.status(403).body(new WebhookError("CHANNEL_BINDING_REJECTED",
                    "Channel binding is not available"));
        }
        try {
            ChannelAcceptance accepted = ingress.accept(new VerifiedChannelMessage(payload.bindingId(), provider,
                    payload.eventId(), payload.conversationId(), payload.senderId(), payload.text(),
                    payload.replyTarget()));
            return ResponseEntity.ok(new WebhookAccepted(accepted.sessionId().value(),
                    accepted.runId().map(runId -> runId.value()).orElse(null), accepted.duplicate()));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            // 身份未关联、员工不可用等都属于"渠道侧无权继续"，不把内部原因回给渠道。
            log.warn("channel.webhook.rejected reason=ingress provider={} errorType={}", provider,
                    rejected.getClass().getSimpleName());
            return ResponseEntity.status(403).body(new WebhookError("CHANNEL_INGRESS_REJECTED",
                    "Channel message was rejected"));
        }
    }

    /** 常量时间比较，避免用响应时间反推签名。 */
    private boolean validSignature(String signature, String body) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] provided = HexFormat.of().parseHex(signature.trim());
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception malformedSignature) {
            return false;
        }
    }

    private static InboundPayload parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            String conversationId = required(node, "conversationId");
            return new InboundPayload(required(node, "bindingId"), required(node, "eventId"), conversationId,
                    required(node, "senderId"), required(node, "text"),
                    text(node, "replyTarget").orElse(conversationId));
        } catch (Exception malformed) {
            throw new IllegalArgumentException("Channel webhook payload is not valid JSON");
        }
    }

    private static String required(JsonNode node, String field) {
        return text(node, field).orElseThrow(() -> new IllegalArgumentException(field + " is required"));
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? Optional.of(value.asText()) : Optional.empty();
    }

    public record WebhookAccepted(String sessionId, String runId, boolean duplicate) {
    }

    public record WebhookError(String code, String message) {
    }

    private record InboundPayload(String bindingId, String eventId, String conversationId, String senderId,
                                  String text, String replyTarget) {
    }
}
