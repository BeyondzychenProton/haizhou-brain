package com.haizhuo.brain.platform.capability;

/** 管理员诊断用的排除原因；不会投影进 AgentScope Toolkit。 */
public record CapabilityExclusion(String capabilityCode, String revision, String reasonCode) {
}
