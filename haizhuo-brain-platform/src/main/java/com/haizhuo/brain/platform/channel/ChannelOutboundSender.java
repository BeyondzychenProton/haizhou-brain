package com.haizhuo.brain.platform.channel;

/** 面向具体供应商的发送器，只由后续的 outbox worker 调用。 */
public interface ChannelOutboundSender {
    String provider();
    DeliveryResult send(ChannelDelivery delivery);

    record DeliveryResult(Status status, String externalMessageId) {
        public enum Status { DELIVERED, RETRYABLE_FAILURE, PERMANENT_FAILURE, UNCERTAIN }
    }
}
