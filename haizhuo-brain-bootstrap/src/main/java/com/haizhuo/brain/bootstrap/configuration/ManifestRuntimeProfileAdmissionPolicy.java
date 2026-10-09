package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.util.Map;

/** 必须同时具备明确的部署配置，以及与提交和环境精确匹配的验证证据。 */
final class ManifestRuntimeProfileAdmissionPolicy implements RuntimeProfileAdmissionPolicy {
    private static final Map<RuntimeProfile, String> GATES = Map.of(
            RuntimeProfile.SINGLE_SKILLED, "profile.single-skilled",
            RuntimeProfile.TEAM_READONLY, "profile.team-readonly",
            RuntimeProfile.TEAM_AUTONOMOUS_READONLY, "profile.team-autonomous-readonly");
    private final EmployeeRuntimeProfileSettings settings;
    private final DeploymentVerificationManifestReader manifestReader;

    ManifestRuntimeProfileAdmissionPolicy(EmployeeRuntimeProfileSettings settings,
                                          DeploymentVerificationManifestReader manifestReader) {
        this.settings = settings;
        this.manifestReader = manifestReader;
    }

    @Override
    public Admission evaluate(RuntimeProfile profile) {
        boolean configured = settings.enabledProfiles().contains(profile);
        if (profile == RuntimeProfile.LEGACY_STABLE) {
            return new Admission(configured, configured, configured,
                    configured ? null : "PROFILE_DISABLED", "VERIFICATION_NOT_REQUIRED");
        }
        String gateId = GATES.get(profile);
        if (gateId == null) {
            return new Admission(configured, false, false, "PROFILE_NOT_SUPPORTED", "PROFILE_NOT_SUPPORTED");
        }
        DeploymentVerificationManifestReader.Verification verification = manifestReader.verify(gateId);
        boolean enabled = configured && verification.matched();
        String reason = enabled ? null : configured ? "PROFILE_VERIFICATION_MISSING" : "PROFILE_DISABLED";
        return new Admission(configured, verification.matched(), enabled, reason, verification.reasonCode());
    }

    @Override
    public String evidenceStatus() {
        return "DEPLOYMENT_MANIFEST";
    }
}
