package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.TenantId;

/** User-facing, channel-addressable identity; internal subagents are not employees. */
public record DigitalEmployee(long id, TenantId tenantId, String code, String displayName, boolean enabled) {
}
