package com.haizhuo.brain.infrastructure.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RunArtifact;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunArtifactIndexTest {
    private static final Instant NOW = Instant.parse("2026-10-09T04:00:00Z");
    private JdbcTemplate jdbc;
    private JdbcRunArtifactIndex index;

    @BeforeEach
    void setUp() {
        jdbc = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc("runartifact");
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,state VARCHAR(32))");
        jdbc.update("INSERT INTO platform_agent_run(run_id,user_id,state) VALUES('run-a',7,'SUCCEEDED')");
        jdbc.execute("CREATE TABLE platform_agent_result(result_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(36),"
                + "kind VARCHAR(32),visibility VARCHAR(16),body_sha256 CHAR(64),byte_size BIGINT)");
        jdbc.update("INSERT INTO platform_agent_result(result_id,run_id,kind,visibility,body_sha256,byte_size) "
                + "VALUES('result-a','run-a','ROOT_FINAL','USER',?,8)", "b".repeat(64));
        jdbc.execute("CREATE TABLE platform_run_artifact(artifact_id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36),"
                + "result_id VARCHAR(64),owner_user_id BIGINT,request_id VARCHAR(128),request_digest CHAR(64),"
                + "visibility VARCHAR(16),title VARCHAR(160),media_type VARCHAR(128),byte_size BIGINT,sha256 CHAR(64),"
                + "blob_ref VARCHAR(128),state VARCHAR(16),created_at TIMESTAMP,"
                + "UNIQUE(run_id,owner_user_id,request_id))");
        index = new JdbcRunArtifactIndex(jdbc);
    }

    @Test
    void stageIsIdempotentAndAvailabilityRequiresMatchingImmutableMetadata() {
        RunArtifact proposed = artifact(UUID.randomUUID().toString(), "request-a", 7);
        var first = index.stage(proposed);
        var replay = index.stage(artifact(UUID.randomUUID().toString(), "request-a", 7));

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(proposed.artifactId(), replay.artifact().artifactId());
        assertEquals(RunArtifact.State.STAGED, replay.artifact().state());

        RunArtifact available = index.markAvailable(proposed.artifactId(), new UserId(7), proposed.sha256(),
                proposed.byteSize());
        assertEquals(RunArtifact.State.AVAILABLE, available.state());
        index.markUnavailable(proposed.artifactId(), new UserId(7));
        RunArtifact repaired = index.markAvailable(proposed.artifactId(), new UserId(7), proposed.sha256(),
                proposed.byteSize());
        assertEquals(RunArtifact.State.AVAILABLE, repaired.state());
    }

    @Test
    void lookupAndPageAlwaysRequireTheExactOwner() {
        RunArtifact owned = artifact(UUID.randomUUID().toString(), "request-a", 7);
        index.stage(owned);
        index.stage(artifact(UUID.randomUUID().toString(), "request-b", 8));

        assertTrue(index.find(owned.artifactId(), new UserId(7)).isPresent());
        assertTrue(index.find(owned.artifactId(), new UserId(8)).isEmpty());
        assertEquals(1, index.page(new RunId("run-a"), new UserId(7), null, 20).items().size());
        assertTrue(index.page(new RunId("run-a"), new UserId(8), null, 20).items().isEmpty());
    }

    private static RunArtifact artifact(String id, String requestId, long owner) {
        return new RunArtifact(id, new RunId("run-a"), "result-a", new UserId(owner), requestId,
                "a".repeat(64), "运行结果", "text/markdown", 8, "b".repeat(64), id,
                RunArtifact.State.STAGED, NOW);
    }
}
