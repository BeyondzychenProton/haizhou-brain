package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.ModelConnectionBindingStore;
import com.haizhuo.brain.platform.run.ModelConnectionBindingAgentRuntime;
import com.haizhuo.brain.platform.run.RunModelConnectionSnapshotBinder;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.RuntimeModelConnectionResolver;
import com.haizhuo.brain.runtime.agentscope.factory.UnavailableRuntimeModelConnectionResolver;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 装配模型连接快照绑定与解析器；默认按 fail-closed 方式处理。 */
@Configuration
@ConditionalOnProperty(prefix = "haizhuo.brain.run-worker", name = "enabled", havingValue = "true")
public class ModelConnectionRuntimeConfiguration {

    @Bean
    @Primary
    AgentScopeModelFactory connectionBoundAgentScopeModelFactory(
            AgentScopeRuntimeProperties properties, ObjectProvider<RuntimeModelConnectionResolver> resolvers,
            @Value("${haizhuo.brain.model-connections.resolution-enabled:false}") boolean resolutionEnabled) {
        RuntimeModelConnectionResolver resolver = resolutionEnabled
                ? resolvers.getIfAvailable(UnavailableRuntimeModelConnectionResolver::new)
                : new UnavailableRuntimeModelConnectionResolver();
        return new AgentScopeModelFactory(properties, resolver);
    }

    @Bean
    RunModelConnectionSnapshotBinder runModelConnectionSnapshotBinder(SessionRunStore sessions,
                                                                       ModelConnectionBindingStore bindings) {
        return new RunModelConnectionSnapshotBinder(sessions, bindings);
    }

    @Bean
    @Primary
    AgentRuntime modelConnectionBoundAgentRuntime(
            @Qualifier("agentRuntime") AgentRuntime delegate,
            RunModelConnectionSnapshotBinder binder) {
        return new ModelConnectionBindingAgentRuntime(delegate, binder);
    }
}
