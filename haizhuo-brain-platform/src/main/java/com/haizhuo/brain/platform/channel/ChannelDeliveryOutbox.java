package com.haizhuo.brain.platform.channel;

import java.util.Optional;

/** Durable delivery claims and status updates, separate from Run completion. */
public interface ChannelDeliveryOutbox {
    Optional<ChannelDelivery> claimNextDelivery();

    /** Record delivered, retryable, permanent or uncertain outcome without blindly resending. */
    void recordOutcome(String deliveryId, ChannelOutboundSender.DeliveryResult result);
}
