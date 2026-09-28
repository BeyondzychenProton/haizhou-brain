package com.haizhuo.brain.platform.channel;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 出站投递 worker（P3）：认领一条投递 → 交给对应 provider 的发送器 → 记录结果。
 *
 * <p>失败分级是这段逻辑的重点，因为渠道投递无法回滚：
 * 可重试失败留回队列等待下一轮；永久失败不再重试；
 * 发送过程抛异常意味着"可能已经发出"，只能记为结果不确定并交人工核查，绝不自动重发。</p>
 */
public class ChannelDeliveryWorker {

    private final ChannelDeliveryOutbox outbox;
    private final Map<String, ChannelOutboundSender> senders;

    public ChannelDeliveryWorker(ChannelDeliveryOutbox outbox, Map<String, ChannelOutboundSender> senders) {
        this.outbox = Objects.requireNonNull(outbox);
        this.senders = Map.copyOf(Objects.requireNonNull(senders));
    }

    /** @return 是否处理了一条投递；false 表示当前没有待投递项。 */
    public boolean deliverNext() {
        Optional<ChannelDelivery> claimed = outbox.claimNextDelivery();
        if (claimed.isEmpty()) {
            return false;
        }
        ChannelDelivery delivery = claimed.get();
        ChannelOutboundSender sender = senders.get(delivery.provider());
        if (sender == null) {
            // 没有发送器说明配置缺失；记为永久失败，避免这条记录无限占用队列头部。
            outbox.recordOutcome(delivery.deliveryId(), new ChannelOutboundSender.DeliveryResult(
                    ChannelOutboundSender.DeliveryResult.Status.PERMANENT_FAILURE, null));
            return true;
        }
        try {
            outbox.recordOutcome(delivery.deliveryId(), sender.send(delivery));
        } catch (RuntimeException error) {
            // 异常时不带出响应正文：只按"结果不确定"落库。
            outbox.recordOutcome(delivery.deliveryId(), new ChannelOutboundSender.DeliveryResult(
                    ChannelOutboundSender.DeliveryResult.Status.UNCERTAIN, null));
        }
        return true;
    }

    /** 连续投递直到队列为空或达到上限，供定时任务调用。 */
    public int deliverBatch(int maxDeliveries) {
        int delivered = 0;
        while (delivered < Math.max(maxDeliveries, 0) && deliverNext()) {
            delivered++;
        }
        return delivered;
    }
}
