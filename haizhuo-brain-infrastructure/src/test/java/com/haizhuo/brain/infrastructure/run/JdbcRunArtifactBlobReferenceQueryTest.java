package com.haizhuo.brain.infrastructure.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunArtifactBlobReferenceQueryTest {
    private JdbcTemplate jdbc;
    private JdbcRunArtifactBlobReferenceQuery query;
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        jdbc = HarnessJdbcTestSupport.newJdbc("run_artifact_blob_reference");
        jdbc.execute("CREATE TABLE platform_run_artifact(blob_ref VARCHAR(128) NOT NULL,state VARCHAR(16) NOT NULL)");
        query = new JdbcRunArtifactBlobReferenceQuery(jdbc);
        Path target = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(target);
        root = Files.createTempDirectory(target, "run-artifact-reference-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @Test
    void exactLookupCountsEveryIndexStateAndDoesNotUsePrefixes() {
        jdbc.update("INSERT INTO platform_run_artifact(blob_ref,state) VALUES(?,?)", "blob-staged", "STAGED");
        jdbc.update("INSERT INTO platform_run_artifact(blob_ref,state) VALUES(?,?)", "blob-available", "AVAILABLE");
        jdbc.update("INSERT INTO platform_run_artifact(blob_ref,state) VALUES(?,?)", "blob-unavailable", "UNAVAILABLE");

        assertTrue(query.isBlobRefReferenced("blob-staged"));
        assertTrue(query.isBlobRefReferenced("blob-available"));
        assertTrue(query.isBlobRefReferenced("blob-unavailable"));
        assertFalse(query.isBlobRefReferenced("blob-staged-extra"));
        assertFalse(query.isBlobRefReferenced("not-present"));
    }

    @Test
    void stagedReferencePreventsExpiredBlobDeletion() throws Exception {
        String blobRef = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_run_artifact(blob_ref,state) VALUES(?,?)", blobRef, "STAGED");
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        blobs.write(blobRef, new byte[] {1, 2, 3});
        Path file = root.resolve(blobRef + ".blob");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(8))));

        var result = blobs.cleanupExpired(Instant.now().minus(Duration.ofDays(7)), 1_000, 25,
                Duration.ofSeconds(5), query::isBlobRefReferenced);

        assertTrue(Files.isRegularFile(file));
        assertTrue(result.retained() >= 1);
    }

    @Test
    void databaseFailureLeavesExpiredBlobUntouched() throws Exception {
        String blobRef = UUID.randomUUID().toString();
        PrivateDirectoryRunArtifactBlobStore blobs = new PrivateDirectoryRunArtifactBlobStore(root);
        blobs.write(blobRef, new byte[] {4, 5, 6});
        Path file = root.resolve(blobRef + ".blob");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        jdbc.execute("DROP TABLE platform_run_artifact");

        assertThrows(DataAccessException.class, () -> blobs.cleanupExpired(Instant.now().minus(Duration.ofDays(7)),
                1_000, 25, Duration.ofSeconds(5), query::isBlobRefReferenced));
        assertTrue(Files.isRegularFile(file));
    }
}
