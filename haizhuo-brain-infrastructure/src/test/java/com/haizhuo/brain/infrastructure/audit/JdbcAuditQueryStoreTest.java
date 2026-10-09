package com.haizhuo.brain.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport;
import com.haizhuo.brain.platform.audit.AuditDomain;
import com.haizhuo.brain.platform.audit.AuditFilter;
import com.haizhuo.brain.platform.audit.AuditQueryService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

public class JdbcAuditQueryStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    void globalCursorMergesEqualTimesAndDetailsNeverReturnRawSensitiveColumns() {
        Fixture fixture = fixture("audit_merge");
        JdbcTemplate jdbc = fixture.jdbc();
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("INSERT INTO platform_user_audit VALUES(?,?,?,?,?,?,?,?)", 3L, 7L, 9L, "USER_STATUS_CHANGED",
                "{\"status\":\"ACTIVE\",\"password\":\"private-old\"}",
                "{\"status\":\"DISABLED\",\"token\":\"private-new\"}", "private reason", at);
        jdbc.update("INSERT INTO platform_authentication_audit VALUES(?,?,?,?,?,?)", 7L, "LOGIN_FAILED",
                "subject-private-digest", "source-private-digest", "PASSWORD_INVALID", at);
        jdbc.update("INSERT INTO platform_channel_management_audit VALUES(?,?,?,?,?,?,?,?,?,?,?)", 4L, "binding-4",
                "external-private", "CREATE_ACCOUNT", 7L, "request-4", "private reason", "ADMIN_UI",
                "payload-private-hash", "provider=slack,enabled=true,credentialRef=private-secret", at);
        jdbc.update("INSERT INTO agent_definition_management_audit VALUES(?,?,?,?,?,?,?,?,?,?)", 5L, 7L,
                "MCP_CONNECTION_CREATED", "MCP_CONNECTION", "8", "request-mcp", "reason-private", "{}",
                "{\"endpoint\":\"https://private.example\",\"apiKey\":\"private-secret\"}", at);
        jdbc.update("INSERT INTO tool_invocation_audit VALUES(?,?,?,?,?,?,?,?,?,?,?)", "00000000-0000-0000-0000-000000000007",
                "run-7", "capability.private", "1", 7L, "mcp.write", "argument-private-hash", "ALLOW",
                "FAILED", "private tool output", at);

        var service = new AuditQueryService(fixture.store(), Clock.fixed(NOW, ZoneOffset.UTC));
        var first = service.list(7, null, null, null, null, null, null, null, null, null, null, 1);
        var second = service.list(7, null, null, null, null, null, null, null, null, null, first.nextCursor(), 1);
        var third = service.list(7, null, null, null, null, null, null, null, null, null, second.nextCursor(), 1);
        var fourth = service.list(7, null, null, null, null, null, null, null, null, null, third.nextCursor(), 1);

        assertEquals(List.of("USER:00000000000000000003", "TOOL:00000000-0000-0000-0000-000000000007",
                        "MCP:00000000000000000005", "CHANNEL:00000000000000000004"),
                List.of(first.items().get(0).auditId(), second.items().get(0).auditId(),
                        third.items().get(0).auditId(), fourth.items().get(0).auditId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_management_audit WHERE event_type='MCP_CONNECTION_CREATED'",
                Integer.class));
        var userDetail = service.detail(7, first.items().get(0).auditId());
        assertEquals("停用", userDetail.safeChanges().get(0).newLabel());
        assertFalse(userDetail.toString().contains("private-old"));
        assertFalse(userDetail.toString().contains("private-new"));

        var mcpId = "MCP:00000000000000000005";
        var mcpDetail = service.detail(7, mcpId);
        assertTrue(mcpDetail.summary().safeSummary().contains("连接地址已隐藏"));
        assertFalse(mcpDetail.toString().contains("private.example"));
        assertFalse(mcpDetail.toString().contains("private-secret"));

        var tool = fixture.store().findCandidates(new AuditFilter(AuditDomain.TOOL, null, null, null, null,
                null, null, null, null), NOW, null, 10).get(0);
        assertEquals("FAILED", tool.outcome().name());
        assertFalse(tool.toString().contains("private tool output"));
        assertFalse(tool.toString().contains("argument-private-hash"));

        var channelId = "CHANNEL:00000000000000000004";
        var channelDetail = service.detail(7, channelId);
        assertTrue(channelDetail.safeChanges().stream().anyMatch(change -> change.field().equals("启用状态")));
        assertFalse(channelDetail.toString().contains("external-private"));
        assertFalse(channelDetail.toString().contains("private-secret"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_read", Integer.class));
        String readDigest = jdbc.queryForObject("SELECT target_digest FROM platform_admin_audit_read ORDER BY audit_read_id LIMIT 1", String.class);
        assertTrue(readDigest.matches("[0-9a-f]{64}"));
        assertFalse(readDigest.contains("binding-4"));
    }

    @Test
    void runRecoveryUsesEncodedStableIdAndDoesNotExposeStopEvidence() {
        Fixture fixture = fixture("audit_recovery");
        String requestId = "evidence/path:request";
        fixture.jdbc().update("INSERT INTO platform_run_recovery_action VALUES(?,?,?,?,?,?,?)", requestId,
                "00000000-0000-0000-0000-000000000017", 7L, 2L, "C:\\private\\worker.log", "private reason",
                Timestamp.from(NOW));
        var service = new AuditQueryService(fixture.store(), Clock.fixed(NOW, ZoneOffset.UTC));
        String auditId = "RUN_RECOVERY:" + HexFormat.of().formatHex(requestId.getBytes(StandardCharsets.UTF_8));

        var detail = service.detail(7, auditId);
        var uppercaseDetail = service.detail(7, auditId.toUpperCase(java.util.Locale.ROOT));

        assertEquals("RUN_RECOVERY_TERMINATED", detail.summary().action());
        assertEquals(detail.summary().auditId(), uppercaseDetail.summary().auditId());
        assertEquals("停止核查证据引用已记录", detail.evidenceReferenceLabel());
        assertFalse(detail.toString().contains("C:\\private"));
        assertFalse(detail.toString().contains("private reason"));
    }

    @Test
    void unknownActionsStayUnknownAndUnsafeTargetIdsAreOmitted() {
        Fixture fixture = fixture("audit_unknown_action");
        fixture.jdbc().update("INSERT INTO agent_definition_management_audit VALUES(?,?,?,?,?,?,?,?,?,?)",
                1L, 7L, "UNRECOGNIZED_ACTION", "DIGITAL_EMPLOYEE", "bad\ntarget", null,
                "private reason", "{}", "{}", Timestamp.from(NOW));

        var entry = fixture.store().findCandidates(new AuditFilter(AuditDomain.DEFINITION, null, null,
                null, null, null, null, null, null), NOW, null, 10).get(0);

        assertEquals("OTHER", entry.action());
        assertEquals("UNKNOWN", entry.outcome().name());
        assertNull(entry.targetId());
    }

    @Test
    void exportRejectsThe10001stRowAndReadsValidBoundedRangesInOrder() {
        Fixture fixture = fixture("audit_export");
        JdbcTemplate jdbc = fixture.jdbc();
        var batch = new java.util.ArrayList<Object[]>(10_001);
        for (int id = 1; id <= 10_001; id++) {
            batch.add(new Object[]{(long) id, 7L, (long) (id + 100_000), "USER_CREATED", null,
                    "{\"status\":\"PENDING_ACTIVATION\"}", "reason", Timestamp.from(NOW)});
        }
        jdbc.batchUpdate("INSERT INTO platform_user_audit VALUES(?,?,?,?,?,?,?,?)", batch);

        var service = new AuditQueryService(fixture.store(), Clock.fixed(NOW, ZoneOffset.UTC));
        var tooLarge = assertThrows(AuditQueryService.AuditQueryException.class,
                () -> service.export(7, "USER", null, null, null, null, null, null,
                        "2026-10-08T00:00:00Z", "2026-10-10T00:00:00Z"));
        assertEquals(AuditQueryService.Error.EXPORT_TOO_LARGE, tooLarge.error());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_admin_audit_read", Integer.class));
    }

    private static Fixture fixture(String name) {
        JdbcTemplate jdbc = HarnessJdbcTestSupport.newJdbc(name);
        jdbc.execute("CREATE ALIAS IF NOT EXISTS HEX FOR 'com.haizhuo.brain.infrastructure.audit.JdbcAuditQueryStoreTest.hex'");
        jdbc.execute("CREATE TABLE platform_user_audit(id BIGINT PRIMARY KEY,actor_user_id BIGINT,target_user_id BIGINT NOT NULL,event_type VARCHAR(64),previous_state TEXT,new_state TEXT,reason VARCHAR(500),occurred_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE platform_authentication_audit(id BIGINT PRIMARY KEY,event_type VARCHAR(64),subject_digest CHAR(64),source_digest CHAR(64),failure_category VARCHAR(64),occurred_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE agent_definition_management_audit(id BIGINT PRIMARY KEY,actor_user_id BIGINT,event_type VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(128),request_id VARCHAR(128),reason VARCHAR(500),previous_summary TEXT,new_summary TEXT,occurred_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE capability_asset_audit(id BIGINT PRIMARY KEY,capability_code VARCHAR(128),action VARCHAR(32),actor_user_id BIGINT,request_id VARCHAR(128),reason VARCHAR(500),previous_hash CHAR(64),new_hash CHAR(64),occurred_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE tool_invocation_audit(invocation_id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36),capability_code VARCHAR(128),capability_revision VARCHAR(32),user_id BIGINT,business_action VARCHAR(128),arguments_hash CHAR(64),decision VARCHAR(24),result_status VARCHAR(24),result_summary VARCHAR(1000),created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE platform_run_recovery_action(request_id VARCHAR(128) PRIMARY KEY,run_id VARCHAR(36),actor_user_id BIGINT,expected_fence_token BIGINT,stop_evidence_reference VARCHAR(512),reason VARCHAR(500),created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE platform_channel_management_audit(audit_id BIGINT PRIMARY KEY,binding_id VARCHAR(64),external_user_id VARCHAR(128),action_code VARCHAR(48),actor_user_id BIGINT,request_id VARCHAR(128),reason VARCHAR(500),client_source VARCHAR(32),payload_hash CHAR(64),safe_changes TEXT,created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE platform_admin_audit_read(audit_read_id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_user_id BIGINT,access_kind VARCHAR(16),target_digest CHAR(64),result_count INT,created_at TIMESTAMP NOT NULL)");
        return new Fixture(jdbc, new JdbcAuditQueryStore(jdbc, new ObjectMapper(),
                new DataSourceTransactionManager(jdbc.getDataSource())));
    }

    private record Fixture(JdbcTemplate jdbc, JdbcAuditQueryStore store) { }

    public static String hex(String value) {
        return java.util.HexFormat.of().withUpperCase().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
