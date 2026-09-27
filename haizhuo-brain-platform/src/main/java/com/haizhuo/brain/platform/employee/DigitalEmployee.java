package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.kernel.identity.TenantId;

/** 面向用户、可被渠道寻址的身份；内部子 agent 不属于员工。 */
public record DigitalEmployee(long id, TenantId tenantId, String code, String displayName, boolean enabled) {
}
