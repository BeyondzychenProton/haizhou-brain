package com.haizhuo.brain.runtime.agentscope.factory;

import io.agentscope.harness.agent.HarnessAgent;
import java.time.Instant;
import java.util.Objects;

/**
 * 一个已缓存的 HarnessAgent 及其内容寻址键（规格 §19）。关闭模板即关闭底层 agent，
 * 生命周期归缓存所有。
 */
public record HarnessRuntimeTemplate(HarnessTemplateKey key, HarnessAgent agent, Instant createdAt)
        implements AutoCloseable {

    public HarnessRuntimeTemplate {
        Objects.requireNonNull(key);
        Objects.requireNonNull(agent);
        Objects.requireNonNull(createdAt);
    }

    @Override
    public void close() {
        agent.close();
    }
}
