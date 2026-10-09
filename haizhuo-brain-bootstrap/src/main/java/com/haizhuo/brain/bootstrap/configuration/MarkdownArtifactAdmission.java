package com.haizhuo.brain.bootstrap.configuration;

import org.springframework.util.StringUtils;

/** 启动时统一判定，供成果物生产者和只读 readiness 视图共用。 */
record MarkdownArtifactAdmission(boolean configured, boolean verificationMatched, boolean enabled,
                                String reasonCode, String verificationReasonCode) {
    static final String GATE_ID = "artifact.markdown-export";

    MarkdownArtifactAdmission {
        if (enabled && (!configured || !verificationMatched))
            throw new IllegalArgumentException("Markdown export requires configuration and deployment evidence");
        if (enabled && reasonCode != null)
            throw new IllegalArgumentException("Enabled Markdown export cannot have a rejection reason");
    }

    static MarkdownArtifactAdmission evaluate(RunArtifactProperties properties,
                                              DeploymentVerificationManifestReader manifestReader) {
        boolean configured = properties.isEnabled() && StringUtils.hasText(properties.getStorageDirectory());
        DeploymentVerificationManifestReader.Verification verification = manifestReader.verify(GATE_ID);
        boolean enabled = configured && verification.matched();
        String reason = enabled ? null : configured ? "ARTIFACT_DEPLOYMENT_EVIDENCE_MISSING"
                : "ARTIFACT_STORAGE_NOT_CONFIGURED";
        return new MarkdownArtifactAdmission(configured, verification.matched(), enabled,
                reason, verification.reasonCode());
    }
}
