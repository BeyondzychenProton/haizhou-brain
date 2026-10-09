package com.haizhuo.brain.platform.channel;

import java.util.Optional;

/** 持久化的投递认领与状态更新，与 Run 完成状态解耦。 */
public interface ChannelDeliveryOutbox {
    Optional<ChannelDeliveryClaim> claimNextDelivery();

    /**
     * 记录已投递、可重试、永久性或结果不确定的投递结果。
     * 只接受本次 claim 的代次与私有 token；过期或已被替代的 sender 结果返回 STALE_CLAIM。
     */
    DeliveryOutcomeWrite recordOutcome(ChannelDeliveryClaim claim, ChannelOutboundSender.DeliveryResult result);

    /**
     * 在调用供应商之前落库的投递意图（P3）。同一 idempotencyKey 只入队一次，
     * 重复入队返回既有记录——这样"已受理但未投递"的状态可查、可在重启后继续。
     */
    ChannelDelivery enqueue(ChannelDelivery delivery);
}
