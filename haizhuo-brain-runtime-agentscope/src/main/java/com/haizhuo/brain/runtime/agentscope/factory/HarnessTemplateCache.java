package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0 模板缓存（规格 §20）：仅使用 ConcurrentHashMap + computeIfAbsent。
 * 没有 LRU、没有容量上限、没有访问过期——这些留到后续版本（线上同时存在大量定义版本时）
 * 再引入。P0 下模板不会被驱逐，因此调用方不得关闭返回的模板。
 */
public class HarnessTemplateCache {

    private final ConcurrentHashMap<String, HarnessRuntimeTemplate> cache = new ConcurrentHashMap<>();
    private final HarnessAgentFactory factory;

    public HarnessTemplateCache(HarnessAgentFactory factory) {
        this.factory = Objects.requireNonNull(factory);
    }

    public HarnessRuntimeTemplate getOrBuild(RuntimeDefinitionSnapshot definition) {
        HarnessTemplateKey key = HarnessTemplateKey.from(definition);
        return cache.computeIfAbsent(key.value(), ignored -> factory.build(definition, key));
    }

    /** 仅供测试观察缓存规模。 */
    public int size() {
        return cache.size();
    }
}
