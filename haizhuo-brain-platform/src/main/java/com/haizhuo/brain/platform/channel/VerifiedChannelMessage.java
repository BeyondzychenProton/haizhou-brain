package com.haizhuo.brain.platform.channel;

/** 只有渠道适配器在认证 Web 用户或 webhook 之后才能创建该对象。 */
public record VerifiedChannelMessage(String bindingId, String provider, String providerEventId,
                                     String externalConversationId, String externalUserId,
                                     String text, String replyTarget) {
    public VerifiedChannelMessage {
        require(bindingId, "bindingId");
        require(provider, "provider");
        require(providerEventId, "providerEventId");
        require(externalConversationId, "externalConversationId");
        require(externalUserId, "externalUserId");
        require(text, "text");
        if (replyTarget == null) throw new IllegalArgumentException("replyTarget is required");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
