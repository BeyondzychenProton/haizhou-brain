package com.haizhuo.brain.infrastructure.session;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 冻结 run spec 的往返读写（spec §52.2）；写入路径另见 createRun 的同事务冻结测试。 */
class JdbcHarnessRunSpecRepositoryTest {

    private JdbcHarnessRunSpecRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:runspec_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE platform_agent_run_spec(run_id VARCHAR(64) PRIMARY KEY,"
                + "definition_version_id BIGINT NOT NULL,definition_bundle_id BIGINT NOT NULL,"
                + "definition_bundle_hash CHAR(64) NOT NULL,model_visible_tool_names_json TEXT NOT NULL,"
                + "tool_view_hash CHAR(64) NOT NULL,effective_capability_hash CHAR(64) NOT NULL,"
                + "channel_type VARCHAR(32) NULL,created_at TIMESTAMP NOT NULL)");
        repository = new JdbcHarnessRunSpecRepository(jdbc);
    }

    @Test
    void savesAndFindsFrozenSpec() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        HarnessRunSpec spec = new HarnessRunSpec(new RunId("run-1"), 7L, 3L, "b".repeat(64),
                "[\"meeting_room_search\",\"meeting_room_reserve\"]", "t".repeat(64), "e".repeat(64), "web", now);

        repository.save(spec);
        HarnessRunSpec restored = repository.findByRunId(new RunId("run-1")).orElseThrow();

        assertEquals(7L, restored.definitionVersionId());
        assertEquals(3L, restored.definitionBundleId());
        assertEquals("b".repeat(64), restored.definitionBundleHash());
        assertEquals("[\"meeting_room_search\",\"meeting_room_reserve\"]", restored.modelVisibleToolNamesJson());
        assertEquals("t".repeat(64), restored.toolViewHash());
        assertEquals("e".repeat(64), restored.effectiveCapabilityHash());
        assertEquals("web", restored.channelType());
        assertEquals(now, restored.createdAt());
        assertTrue(repository.findByRunId(new RunId("run-missing")).isEmpty());
    }
}
