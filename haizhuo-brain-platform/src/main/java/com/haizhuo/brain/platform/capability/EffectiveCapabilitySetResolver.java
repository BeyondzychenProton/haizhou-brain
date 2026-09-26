package com.haizhuo.brain.platform.capability;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.runtime.api.RuntimeCapabilityProviderCatalog;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
public class EffectiveCapabilitySetResolver implements CapabilityRunAuthorizer {
    private final AgentDefinitionRepository repository;
    private final RuntimeCapabilityProviderCatalog providers;

    public EffectiveCapabilitySetResolver(AgentDefinitionRepository repository,
                                          RuntimeCapabilityProviderCatalog providers) {
        this.repository = repository;
        this.providers = providers;
    }

    /** 中文注释：先按版本绑定收敛，再与当前授权和停用状态求交集，结果保存后才能交给 Runtime。 */
    public EffectiveCapabilitySet resolve(RunId runId, UserId userId, PublishedEmployee published) {
        List<RuntimeCapability> allowed = new ArrayList<>();
        List<CapabilityExclusion> exclusions = new ArrayList<>();
        for (CapabilityBinding binding : published.capabilities()) {
            if (binding.type() != CapabilityBinding.CapabilityType.TOOL) {
                exclusions.add(new CapabilityExclusion(binding.referenceId(), binding.revision(), "UNSUPPORTED_TYPE"));
                continue;
            }
            CapabilityCatalogEntry entry = repository.findCapability(binding.referenceId(), binding.revision()).orElse(null);
            if (entry == null) {
                exclusions.add(new CapabilityExclusion(binding.referenceId(), binding.revision(), "REVISION_MISSING"));
                continue;
            }
            if (!entry.enabled() || !repository.isCapabilityEnabled(entry.capabilityCode(), entry.revision())) {
                exclusions.add(new CapabilityExclusion(entry.capabilityCode(), entry.revision(), "CAPABILITY_DISABLED"));
                continue;
            }
            if (!providers.supports(entry.implementationKey(), entry.capabilityCode())) {
                exclusions.add(new CapabilityExclusion(entry.capabilityCode(), entry.revision(), "PROVIDER_UNAVAILABLE"));
                continue;
            }
            if (!repository.hasUserCapabilityGrant(userId.value(), entry.capabilityCode())) {
                exclusions.add(new CapabilityExclusion(entry.capabilityCode(), entry.revision(), "USER_NOT_GRANTED"));
                continue;
            }
            allowed.add(toRuntimeCapability(entry));
        }
        allowed.sort(Comparator.comparing(RuntimeCapability::referenceId));
        exclusions.sort(Comparator.comparing(CapabilityExclusion::capabilityCode));
        EffectiveCapabilitySet set = new EffectiveCapabilitySet(runId.value(), published.definition().id(),
                userId.value(), hash(published.definition().contentHash(), userId.value(), allowed),
                allowed, exclusions, Instant.now());
        repository.saveEffectiveCapabilitySet(set);
        return set;
    }

    @Override
    public boolean isAllowedNow(AgentExecutionRequest run, String capabilityCode) {
        return repository.findEffectiveCapabilitySet(run.runId().value())
                .filter(set -> set.snapshotHash().equals(run.effectiveCapabilityHash()))
                .filter(set -> set.definitionVersionId() == run.definitionVersionId())
                .filter(set -> set.userId() == run.userId().value())
                .flatMap(set -> set.allowedCapabilities().stream()
                        .filter(capability -> capabilityCode.equals(capability.referenceId())).findFirst())
                .filter(snapshotCapability -> run.capabilities().stream().anyMatch(runtimeCapability ->
                        snapshotCapability.referenceId().equals(runtimeCapability.referenceId())
                                && snapshotCapability.revision().equals(runtimeCapability.revision())
                                && snapshotCapability.implementationKey().equals(runtimeCapability.implementationKey())
                                && snapshotCapability.businessAction().equals(runtimeCapability.businessAction())))
                .filter(capability -> repository.isCapabilityEnabled(capability.referenceId(), capability.revision()))
                .filter(capability -> repository.hasUserCapabilityGrant(run.userId().value(), capability.referenceId()))
                .filter(capability -> providers.supports(capability.implementationKey(), capability.referenceId()))
                .isPresent();
    }

    private static RuntimeCapability toRuntimeCapability(CapabilityCatalogEntry entry) {
        return new RuntimeCapability(entry.type().name().toLowerCase(java.util.Locale.ROOT),
                entry.capabilityCode(), entry.revision(), entry.toolName(), entry.description(),
                entry.implementationKey(), entry.businessAction(), entry.inputSchema());
    }

    private static String hash(String definitionHash, long userId, List<RuntimeCapability> capabilities) {
        StringBuilder canonical = new StringBuilder(definitionHash).append('|').append(userId);
        capabilities.forEach(capability -> canonical.append('|').append(capability.referenceId())
                .append('@').append(capability.revision()).append(':').append(capability.implementationKey())
                .append(':').append(capability.toolName()).append(':').append(capability.businessAction())
                .append(':').append(new java.util.TreeMap<>(capability.inputSchema())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
