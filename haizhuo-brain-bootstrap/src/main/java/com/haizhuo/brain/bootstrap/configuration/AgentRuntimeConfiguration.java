package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.runtime.agentscope.AgentScopeRuntime;
import com.haizhuo.brain.runtime.agentscope.AgentScopeToolkitAssembler;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AgentScopeRuntimeProperties.class, RunWorkerProperties.class})
public class AgentRuntimeConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "haizhuo.brain.run-worker", name = "enabled", havingValue = "true")
    AgentRuntime agentRuntime(AgentScopeRuntimeProperties properties, ToolExecutionGateway gateway, AgentScopeToolkitAssembler assembler) {
        return new AgentScopeRuntime(properties, gateway, assembler);
    }

    /** No capability is executable until a separate platform tool gateway is implemented and authorized. */
    @Bean
    ToolExecutionGateway toolExecutionGateway() {
        return (run, capability, arguments) -> { throw new IllegalStateException("No platform tool gateway is configured"); };
    }
}
