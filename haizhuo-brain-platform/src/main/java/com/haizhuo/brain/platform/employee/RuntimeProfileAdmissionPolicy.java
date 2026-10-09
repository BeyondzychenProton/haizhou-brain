package com.haizhuo.brain.platform.employee;

import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.util.Objects;
import java.util.Set;

/** 统一判断运行 profile 是否可发布，以及是否允许创建新 Session/Run。 */
@FunctionalInterface
public interface RuntimeProfileAdmissionPolicy {
    Admission evaluate(RuntimeProfile profile);

    default boolean allows(RuntimeProfile profile) {
        return evaluate(profile).enabled();
    }

    default String evidenceStatus() {
        return "NOT_EVALUATED";
    }

    /** 为只提供已配置 profile 列表的旧调用方保留兼容适配器。 */
    static RuntimeProfileAdmissionPolicy configuredOnly(Set<RuntimeProfile> configuredProfiles) {
        Set<RuntimeProfile> configured = Set.copyOf(Objects.requireNonNull(configuredProfiles));
        if (!configured.contains(RuntimeProfile.LEGACY_STABLE))
            throw new IllegalArgumentException("LEGACY_STABLE must remain enabled");
        return profile -> {
            boolean selected = configured.contains(profile);
            if (profile == RuntimeProfile.LEGACY_STABLE)
                return new Admission(true, true, true, null, "VERIFICATION_NOT_REQUIRED");
            return new Admission(selected, false, false,
                    selected ? "PROFILE_VERIFICATION_MISSING" : "PROFILE_DISABLED",
                    "DEPLOYMENT_EVIDENCE_NOT_EVALUATED");
        };
    }

    static RuntimeProfileAdmissionPolicy legacyOnly() {
        return configuredOnly(Set.of(RuntimeProfile.LEGACY_STABLE));
    }

    record Admission(boolean configuredEnabled, boolean verificationMatched, boolean enabled,
                     String reasonCode, String verificationReasonCode) {
        public Admission {
            if (enabled && (!configuredEnabled || !verificationMatched))
                throw new IllegalArgumentException("An enabled profile must be configured and verified");
            if (enabled && reasonCode != null)
                throw new IllegalArgumentException("Enabled profile cannot have a rejection reason");
            Objects.requireNonNull(verificationReasonCode, "verificationReasonCode");
        }
    }
}
