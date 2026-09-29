package com.haizhuo.brain.platform.employee;

/** 平台用户对能力编码的当前授权状态；不表示目标资源级权限。 */
public record UserCapabilityGrant(long userId, String capabilityCode, boolean enabled) {
}
