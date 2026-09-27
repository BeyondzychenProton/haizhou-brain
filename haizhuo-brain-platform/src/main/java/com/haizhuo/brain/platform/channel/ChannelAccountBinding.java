package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.TenantId;

/** 服务端持有的渠道账号路由；绝不能从消息体里拷贝。 */
public record ChannelAccountBinding(String bindingId, TenantId tenantId, String provider,
                                    String externalAccountKey, String credentialRef,
                                    long defaultEmployeeId, boolean enabled) {
}
