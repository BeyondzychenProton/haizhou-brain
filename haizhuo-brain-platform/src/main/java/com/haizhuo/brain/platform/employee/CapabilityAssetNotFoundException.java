package com.haizhuo.brain.platform.employee;

/** 用于新管理查询端点的安全 404；不暴露资产或修订的内部查询细节。 */
public class CapabilityAssetNotFoundException extends RuntimeException {
    public CapabilityAssetNotFoundException() {
        super("Capability asset was not found");
    }
}
