package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.UserId;
import java.util.Optional;

/** Identity links must be established by a separate verified binding flow. */
public interface ChannelIdentityDirectory {
    Optional<UserId> resolve(TenantId tenantId, String bindingId, String externalUserId);
}
