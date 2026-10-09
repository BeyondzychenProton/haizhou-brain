package com.haizhuo.brain.bootstrap.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 读取部署提供且经过审计的验证清单；清单缺失或证据无效时关闭准入。 */
final class DeploymentVerificationManifestReader {
    private static final int MAX_BYTES = 256 * 1024;
    private static final Pattern COMMIT = Pattern.compile("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})");
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String manifestPath;
    private final String deploymentCommit;
    private final String deploymentEnvironment;

    DeploymentVerificationManifestReader(ObjectMapper objectMapper, Clock clock, String manifestPath,
                                          String deploymentCommit, String deploymentEnvironment) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.manifestPath = manifestPath == null ? "" : manifestPath.trim();
        this.deploymentCommit = deploymentCommit == null ? "" : deploymentCommit.trim();
        this.deploymentEnvironment = deploymentEnvironment == null ? "" : deploymentEnvironment.trim();
    }

    Verification verify(String gateId) {
        if (manifestPath.isEmpty()) return Verification.missing("MANIFEST_NOT_CONFIGURED");
        if (!COMMIT.matcher(deploymentCommit).matches()
                || !ENVIRONMENT.matcher(deploymentEnvironment).matches())
            return Verification.missing("DEPLOYMENT_IDENTITY_NOT_CONFIGURED");
        try {
            Path path = Path.of(manifestPath).toAbsolutePath().normalize();
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_BYTES)
                return Verification.missing("MANIFEST_INVALID");
            JsonNode root;
            try (JsonParser parser = objectMapper.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                root = objectMapper.readTree(parser);
                if (parser.nextToken() != null) return Verification.missing("MANIFEST_INVALID");
            }
            if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                    || root.path("schemaVersion").intValue() != 1 || !root.path("verifications").isArray())
                return Verification.missing("MANIFEST_INVALID");

            JsonNode exact = null;
            int exactCount = 0;
            for (JsonNode item : root.path("verifications")) {
                if (!item.isObject() || !gateId.equals(text(item, "gateId"))) continue;
                if (!deploymentCommit.equals(text(item, "commit"))
                        || !deploymentEnvironment.equals(text(item, "environment"))) continue;
                exact = item;
                exactCount++;
            }
            if (exactCount == 0) return Verification.missing("DEPLOYMENT_EVIDENCE_NOT_MATCHED");
            if (exactCount != 1) return Verification.missing("MANIFEST_DUPLICATE_EVIDENCE");
            String evidenceRef = text(exact, "evidenceRef");
            String owner = text(exact, "owner");
            if (!safeRequiredText(evidenceRef, 512) || !safeRequiredText(owner, 128))
                return Verification.missing("MANIFEST_INVALID");
            Instant verifiedAt;
            try {
                verifiedAt = Instant.parse(text(exact, "verifiedAt"));
            } catch (DateTimeParseException | NullPointerException invalidTimestamp) {
                return Verification.missing("MANIFEST_INVALID");
            }
            if (verifiedAt.isAfter(clock.instant())) return Verification.missing("MANIFEST_INVALID");
            return Verification.verified();
        } catch (IOException | RuntimeException error) {
            return Verification.missing("MANIFEST_UNAVAILABLE_OR_INVALID");
        }
    }

    private static String text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean safeRequiredText(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) return false;
        return value.chars().noneMatch(Character::isISOControl);
    }

    record Verification(boolean matched, String reasonCode) {
        private static Verification verified() { return new Verification(true, "DEPLOYMENT_EVIDENCE_MATCHED"); }
        private static Verification missing(String reason) { return new Verification(false, reason); }
    }
}
