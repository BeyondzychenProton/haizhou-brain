package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.runtime.DefaultHarnessDefinitionBundleCompiler;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleCompiler;
import com.haizhuo.brain.platform.channel.ChannelReplyEnqueuer;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundleRepository;
import com.haizhuo.brain.platform.harness.SessionBridgeService;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshotRepository;
import com.haizhuo.brain.platform.harness.SessionHarnessBindingRepository;
import com.haizhuo.brain.platform.run.HarnessRunSpecFactory;
import com.haizhuo.brain.platform.run.HarnessRunSpecRepository;
import com.haizhuo.brain.platform.run.RunExecutionService;
import com.haizhuo.brain.platform.run.RunRealtimeEventPublisher;
import com.haizhuo.brain.platform.run.RunExecutionStore;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.tool.CapabilityExecutor;
import com.haizhuo.brain.platform.tool.CapabilityExecutorRegistry;
import com.haizhuo.brain.platform.tool.CredentialResolver;
import com.haizhuo.brain.platform.tool.DefaultToolExecutionGatewayService;
import com.haizhuo.brain.platform.tool.DefaultToolResourcePolicy;
import com.haizhuo.brain.platform.tool.ListCapabilityExecutorRegistry;
import com.haizhuo.brain.platform.tool.ResolvedCredential;
import com.haizhuo.brain.platform.tool.ToolApprovalRepository;
import com.haizhuo.brain.platform.tool.ToolApprovalService;
import com.haizhuo.brain.platform.tool.ToolExecutionGatewayService;
import com.haizhuo.brain.platform.tool.ToolExecutionRepository;
import com.haizhuo.brain.platform.tool.ToolExecutionUserDirectory;
import com.haizhuo.brain.platform.tool.ToolExecutionWorker;
import com.haizhuo.brain.platform.tool.ToolResourcePolicy;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配平台侧的 harness 服务：执行器白名单、能力包编译器、RunSpec 工厂、会话桥、
 * 工具网关，以及两个持久化 worker。platform 保持无 Spring，每个 bean 都是一次普通的构造器调用。
 * 凭据解析器是 P0 桩——预置能力不需要外部密钥，持久化适配器随 MCP 工作一起到位（规格 §40）。
 */
@Configuration
public class HarnessRuntimeConfiguration {

    @Bean
    CapabilityExecutorRegistry capabilityExecutorRegistry(List<CapabilityExecutor> executors) {
        return new ListCapabilityExecutorRegistry(executors);
    }

    @Bean
    HarnessDefinitionBundleCompiler harnessDefinitionBundleCompiler(AgentDefinitionRepository definitions) {
        return new DefaultHarnessDefinitionBundleCompiler(definitions);
    }

    @Bean
    HarnessRunSpecFactory harnessRunSpecFactory(AgentDefinitionRepository definitions,
                                                CapabilityExecutorRegistry executors) {
        return new HarnessRunSpecFactory(definitions, executors);
    }

    @Bean
    SessionBridgeService sessionBridgeService(SessionHarnessBindingRepository bindings,
                                              SessionBridgeSnapshotRepository snapshots,
                                              SessionRunStore runs, Clock clock) {
        return new SessionBridgeService(bindings, snapshots, runs, clock);
    }

    @Bean
    ToolResourcePolicy toolResourcePolicy() {
        return new DefaultToolResourcePolicy();
    }

    @Bean
    CredentialResolver credentialResolver() {
        return (userId, capabilityRevisionId) -> ResolvedCredential.none();
    }

    @Bean
    ToolExecutionGatewayService toolExecutionGatewayService(AgentDefinitionRepository definitions,
                                                            HarnessRunSpecRepository runSpecs,
                                                            ToolExecutionUserDirectory users,
                                                            ToolResourcePolicy resourcePolicy,
                                                            CredentialResolver credentials,
                                                            CapabilityExecutorRegistry executors) {
        return new DefaultToolExecutionGatewayService(definitions, runSpecs, users, resourcePolicy,
                credentials, executors);
    }

    @Bean
    ToolApprovalService toolApprovalService(SessionRunStore runs, ToolExecutionRepository executions,
                                            ToolApprovalRepository approvals) {
        return new ToolApprovalService(runs, executions, approvals);
    }

    @Bean
    @ConditionalOnProperty(prefix = "haizhuo.brain.run-worker", name = "enabled", havingValue = "true")
    RunExecutionService runExecutionService(RunExecutionStore executionStore,
                                            HarnessDefinitionBundleRepository bundles,
                                            SessionBridgeService bridgeService,
                                            SessionBridgeSnapshotRepository snapshots,
                                            SessionRunStore runs,
                                            ToolExecutionRepository toolExecutions,
                                            AgentRuntime runtime, RunRealtimeEventPublisher realtimeEvents,
                                            ChannelReplyEnqueuer replies, Clock clock) {
        return new RunExecutionService(executionStore, bundles, bridgeService, snapshots, runs,
                toolExecutions, runtime, realtimeEvents, replies, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "haizhuo.brain.run-worker", name = "enabled", havingValue = "true")
    ToolExecutionWorker toolExecutionWorker(ToolExecutionRepository executions,
                                            ToolApprovalRepository approvals,
                                            ToolExecutionGatewayService gateway, Clock clock) {
        return new ToolExecutionWorker(executions, approvals, gateway, clock);
    }
}
