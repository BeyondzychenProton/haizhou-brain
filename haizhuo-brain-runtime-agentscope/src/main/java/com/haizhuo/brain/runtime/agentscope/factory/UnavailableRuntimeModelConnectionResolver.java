package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;

/** 专用 Vault 集成配置完成前使用的默认拒绝解析器。 */
public final class UnavailableRuntimeModelConnectionResolver implements RuntimeModelConnectionResolver {
    @Override
    public ResolvedModelConnection resolve(RuntimeModelConnectionRef reference, String expectedProvider,
                                           String modelName) {
        throw new ModelConnectionUnavailableException("Credential reference resolution is not configured");
    }
}
