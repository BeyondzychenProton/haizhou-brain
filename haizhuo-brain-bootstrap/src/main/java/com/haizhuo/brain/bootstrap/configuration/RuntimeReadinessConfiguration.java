package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.api.admin.RuntimeReadinessProvider;
import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.GateState;
import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.GateStatus;
import com.haizhuo.brain.api.admin.RuntimeReadinessProvider.ReadinessResponse;
import com.haizhuo.brain.bootstrap.channel.SimulatedChannelOutboundSender;
import com.haizhuo.brain.infrastructure.mcp.SimulatorMcpUserTokenProvider;
import com.haizhuo.brain.platform.channel.ChannelOutboundSender;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import com.haizhuo.brain.platform.mcp.McpUserTokenProvider;
import com.haizhuo.brain.platform.tool.StrictToolResourcePolicy;
import com.haizhuo.brain.platform.tool.ToolResourcePolicy;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 构建固定的只读门槛列表，不写入门槛状态，也不暴露凭据。 */
@Configuration
public class RuntimeReadinessConfiguration {
    @Bean
    RuntimeReadinessProvider runtimeReadinessProvider(Environment environment,
                                                       ObjectProvider<ChannelOutboundSender> channelSenders,
                                                       ObjectProvider<McpUserTokenProvider> tokenProviders,
                                                       ObjectProvider<ToolResourcePolicy> resourcePolicies,
                                                       RuntimeProfileAdmissionPolicy profileAdmission,
                                                       DeploymentVerificationManifestReader manifestReader,
                                                       MarkdownArtifactAdmission markdownArtifactAdmission,
                                                       Clock clock) {
        return () -> {
            Instant checkedAt = clock.instant();
            ChannelOutboundSender channelSender = channelSenders.getIfAvailable();
            boolean realChannelConfigured = environment.getProperty(
                    "haizhuo.brain.channel.real-callback.enabled", Boolean.class, false)
                    && channelSender != null && !(channelSender instanceof SimulatedChannelOutboundSender);
            McpUserTokenProvider tokenProvider = tokenProviders.getIfAvailable();
            boolean enterpriseIdentityConfigured = environment.getProperty(
                    "haizhuo.brain.mcp.enterprise-identity.enabled", Boolean.class, false)
                    && tokenProvider != null
                    && !(tokenProvider instanceof SimulatorMcpUserTokenProvider)
                    && !tokenProvider.getClass().isSynthetic();
            boolean strictResourcePolicyConfigured = "STRICT".equalsIgnoreCase(environment.getProperty(
                    "haizhuo.brain.tool.resource-policy-mode", "STRICT"))
                    && resourcePolicies.getIfAvailable() instanceof StrictToolResourcePolicy;

            RuntimeProfileAdmissionPolicy.Admission single = profileAdmission.evaluate(RuntimeProfile.SINGLE_SKILLED);
            RuntimeProfileAdmissionPolicy.Admission team = profileAdmission.evaluate(RuntimeProfile.TEAM_READONLY);
            RuntimeProfileAdmissionPolicy.Admission autonomous = profileAdmission.evaluate(
                    RuntimeProfile.TEAM_AUTONOMOUS_READONLY);
            return new ReadinessResponse(1, List.of(
                    verifiedGate("tool.resource-authorization", GateState.IMPLEMENTED_CLOSED,
                            strictResourcePolicyConfigured, manifestReader.verify("tool.resource-authorization"),
                            false, strictResourcePolicyConfigured ? "RESOURCE_RESOLVER_NOT_CONFIGURED"
                                    : "STRICT_RESOURCE_POLICY_NOT_ACTIVE",
                            List.of("注册服务端资源解析器和授权事实来源。", "在目标环境验证用户与资源隔离。"), checkedAt),
                    verifiedGate("channel.real-im", GateState.IMPLEMENTED_CLOSED, realChannelConfigured,
                            manifestReader.verify("channel.real-im"), false,
                            realChannelConfigured ? "REAL_CALLBACK_ADAPTER_NOT_CONFIGURED" : "PROVIDER_NOT_SELECTED",
                            List.of("指定渠道提供方和受控测试账号。", "完成验签、身份绑定和投递回执核验。"), checkedAt),
                    verifiedGate("mcp.enterprise-identity", GateState.IMPLEMENTED_CLOSED,
                            enterpriseIdentityConfigured, manifestReader.verify("mcp.enterprise-identity"), false,
                            enterpriseIdentityConfigured ? "ENTERPRISE_IDENTITY_ADAPTER_NOT_CONFIGURED"
                                    : "ENTERPRISE_IDENTITY_NOT_CONFIGURED",
                            List.of("指定企业身份源和 Token broker。", "用受控用户验证 audience、scope、过期与吊销。"), checkedAt),
                    new GateStatus(MarkdownArtifactAdmission.GATE_ID,
                            markdownArtifactAdmission.enabled() ? GateState.ENABLED : GateState.IMPLEMENTED_CLOSED,
                            markdownArtifactAdmission.configured(), markdownArtifactAdmission.verificationMatched(),
                            markdownArtifactAdmission.enabled(), markdownArtifactAdmission.reasonCode(),
                            markdownArtifactAdmission.verificationReasonCode(),
                            List.of("配置服务端私有存储目录。", "将本部署的 Markdown 导出验收证据加入受审计清单。"),
                            checkedAt),
                    closedGate("artifact.content-blocks", "PUBLIC_CONTENT_PRODUCER_NOT_CONFIGURED",
                            List.of("接入白名单内容生产者并验证普通用户可见性/属主过滤。"), checkedAt),
                    new GateStatus("model.connection-directory", GateState.DESIGNED, false, false, false,
                            "CREDENTIAL_REFERENCE_DIRECTORY_NOT_CONFIGURED", "VERIFICATION_NOT_RUN",
                            List.of("指定真实 Vault/凭据目录及授权范围。", "完成精确 revision 冻结和短生命周期解析验证。"),
                            checkedAt),
                    profileGate("profile.single-skilled", single,
                            List.of("验证部署的 AgentScope 精确版本和该 profile 的验收场景。"), checkedAt),
                    profileGate("profile.team-readonly", team,
                            List.of("验证父级权限上界、预算、根结果与安全进度。"), checkedAt),
                    profileGate("profile.team-autonomous-readonly", autonomous,
                            List.of("验证 fencing、超时、持久化、重启恢复和管理员核查。"), checkedAt),
                    closedGate("session.collaborative", "RUN_MODE_NOT_ENABLED",
                            List.of("完成路由、属主、取消和授权独立验收前保持 DIRECT。"), checkedAt),
                    closedGate("session.autonomous", "RUN_MODE_NOT_ENABLED",
                            List.of("完成自治 Run 控制与恢复独立验收前保持 DIRECT。"), checkedAt),
                    closedGate("memory.long-term", "CAPABILITY_NOT_IMPLEMENTED",
                            List.of("明确保留、删除、访问控制、隔离和恢复要求。"), checkedAt),
                    closedGate("rag.full", "CAPABILITY_NOT_IMPLEMENTED",
                            List.of("明确导入、检索、ACL、评测和更新生命周期。"), checkedAt),
                    closedGate("meeting-room.production", "BUSINESS_SOURCE_NOT_CONFIGURED",
                            List.of("接入真实会议室业务源，验证资源权限和冲突处理。"), checkedAt),
                    new GateStatus("artifact.generic-selection", GateState.DESIGNED, false, false, false,
                            "GENERIC_SELECTION_NOT_IMPLEMENTED", "DEPLOYMENT_EVIDENCE_NOT_MATCHED",
                            List.of("实现原生暂停恢复映射、稳定内容类型 DTO 和受控生产者授权。"), checkedAt)
            ), null, false);
        };
    }

    private static GateStatus profileGate(String gateId, RuntimeProfileAdmissionPolicy.Admission admission,
                                          List<String> requirements, Instant checkedAt) {
        GateState state = admission.enabled() ? GateState.ENABLED : GateState.IMPLEMENTED_CLOSED;
        return new GateStatus(gateId, state, admission.configuredEnabled(), admission.verificationMatched(),
                admission.enabled(), admission.reasonCode(), admission.verificationReasonCode(), requirements, checkedAt);
    }

    private static GateStatus verifiedGate(String gateId, GateState state, boolean configured,
                                           DeploymentVerificationManifestReader.Verification verification,
                                           boolean enabled, String reasonCode, List<String> requirements,
                                           Instant checkedAt) {
        return new GateStatus(gateId, state, configured, verification.matched(), enabled,
                reasonCode, verification.reasonCode(), requirements, checkedAt);
    }

    private static GateStatus closedGate(String gateId, String reasonCode,
                                         List<String> requirements, Instant checkedAt) {
        return new GateStatus(gateId, GateState.IMPLEMENTED_CLOSED, false, false, false,
                reasonCode, "DEPLOYMENT_EVIDENCE_NOT_MATCHED", requirements, checkedAt);
    }
}
