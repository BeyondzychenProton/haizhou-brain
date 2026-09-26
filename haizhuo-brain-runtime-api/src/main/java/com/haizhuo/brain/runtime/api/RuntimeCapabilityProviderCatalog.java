package com.haizhuo.brain.runtime.api;

/** 平台发布校验与运行时装配共用的受控提供者目录。 */
public interface RuntimeCapabilityProviderCatalog {
    boolean supports(String implementationKey, String capabilityCode);
}
