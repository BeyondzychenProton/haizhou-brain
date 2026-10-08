package com.haizhuo.brain.runtime.agentscope.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.runtime.agentscope.TestRequests;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.runtime.api.model.RuntimeDefinitionSnapshot;
import com.haizhuo.brain.runtime.api.model.RuntimeEmployeeConfiguration;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.model.RuntimeWorkspaceFile;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefinitionWorkspaceMaterializerTest {

    Path tempDir;

    @BeforeEach
    void createWorkspaceRootInsideBuildDirectory() throws Exception {
        tempDir = Path.of(System.getProperty("user.dir"), "target", "workspace-materializer-tests",
                UUID.randomUUID().toString());
        Files.createDirectories(tempDir);
    }

    @AfterEach
    void removeWorkspaceRoot() throws Exception {
        if (!Files.exists(tempDir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(tempDir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                makeWritable(path);
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void materializesAgentsFileUnderBundleHashDirectory() throws Exception {
        DefinitionWorkspaceMaterializer materializer = new DefinitionWorkspaceMaterializer(tempDir);

        Path directory = materializer.materialize(TestRequests.definition("bundle-1", List.of()));

        assertEquals(tempDir.resolve("bundle-1"), directory);
        assertEquals("你是海卓数字员工。", Files.readString(directory.resolve("AGENTS.md")));
        assertTrue(isReadOnly(directory.resolve("AGENTS.md")));
        assertTrue(Files.exists(directory.resolve("manifest.json")));
        assertTrue(Files.exists(directory.resolve(".complete")));
    }

    @Test
    void repeatedMaterializationIsIdempotent() throws Exception {
        DefinitionWorkspaceMaterializer materializer = new DefinitionWorkspaceMaterializer(tempDir);
        Path first = materializer.materialize(TestRequests.definition("bundle-1", List.of()));

        Path second = materializer.materialize(TestRequests.definition("bundle-1", List.of()));

        assertEquals(first, second);
        assertEquals("你是海卓数字员工。", Files.readString(second.resolve("AGENTS.md")));
    }

    @Test
    void canonicalizesManifestJsonBeforeVerifyingAndMaterializing() throws Exception {
        RuntimeDefinitionSnapshot base = TestRequests.definition("bundle-normalized-manifest", List.of());
        String canonicalManifest = CanonicalJson.write(Map.of("schemaVersion", 1, "agents", "AGENTS.md",
                "skills", List.of(), "subagents", List.of(), "knowledge", List.of()));
        String databaseNormalizedManifest = "{ \"skills\": [], \"agents\": \"AGENTS.md\", "
                + "\"knowledge\": [], \"subagents\": [], \"schemaVersion\": 1 }";
        String workspaceHash = CanonicalJson.sha256(Map.of("instructions", base.instructions(),
                "manifestJson", canonicalManifest));
        RuntimeDefinitionSnapshot definition = new RuntimeDefinitionSnapshot(base.definitionVersionId(),
                base.employeeName(), base.instructions(), base.modelProvider(), base.modelName(),
                base.maxIterations(), base.definitionBundleHash(), base.workspaceProjectionKey(), workspaceHash,
                databaseNormalizedManifest, base.toolCatalog(), base.configuration(), base.workspaceFiles());

        Path directory = new DefinitionWorkspaceMaterializer(tempDir).materialize(definition);

        assertEquals(canonicalManifest, Files.readString(directory.resolve("manifest.json")));
    }

    @Test
    void rejectsTamperedCachedDefinitionBeforeReuse() throws Exception {
        DefinitionWorkspaceMaterializer materializer = new DefinitionWorkspaceMaterializer(tempDir);
        RuntimeDefinitionSnapshot definition = TestRequests.definition("bundle-tampered", List.of());
        Path directory = materializer.materialize(definition);
        Path agents = directory.resolve("AGENTS.md");
        makeWritable(agents);
        Files.writeString(agents, "tampered");

        assertThrows(IllegalStateException.class, () -> materializer.materialize(definition));
    }

    @Test
    void materializesOnlyFrozenSkillFilesAndRejectsAddedFiles() throws Exception {
        DefinitionWorkspaceMaterializer materializer = new DefinitionWorkspaceMaterializer(tempDir);
        RuntimeDefinitionSnapshot definition = skilledDefinition("bundle-skill");

        Path directory = materializer.materialize(definition);

        assertEquals("name: sales\ndescription: approved\n", Files.readString(directory.resolve("skills/sales/SKILL.md")));
        assertTrue(isReadOnly(directory.resolve("skills")));
        assertTrue(isReadOnly(directory.resolve("skills/sales")));
        assertTrue(isReadOnly(directory.resolve("skills/sales/SKILL.md")));
        makeWritable(directory.resolve("skills"));
        makeWritable(directory.resolve("skills/sales"));
        Files.writeString(directory.resolve("skills/sales/EXTRA.md"), "not reviewed");

        assertThrows(IllegalStateException.class, () -> materializer.materialize(definition));
    }

    @Test
    void concurrentMaterializersPublishOneCompletePackage() throws Exception {
        RuntimeDefinitionSnapshot definition = skilledDefinition("bundle-concurrent");
        DefinitionWorkspaceMaterializer first = new DefinitionWorkspaceMaterializer(tempDir);
        DefinitionWorkspaceMaterializer second = new DefinitionWorkspaceMaterializer(tempDir);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Path> left = () -> first.materialize(definition);
            Callable<Path> right = () -> second.materialize(definition);
            var leftResult = executor.submit(left);
            var rightResult = executor.submit(right);
            Path leftPath = leftResult.get(10, TimeUnit.SECONDS);
            Path rightPath = rightResult.get(10, TimeUnit.SECONDS);

            assertEquals(leftPath, rightPath);
            assertEquals("name: sales\ndescription: approved\n", Files.readString(leftPath.resolve("skills/sales/SKILL.md")));
            assertTrue(Files.exists(leftPath.resolve(".complete")));
            try (var children = Files.list(tempDir)) {
                assertFalse(children.anyMatch(path -> path.getFileName().toString().contains(".tmp-")));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static RuntimeDefinitionSnapshot skilledDefinition(String bundleHash) throws Exception {
        String content = "name: sales\ndescription: approved\n";
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        RuntimeWorkspaceFile file = new RuntimeWorkspaceFile("skills/sales/SKILL.md", content, hash, bytes.length,
                "skill.sales", "1", "SKILL");
        Map<String, Object> fileEntry = Map.of("relativePath", file.relativePath(), "sha256", file.sha256(),
                "byteSize", file.byteSize(), "capabilityCode", file.capabilityCode(),
                "capabilityRevision", file.capabilityRevision(), "capabilityType", file.capabilityType());
        String manifest = CanonicalJson.write(Map.of("schemaVersion", 1, "agents", "AGENTS.md",
                "runtimeProfile", "SINGLE_SKILLED", "skills", List.of(Map.of("capabilityCode", "skill.sales",
                        "revision", "1", "path", "skills/sales")), "subagents", List.of(), "knowledge", List.of(),
                "files", List.of(fileEntry)));
        String instructions = "你是销售专家。";
        String workspaceHash = CanonicalJson.sha256(Map.of("instructions", instructions,
                "manifestJson", manifest, "fileManifest", List.of(fileEntry)));
        return new RuntimeDefinitionSnapshot(1L, "销售专家", instructions, "openai", "test-model", 3,
                bundleHash, "workspace-projection", workspaceHash, manifest, List.of(),
                new RuntimeEmployeeConfiguration(1, RuntimeProfile.SINGLE_SKILLED,
                        new RuntimeEmployeeConfiguration.RuntimePolicy(3, 0, 0, 30, false), null, List.of()),
                List.of(file));
    }

    private static boolean isReadOnly(Path path) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) return dos.readAttributes().isReadOnly();
        return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
                .noneMatch(permission -> permission == PosixFilePermission.OWNER_WRITE
                        || permission == PosixFilePermission.GROUP_WRITE || permission == PosixFilePermission.OTHERS_WRITE);
    }

    private static void makeWritable(Path path) throws IOException {
        DosFileAttributeView dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null) dos.setReadOnly(false);
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
