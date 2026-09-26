package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.TenantId;

/** Server-owned channel account routing; never copied from a message body. */
public record ChannelAccountBinding(String bindingId, TenantId tenantId, String provider,
                                    String externalAccountKey, String credentialRef,
                                    long defaultEmployeeId, boolean enabled) {
}
