package com.haizhuo.brain.infrastructure.harness;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.harness.SessionBridgeSnapshot;
import com.haizhuo.brain.platform.harness.SessionHarnessBinding;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话 → Harness 绑定与一次性桥接快照（spec §53/§25-§26）：scope 唯一、键唯一、
 * 快照 insert-only；绑定引用快照 id，供 §23 上下文工厂恢复 harnessSessionKey /
 * workspaceRuntimeKey / bridgeContext。
 */
class JdbcHarnessBindingBridgeTest {

    private static final UserId OWNER = new UserId(42);
    private static final SessionId SESSION = new SessionId("session-1");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private JdbcSessionBridgeSnapshotRepository snapshots;
    private JdbcSessionHarnessBindingRepository bindings;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:binding_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE platform_session_bridge_snapshot(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,session_id VARCHAR(64) NOT NULL,definition_version_id BIGINT NOT NULL,"
                + "generation INT NOT NULL,source_event_sequence INT NOT NULL,conversation_summary TEXT NOT NULL,"
                + "stable_business_facts_json TEXT NOT NULL,schema_version INT NOT NULL,content_hash CHAR(64) NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,UNIQUE(user_id,session_id,definition_version_id,generation))");
        jdbc.execute("CREATE TABLE platform_session_harness_binding(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id BIGINT NOT NULL,session_id VARCHAR(64) NOT NULL,employee_id BIGINT NOT NULL,"
                + "definition_version_id BIGINT NOT NULL,generation INT NOT NULL,"
                + "harness_session_key VARCHAR(96) NOT NULL,workspace_runtime_key VARCHAR(96) NOT NULL,"
                + "bridge_snapshot_id BIGINT NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "UNIQUE(user_id,session_id,definition_version_id,generation),"
                + "UNIQUE(harness_session_key),UNIQUE(workspace_runtime_key))");
        snapshots = new JdbcSessionBridgeSnapshotRepository(jdbc);
        bindings = new JdbcSessionHarnessBindingRepository(jdbc);
    }

    @Test
    void snapshotAndBindingRoundTrip() {
        SessionBridgeSnapshot snapshot = snapshots.save(new SessionBridgeSnapshot(0L, OWNER, SESSION, 7L, 1, 12,
                "用户要订明天下午的会议室。", "{\"preferredRoom\":\"A-201\"}", 1, "h".repeat(64), NOW));
        assertTrue(snapshot.id() > 0);

        SessionHarnessBinding binding = bindings.save(new SessionHarnessBinding(0L, OWNER, SESSION, 1L, 7L, 1,
                "hs-key-1", "ws-key-1", snapshot.id(), NOW));
        assertTrue(binding.id() > 0);

        SessionHarnessBinding restored = bindings.findByScope(OWNER, SESSION, 7L, 1).orElseThrow();
        assertEquals("hs-key-1", restored.harnessSessionKey());
        assertEquals("ws-key-1", restored.workspaceRuntimeKey());
        assertEquals(snapshot.id(), restored.bridgeSnapshotId());
        assertEquals(1L, restored.employeeId());

        SessionBridgeSnapshot restoredSnapshot = snapshots.findById(restored.bridgeSnapshotId()).orElseThrow();
        assertEquals("用户要订明天下午的会议室。", restoredSnapshot.conversationSummary());
        assertEquals("{\"preferredRoom\":\"A-201\"}", restoredSnapshot.stableBusinessFactsJson());
        assertEquals(12, restoredSnapshot.sourceEventSequence());
        assertEquals(1, restoredSnapshot.schemaVersion());
        assertEquals("h".repeat(64), restoredSnapshot.contentHash());

        assertTrue(bindings.findByScope(OWNER, SESSION, 8L, 1).isEmpty(), "其他定义版本互不可见");
        assertTrue(bindings.findByScope(new UserId(43), SESSION, 7L, 1).isEmpty(), "属主隔离");
        assertTrue(snapshots.findById(999999L).isEmpty());
    }

    @Test
    void scopeAndKeysAreUnique() {
        SessionBridgeSnapshot snapshot = snapshots.save(new SessionBridgeSnapshot(0L, OWNER, SESSION, 7L, 1, 1,
                "摘要", "{}", 1, "h".repeat(64), NOW));
        bindings.save(new SessionHarnessBinding(0L, OWNER, SESSION, 1L, 7L, 1, "hs-key-1", "ws-key-1", snapshot.id(), NOW));

        assertThrows(Exception.class, () -> bindings.save(new SessionHarnessBinding(0L, OWNER, SESSION, 1L, 7L, 1,
                "hs-key-2", "ws-key-2", snapshot.id(), NOW)), "同 scope 绑定必须唯一（重复创建由 Service 层幂等返回）");
        assertThrows(Exception.class, () -> bindings.save(new SessionHarnessBinding(0L, new UserId(43), SESSION, 1L, 7L, 1,
                "hs-key-1", "ws-key-3", snapshot.id(), NOW)), "harnessSessionKey 全局唯一");
        assertThrows(Exception.class, () -> snapshots.save(new SessionBridgeSnapshot(0L, OWNER, SESSION, 7L, 1, 2,
                "另一份摘要", "{}", 1, "g".repeat(64), NOW)), "同 scope 快照 insert-only 且唯一");
    }
}
