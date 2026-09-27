package com.haizhuo.brain.runtime.agentscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 进程内的运行时配置。apiKey/baseUrl 不进入任何定义能力包或快照（I-08）。
 * provider/primaryModel 只是历史兜底——冻结的定义快照才携带权威的
 * modelProvider/modelName。streamEnabled 控制模型是否流式输出
 * （TextBlockDeltaEvent 还是一次性结果），definitionWorkspaceDir 是物化定义工作区的本地根目录。
 */
@ConfigurationProperties(prefix = "haizhuo.brain.agent")
public record AgentScopeRuntimeProperties(String provider, String primaryModel, String apiKey, String baseUrl,
                                          Boolean streamEnabled, String definitionWorkspaceDir) {
    public AgentScopeRuntimeProperties {
        if (streamEnabled == null) streamEnabled = Boolean.TRUE;
        if (definitionWorkspaceDir == null || definitionWorkspaceDir.isBlank()) {
            definitionWorkspaceDir = "data/definition-workspace";
        }
    }
}
