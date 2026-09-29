package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.observability.AgentExecutionObserver;
import com.haizhuo.brain.observability.LangfuseProperties;
import com.haizhuo.brain.runtime.agentscope.AgentScopeRuntime;
import com.haizhuo.brain.runtime.agentscope.config.AgentScopeRuntimeProperties;
import com.haizhuo.brain.runtime.agentscope.context.RuntimeContextFactory;
import com.haizhuo.brain.runtime.agentscope.event.AgentScopeEventTranslator;
import com.haizhuo.brain.runtime.agentscope.factory.AgentScopeModelFactory;
import com.haizhuo.brain.runtime.agentscope.factory.DefinitionWorkspaceMaterializer;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessAgentFactory;
import com.haizhuo.brain.runtime.agentscope.factory.HarnessTemplateCache;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 装配 Harness 运行时技术栈（规格 §18–§30）。整条技术栈以 run-worker 是否启用为条件，
 * 这样没有模型凭据的部署仍然可以提供管理端 API。AgentStateStore 直接用 AgentScope 自带的
 * JDBC 存储 + MySQL 方言（规格 §22 写的是 "MysqlAgentStateStore"；2.0.3 实际提供的是
 * JdbcAgentStateStore + MysqlDialect）。它会在构造器里校验会话表，表缺失即快速失败，
 * 因此该 DDL 归 Flyway 迁移 V13__agentscope_session_state 所有
 * （不要用 createIfNotExist=true 去"修"一个缺失的表——那只会掩盖一个没迁移的库）。
 * 在出现 durable BaseStore 之前，远程工作区文件系统保持未接线——
 * 此时 HarnessAgentFactory 不带工作区文件面运行（已在文档中记录为 P0 缺口）。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AgentScopeRuntimeProperties.class, RunWorkerProperties.class})
@ConditionalOnProperty(prefix = "haizhuo.brain.run-worker", name = "enabled", havingValue = "true")
public class AgentRuntimeConfiguration {

    /**
     * 2 参构造 = 严格模式：表由 V13 迁移预先建好，缺失即抛 IllegalStateException。
     * AgentStateStoreSchemaMigrationTest 守住迁移脚本与方言 schema 的一致性。
     */
    @Bean
    AgentStateStore agentStateStore(DataSource dataSource) {
        return new JdbcAgentStateStore(dataSource, new MysqlDialect());
    }

    @Bean
    AgentScopeModelFactory agentScopeModelFactory(AgentScopeRuntimeProperties properties) {
        return new AgentScopeModelFactory(properties);
    }

    @Bean
    DefinitionWorkspaceMaterializer definitionWorkspaceMaterializer(AgentScopeRuntimeProperties properties) {
        return new DefinitionWorkspaceMaterializer(Path.of(properties.definitionWorkspaceDir()));
    }

    @Bean
    HarnessAgentFactory harnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                                            DefinitionWorkspaceMaterializer workspaceMaterializer,
                                            RunControlInbox controlInbox, LangfuseProperties langfuse,
                                            ObjectProvider<Tracer> tracer) {
        Tracer otelTracer = tracer.getIfAvailable(
                () -> GlobalOpenTelemetry.getTracer("com.haizhuo.brain"));
        return new HarnessAgentFactory(modelFactory, agentStateStore, null, workspaceMaterializer, controlInbox,
                new com.haizhuo.brain.runtime.agentscope.middleware.ObservabilityMiddleware(
                        otelTracer, langfuse), langfuse.isEnabled());
    }

    @Bean
    HarnessTemplateCache harnessTemplateCache(HarnessAgentFactory factory) {
        return new HarnessTemplateCache(factory);
    }

    @Bean
    RuntimeContextFactory runtimeContextFactory() {
        return new RuntimeContextFactory();
    }

    @Bean
    AgentScopeEventTranslator agentScopeEventTranslator() {
        return new AgentScopeEventTranslator();
    }

    @Bean
    AgentRuntime agentRuntime(HarnessTemplateCache templateCache, RuntimeContextFactory contextFactory,
                              AgentScopeEventTranslator translator,
                              AgentExecutionObserver observer) {
        return new AgentScopeRuntime(templateCache, contextFactory, translator, observer);
    }
}
