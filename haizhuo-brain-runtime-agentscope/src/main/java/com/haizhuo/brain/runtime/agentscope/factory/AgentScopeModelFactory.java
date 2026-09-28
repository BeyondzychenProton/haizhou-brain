package com.haizhuo.brain.runtime.agentscope.factory;

import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.util.Locale;
import java.util.Objects;

/**
 * 为一个冻结定义创建会话模型（规格 §21 的输入“Model”）。供应商与模型名来自定义快照；
 * API Key 与 base URL 来自进程配置，绝不属于定义包的一部分（I-08）。
 */
public class AgentScopeModelFactory {

    private final AgentScopeRuntimeProperties properties;

    public AgentScopeModelFactory(AgentScopeRuntimeProperties properties) {
        this.properties = Objects.requireNonNull(properties);
    }

    public ChatModelBase create(RuntimeDefinitionSnapshot definition) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("LLM API key is missing from the configured agent model");
        }
        String provider = definition.modelProvider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "openai", "openai-compatible" -> {
                if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
                    throw new IllegalStateException("LLM base-url is missing from the configured agent model");
                }
                yield OpenAIChatModel.builder()
                        .apiKey(properties.apiKey())
                        .modelName(definition.modelName())
                        .baseUrl(properties.baseUrl())
                        .stream(properties.streamEnabled())
                        .build();
            }
            case "dashscope" -> {
                var builder = DashScopeChatModel.builder()
                        .apiKey(properties.apiKey())
                        .modelName(definition.modelName())
                        .stream(properties.streamEnabled());
                if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
                    builder.baseUrl(properties.baseUrl());
                }
                yield builder.build();
            }
            default -> throw new IllegalStateException("Unsupported LLM provider: " + definition.modelProvider());
        };
    }
}
