package com.haizhuo.brain.platform.channel;

/** Provider-specific sender called only by a future outbox worker. */
public interface ChannelOutboundSender {
    String provider();
    DeliveryResult send(ChannelDelivery delivery);

    record DeliveryResult(Status status, String externalMessageId) {
        public enum Status { DELIVERED, RETRYABLE_FAILURE, PERMANENT_FAILURE, UNCERTAIN }
    }
}
