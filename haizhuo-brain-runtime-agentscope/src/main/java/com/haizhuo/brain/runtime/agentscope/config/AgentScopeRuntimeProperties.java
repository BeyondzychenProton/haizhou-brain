package com.haizhuo.brain.runtime.agentscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "haizhuo.brain.agent")
public record AgentScopeRuntimeProperties(String provider, String primaryModel) {
}
