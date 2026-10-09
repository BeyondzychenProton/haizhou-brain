package com.haizhuo.brain.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.platform.employee.RuntimeProfileAdmissionPolicy;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentVerificationManifestReaderTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String GATE = "profile.single-skilled";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDirectory;

    @Test
    void matchesOnlyEvidenceBoundToExactCommitAndEnvironment() throws Exception {
        DeploymentVerificationManifestReader.Verification matching = reader(manifest("uat", COMMIT,
                "2026-10-09T02:00:00Z"));
        DeploymentVerificationManifestReader.Verification wrongEnvironment = reader(manifest("prod", COMMIT,
                "2026-10-09T02:00:00Z"));
        DeploymentVerificationManifestReader.Verification wrongCommit = reader(manifest("uat", "b".repeat(40),
                "2026-10-09T02:00:00Z"));

        assertTrue(matching.matched());
        assertEquals("DEPLOYMENT_EVIDENCE_MATCHED", matching.reasonCode());
        assertFalse(wrongEnvironment.matched());
        assertFalse(wrongCommit.matched());
        assertEquals("DEPLOYMENT_EVIDENCE_NOT_MATCHED", wrongCommit.reasonCode());
    }

    @Test
    void admissionRequiresBothConfiguredProfileAndMatchingManifestEvidence() throws Exception {
        Path file = tempDirectory.resolve("admission-manifest.json");
        Files.writeString(file, manifest("uat", COMMIT, "2026-10-09T02:00:00Z"));
        var policy = new ManifestRuntimeProfileAdmissionPolicy(
                new EmployeeRuntimeProfileSettings(java.util.Set.of(RuntimeProfile.LEGACY_STABLE,
                        RuntimeProfile.SINGLE_SKILLED)),
                new DeploymentVerificationManifestReader(new ObjectMapper(), CLOCK, file.toString(), COMMIT, "uat"));

        RuntimeProfileAdmissionPolicy.Admission configuredAndVerified = policy.evaluate(RuntimeProfile.SINGLE_SKILLED);
        RuntimeProfileAdmissionPolicy.Admission notConfigured = new ManifestRuntimeProfileAdmissionPolicy(
                new EmployeeRuntimeProfileSettings(java.util.Set.of(RuntimeProfile.LEGACY_STABLE)),
                new DeploymentVerificationManifestReader(new ObjectMapper(), CLOCK, file.toString(), COMMIT, "uat"))
                .evaluate(RuntimeProfile.SINGLE_SKILLED);

        assertTrue(configuredAndVerified.configuredEnabled());
        assertTrue(configuredAndVerified.verificationMatched());
        assertTrue(configuredAndVerified.enabled());
        assertFalse(notConfigured.enabled());
        assertEquals("PROFILE_DISABLED", notConfigured.reasonCode());
    }

    @Test
    void malformedDuplicateAndFutureEvidenceFailClosed() throws Exception {
        var malformed = reader("{broken");
        String one = verification("uat", COMMIT, "2026-10-09T02:00:00Z");
        var duplicate = reader("{\"schemaVersion\":1,\"verifications\":[" + one + "," + one + "]}");
        var duplicateField = reader("{\"schemaVersion\":1,\"schemaVersion\":1,\"verifications\":["
                + one + "]}");
        var fractionalSchema = reader("{\"schemaVersion\":1.5,\"verifications\":[" + one + "]}");
        var trailingValue = reader(manifest("uat", COMMIT, "2026-10-09T02:00:00Z") + " {}");
        var future = reader(manifest("uat", COMMIT, "2026-10-10T00:00:00Z"));

        assertFalse(malformed.matched());
        assertEquals("MANIFEST_UNAVAILABLE_OR_INVALID", malformed.reasonCode());
        assertFalse(duplicate.matched());
        assertEquals("MANIFEST_DUPLICATE_EVIDENCE", duplicate.reasonCode());
        assertFalse(duplicateField.matched());
        assertFalse(fractionalSchema.matched());
        assertEquals("MANIFEST_INVALID", fractionalSchema.reasonCode());
        assertFalse(trailingValue.matched());
        assertEquals("MANIFEST_INVALID", trailingValue.reasonCode());
        assertFalse(future.matched());
        assertEquals("MANIFEST_INVALID", future.reasonCode());
    }

    @Test
    void absentManifestAndDeploymentIdentityAreExplicitlyUnmatched() {
        assertEquals("MANIFEST_NOT_CONFIGURED", new DeploymentVerificationManifestReader(
                new ObjectMapper(), CLOCK, "", COMMIT, "uat").verify(GATE).reasonCode());
        assertEquals("DEPLOYMENT_IDENTITY_NOT_CONFIGURED", new DeploymentVerificationManifestReader(
                new ObjectMapper(), CLOCK, "manifest.json", "", "uat").verify(GATE).reasonCode());
    }

    private DeploymentVerificationManifestReader.Verification reader(String content) throws Exception {
        Path file = tempDirectory.resolve("deployment-verification.json");
        Files.writeString(file, content);
        return new DeploymentVerificationManifestReader(new ObjectMapper(), CLOCK, file.toString(), COMMIT, "uat")
                .verify(GATE);
    }

    private static String manifest(String environment, String commit, String verifiedAt) {
        return "{\"schemaVersion\":1,\"verifications\":["
                + verification(environment, commit, verifiedAt) + "]}";
    }

    private static String verification(String environment, String commit, String verifiedAt) {
        return "{\"commit\":\"" + commit + "\",\"environment\":\"" + environment
                + "\",\"gateId\":\"" + GATE + "\",\"evidenceRef\":\"release-check-42\","
                + "\"owner\":\"operator\",\"verifiedAt\":\"" + verifiedAt + "\"}";
    }
}
