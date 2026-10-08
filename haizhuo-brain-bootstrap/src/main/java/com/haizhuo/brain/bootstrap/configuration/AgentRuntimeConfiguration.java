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
import com.haizhuo.brain.runtime.agentscope.team.NativeTeamExecutionGateway;
import com.haizhuo.brain.runtime.agentscope.team.NativeTeamMemberFactory;
import com.haizhuo.brain.platform.run.RunExecutionStore;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
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

    /** AgentScope 原生 KV；DDL 由 Flyway V24 管理，运行时不得自行建表。 */
    @Bean
    BaseStore agentScopeBaseStore(DataSource dataSource) {
        return JdbcStore.builder(dataSource).dialect(new MysqlDialect()).initializeSchema(false).build();
    }

    @Bean
    DistributedStore agentScopeDistributedStore(AgentStateStore agentStateStore, BaseStore baseStore) {
        return DistributedStore.builder().agentStateStore(agentStateStore).baseStore(baseStore).build();
    }

    @Bean
    RemoteFilesystemSpec agentScopeRemoteFilesystemSpec(BaseStore baseStore) {
        return new RemoteFilesystemSpec(baseStore).isolationScope(IsolationScope.SESSION)
                .addSharedPrefix("outputs/");
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
    NativeTeamMemberFactory nativeTeamMemberFactory(AgentScopeModelFactory modelFactory,
                                                     AgentStateStore agentStateStore,
                                                     DefinitionWorkspaceMaterializer workspaceMaterializer,
                                                     RunControlInbox controlInbox) {
        return new NativeTeamMemberFactory(modelFactory, agentStateStore, workspaceMaterializer, controlInbox);
    }

    @Bean
    NativeTeamExecutionGateway nativeTeamExecutionGateway(BaseStore baseStore,
                                                           TeamExecutionPersistence persistence,
                                                           NativeTeamMemberFactory memberFactory,
                                                           RunControlInbox controlInbox) {
        return new NativeTeamExecutionGateway(baseStore, persistence, memberFactory, controlInbox);
    }

    @Bean
    HarnessAgentFactory harnessAgentFactory(AgentScopeModelFactory modelFactory, AgentStateStore agentStateStore,
                                            DistributedStore distributedStore, RemoteFilesystemSpec filesystemSpec,
                                            DefinitionWorkspaceMaterializer workspaceMaterializer,
                                            RunControlInbox controlInbox, LangfuseProperties langfuse,
                                            ObjectProvider<Tracer> tracer,
                                            NativeTeamExecutionGateway nativeTeamExecutionGateway) {
        Tracer otelTracer = tracer.getIfAvailable(
                () -> GlobalOpenTelemetry.getTracer("com.haizhuo.brain"));
        return new HarnessAgentFactory(modelFactory, agentStateStore, distributedStore, filesystemSpec,
                workspaceMaterializer, controlInbox,
                new com.haizhuo.brain.runtime.agentscope.middleware.ObservabilityMiddleware(
                        otelTracer, langfuse), langfuse.isEnabled(), nativeTeamExecutionGateway);
    }

    @Bean
    HarnessTemplateCache harnessTemplateCache(HarnessAgentFactory factory) {
        return new HarnessTemplateCache(factory);
    }

    @Bean
    RuntimeContextFactory runtimeContextFactory(RunExecutionStore runExecutionStore) {
        return new RuntimeContextFactory(runExecutionStore);
    }

    @Bean
    AgentScopeEventTranslator agentScopeEventTranslator() {
        return new AgentScopeEventTranslator();
    }

    @Bean
    AgentRuntime agentRuntime(HarnessTemplateCache templateCache, RuntimeContextFactory contextFactory,
                              AgentScopeEventTranslator translator,
                              AgentExecutionObserver observer, AgentStateStore agentStateStore) {
        return new AgentScopeRuntime(templateCache, contextFactory, translator, observer, agentStateStore);
    }
}
