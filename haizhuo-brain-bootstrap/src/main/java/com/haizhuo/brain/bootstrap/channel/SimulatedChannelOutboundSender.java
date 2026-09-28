package com.haizhuo.brain.bootstrap.channel;

import com.haizhuo.brain.platform.channel.ChannelDelivery;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模拟渠道的发送器（P3 补全）：用真实 HTTP 契约把「出站投递」这段链路跑通，
 * 换真实 IM 时只需替换本类，平台侧的调度、认领、状态机与幂等都不用动。
 *
 * <p>结果分级是这里唯一需要小心的地方，因为它决定会不会重复打扰用户：</p>
 * <ul>
 *   <li>2xx → 已投递</li>
 *   <li>429/5xx → 可重试失败（对方明确没接受）</li>
 *   <li>其余 4xx → 永久失败（重试也不会成功）</li>
 *   <li>超时/连接中断 → **结果不确定**：对方可能已经收到，绝不自动重发</li>
 * </ul>
 *
 * <p>未配置端点时只记录并视为已投递，便于在没有外部依赖的环境里验证调度与状态机。</p>
 */
public class SimulatedChannelOutboundSender implements ChannelOutboundSender {

    private static final Logger log = LoggerFactory.getLogger(SimulatedChannelOutboundSender.class);
    private static final String PROVIDER = "simulated";

    private final String endpoint;
    private final HttpClient http;

    public SimulatedChannelOutboundSender(String endpoint) {
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public DeliveryResult send(ChannelDelivery delivery) {
        if (endpoint == null || endpoint.isBlank()) {
            log.info("channel.simulated.local-delivery deliveryId={} replyTarget={}",
                    delivery.deliveryId(), delivery.replyTarget());
            return new DeliveryResult(DeliveryResult.Status.DELIVERED, "sim-" + delivery.deliveryId());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", delivery.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(payload(delivery)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status / 100 == 2) {
                return new DeliveryResult(DeliveryResult.Status.DELIVERED,
                        Optional.ofNullable(response.headers().firstValue("X-Message-Id").orElse(null))
                                .orElse("sim-" + delivery.deliveryId()));
            }
            if (status == 429 || status >= 500) {
                return new DeliveryResult(DeliveryResult.Status.RETRYABLE_FAILURE, null);
            }
            return new DeliveryResult(DeliveryResult.Status.PERMANENT_FAILURE, null);
        } catch (IOException transportFailure) {
            // 无法判定对方是否已收到：标为不确定，交给人工核查而不是重发。
            return new DeliveryResult(DeliveryResult.Status.UNCERTAIN, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(DeliveryResult.Status.UNCERTAIN, null);
        }
    }

    /** 只带投递必需字段：不把绑定、凭据或内部标识发到渠道侧。 */
    private static String payload(ChannelDelivery delivery) {
        return "{\"to\":\"" + escape(delivery.replyTarget()) + "\",\"text\":\"" + escape(delivery.text())
                + "\",\"runId\":\"" + escape(delivery.runId().value()) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
