package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;

/** 为一次模型调用订阅解析唯一且不含秘密信息的版本引用。 */
@FunctionalInterface
public interface RuntimeModelConnectionResolver {
    ResolvedModelConnection resolve(RuntimeModelConnectionRef reference, String expectedProvider, String modelName);
}
