package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.UserId;
import java.time.Instant;

/** 外部用户到平台用户的身份关联；只能由管理侧经校验的绑定流程写入。 */
public record ChannelIdentityBinding(String bindingId, String externalUserId, UserId userId,
                                     ChannelIdentityState state, Instant linkedAt, Instant updatedAt) {
}
