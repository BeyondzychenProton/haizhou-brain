package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeModelConnectionRef;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * 为一个冻结定义创建会话模型（规格 §21 的输入“Model”）。只有带精确非秘密连接引用的
 * 快照才能进入模型调用；本地部署配置不再作为未绑定快照的隐式回退。
 */
public class AgentScopeModelFactory {

    private final RuntimeModelConnectionResolver connectionResolver;

    public AgentScopeModelFactory(AgentScopeRuntimeProperties properties) {
        this(properties, new UnavailableRuntimeModelConnectionResolver());
    }

    public AgentScopeModelFactory(AgentScopeRuntimeProperties properties,
                                  RuntimeModelConnectionResolver connectionResolver) {
        Objects.requireNonNull(properties);
        this.connectionResolver = Objects.requireNonNull(connectionResolver);
    }

    public Model create(RuntimeDefinitionSnapshot definition) {
        RuntimeModelConnectionRef reference = definition.modelConnectionRef();
        if (reference == null) {
            throw new ModelConnectionUnavailableException(
                    "No frozen model connection reference is bound to this definition version");
        }
        return new ConnectionBoundModel(reference, definition.modelProvider(), definition.modelName());
    }

    private final class ConnectionBoundModel implements Model {
        private final RuntimeModelConnectionRef reference;
        private final String provider;
        private final String modelName;

        private ConnectionBoundModel(RuntimeModelConnectionRef reference, String provider, String modelName) {
            this.reference = reference;
            this.provider = provider;
            this.modelName = modelName;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.defer(() -> {
                ResolvedModelConnection resolved = connectionResolver.resolve(reference, provider, modelName);
                if (!reference.equals(resolved.reference())
                        || !provider.equalsIgnoreCase(resolved.provider())
                        || !modelName.equals(resolved.modelName())) {
                    closeQuietly(resolved);
                    return Flux.error(new ModelConnectionUnavailableException(
                            "Resolved model connection does not match the frozen reference"));
                }
                try {
                    return resolved.model().stream(messages, tools, options)
                            .doFinally(signal -> closeQuietly(resolved));
                } catch (RuntimeException failure) {
                    closeQuietly(resolved);
                    return Flux.error(failure);
                }
            });
        }

        @Override
        public String getModelName() {
            return modelName;
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return false;
        }

        @Override
        public boolean supportsNativeStructuredOutputWithTools() {
            return false;
        }

        @Override
        public int getContextWindowSize() {
            return 0;
        }
    }

    private static void closeQuietly(ResolvedModelConnection resolved) {
        try {
            resolved.close();
        } catch (RuntimeException ignored) {
            // 释放短期凭据租约时，不得覆盖模型调用的结果或异常。
        }
    }
}
