package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import io.agentscope.core.model.Model;
import java.util.Objects;

/** 仅在内存中短暂使用的连接租约，不得放入快照或持久化状态。 */
public record ResolvedModelConnection(RuntimeModelConnectionRef reference, String provider,
                                     String modelName, Model model, Runnable release) implements AutoCloseable {
    public ResolvedModelConnection {
        Objects.requireNonNull(reference);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(modelName);
        Objects.requireNonNull(model);
        Objects.requireNonNull(release);
    }

    @Override
    public void close() {
        release.run();
    }

    @Override
    public String toString() {
        return "ResolvedModelConnection[reference=" + reference + ", provider=" + provider
                + ", modelName=" + modelName + ", model=<redacted>, release=<lease>]";
    }
}
