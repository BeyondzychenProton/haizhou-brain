package com.haizhuo.brain.infrastructure.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivateDirectoryRunArtifactBlobStoreTest {
    private Path root;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        root = Files.createTempDirectory(target, "run-artifact-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (outside != null) Files.deleteIfExists(outside);
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void storesByGeneratedIdAndNeverReplacesDifferentImmutableBytes() throws Exception {
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        String artifactId = UUID.randomUUID().toString();
        byte[] first = "<script>not inline</script>".getBytes(StandardCharsets.UTF_8);

        blobs.write(artifactId, first);
        blobs.write(artifactId, first);

        assertArrayEquals(first, blobs.read(artifactId).orElseThrow());
        assertThrows(IOException.class,
                () -> blobs.write(artifactId, "different".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> blobs.read("..\\outside"));
    }

    @Test
    void deletesOnlyExpiredUnreferencedRegularBlobsAndKeepsFreshOrReferencedFiles() throws Exception {
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        String oldOrphan = UUID.randomUUID().toString();
        String oldReferenced = UUID.randomUUID().toString();
        String fresh = UUID.randomUUID().toString();
        blobs.write(oldOrphan, new byte[] {1});
        blobs.write(oldReferenced, new byte[] {2});
        blobs.write(fresh, new byte[] {3});
        Instant now = Instant.now();
        Instant cutoff = now.minus(Duration.ofDays(7));
        Files.setLastModifiedTime(root.resolve(oldOrphan + ".blob"), FileTime.from(cutoff.minusSeconds(1)));
        Files.setLastModifiedTime(root.resolve(oldReferenced + ".blob"), FileTime.from(cutoff.minusSeconds(1)));

        var result = blobs.cleanupExpired(cutoff, 1000, 25, Duration.ofSeconds(5), oldReferenced::equals);

        assertEquals(1, result.deleted());
        assertFalse(Files.exists(root.resolve(oldOrphan + ".blob"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(root.resolve(oldReferenced + ".blob"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isRegularFile(root.resolve(fresh + ".blob"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void databaseFailureLeavesExpiredBlobUntouched() throws Exception {
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        String id = UUID.randomUUID().toString();
        blobs.write(id, new byte[] {7});
        Files.setLastModifiedTime(root.resolve(id + ".blob"), FileTime.from(Instant.now().minus(Duration.ofDays(8))));

        assertThrows(IllegalStateException.class, () -> blobs.cleanupExpired(Instant.now().minus(Duration.ofDays(7)),
                1000, 25, Duration.ofSeconds(5), ignored -> { throw new IllegalStateException("database unavailable"); }));
        assertTrue(Files.isRegularFile(root.resolve(id + ".blob"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rejectsOutsideReferencesAndNeverFollowsOrDeletesSymlinkTargets() throws Exception {
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        assertThrows(IOException.class, () -> blobs.write("..\\outside", new byte[] {1}));
        outside = root.resolveSibling(root.getFileName() + "-outside.blob");
        Files.write(outside, new byte[] {9});
        Files.setLastModifiedTime(outside, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        String id = UUID.randomUUID().toString();
        Path link = root.resolve(id + ".blob");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable in this environment");
        }

        blobs.cleanupExpired(Instant.now().minus(Duration.ofDays(7)), 1000, 25, Duration.ofSeconds(5), ignored -> false);

        assertTrue(Files.isSymbolicLink(link));
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(outside));
    }

    @Test
    void scanCursorAdvancesAcrossBatchesWithoutStarvingLaterOrphans() throws Exception {
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            blobs.write(id, new byte[] {(byte) i});
            Files.setLastModifiedTime(root.resolve(id + ".blob"), FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        }
        Set<String> protectedPrefix = new HashSet<>();
        try (var entries = Files.newDirectoryStream(root)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (name.endsWith(".blob") && protectedPrefix.size() < 5)
                    protectedPrefix.add(name.substring(0, name.length() - ".blob".length()));
            }
        }

        int deleted = 0;
        for (int i = 0; i < 40; i++) {
            var batch = blobs.cleanupExpired(Instant.now().minus(Duration.ofDays(7)), 5, 3,
                    Duration.ofSeconds(5), protectedPrefix::contains);
            assertTrue(batch.scanned() <= 5);
            assertTrue(batch.deleted() <= 3);
            deleted += batch.deleted();
        }
        assertEquals(ids.size() - protectedPrefix.size(), deleted);
        for (String id : protectedPrefix)
            assertTrue(Files.isRegularFile(root.resolve(id + ".blob"), LinkOption.NOFOLLOW_LINKS));
        for (String id : ids) {
            if (!protectedPrefix.contains(id))
                assertFalse(Files.exists(root.resolve(id + ".blob"), LinkOption.NOFOLLOW_LINKS));
        }

    }

    @Test
    void hardCapsScannedAndCandidateCountsEvenWhenCallerRequestsMore() throws Exception {
        for (int i = 0; i < 1_005; i++) {
            String id = UUID.randomUUID().toString();
            Path file = root.resolve(id + ".blob");
            Files.write(file, new byte[] {1});
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        }
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        var capped = blobs.cleanupExpired(Instant.now().minus(Duration.ofDays(7)), 10_000, 10_000,
                Duration.ofSeconds(5), ignored -> false);
        assertEquals(1_000, capped.scanned());
        assertEquals(25, capped.candidates());
        assertTrue(capped.deleted() <= 25);
    }
}
