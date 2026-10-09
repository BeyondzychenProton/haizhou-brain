package com.haizhuo.brain.bootstrap.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownArtifactAdmissionTest {
    private static final String COMMIT = "a".repeat(40);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDirectory;

    @Test
    void requiresBothPrivateStorageConfigurationAndMatchingDeploymentEvidence() throws Exception {
        Path manifestFile = tempDirectory.resolve("manifest.json");
        Files.writeString(manifestFile, "{\"schemaVersion\":1,\"verifications\":[{"
                + "\"gateId\":\"artifact.markdown-export\",\"commit\":\"" + COMMIT
                + "\",\"environment\":\"uat\",\"evidenceRef\":\"artifact-check-7\","
                + "\"owner\":\"operator\",\"verifiedAt\":\"2026-10-09T02:00:00Z\"}]}");
        var reader = new DeploymentVerificationManifestReader(new ObjectMapper(), CLOCK,
                manifestFile.toString(), COMMIT, "uat");
        RunArtifactProperties properties = new RunArtifactProperties();
        properties.setEnabled(true);

        MarkdownArtifactAdmission noStorage = MarkdownArtifactAdmission.evaluate(properties, reader);
        assertFalse(noStorage.enabled());
        assertTrue(noStorage.verificationMatched());

        properties.setStorageDirectory(tempDirectory.resolve("private-artifacts").toString());
        MarkdownArtifactAdmission admitted = MarkdownArtifactAdmission.evaluate(properties, reader);
        assertTrue(admitted.configured());
        assertTrue(admitted.verificationMatched());
        assertTrue(admitted.enabled());

        MarkdownArtifactAdmission wrongDeployment = MarkdownArtifactAdmission.evaluate(properties,
                new DeploymentVerificationManifestReader(new ObjectMapper(), CLOCK,
                        manifestFile.toString(), "b".repeat(40), "uat"));
        assertFalse(wrongDeployment.enabled());
        assertFalse(wrongDeployment.verificationMatched());
    }
}
