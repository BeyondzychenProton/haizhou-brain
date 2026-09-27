package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Optional;

/** 身份关联必须由独立的、经过校验的绑定流程建立。 */
public interface ChannelIdentityDirectory {
    Optional<UserId> resolve(TenantId tenantId, String bindingId, String externalUserId);
}
