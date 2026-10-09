package com.haizhuo.brain.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.channel.ChannelManagementCommandExecutor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcChannelManagementCommandExecutorTest {
    private JdbcTemplate jdbc;
    private JdbcChannelManagementCommandExecutor commands;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:channel_commands_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE platform_channel_management_receipt(actor_user_id BIGINT NOT NULL,"
                + "request_id VARCHAR(128) NOT NULL,action_code VARCHAR(48) NOT NULL,binding_id VARCHAR(64) NOT NULL,"
                + "external_user_id VARCHAR(128),payload_hash CHAR(64) NOT NULL,result_json CLOB,created_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(actor_user_id,request_id))");
        jdbc.execute("CREATE TABLE platform_channel_management_audit(audit_id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "binding_id VARCHAR(64) NOT NULL,external_user_id VARCHAR(128),action_code VARCHAR(48) NOT NULL,"
                + "actor_user_id BIGINT NOT NULL,request_id VARCHAR(128),reason VARCHAR(500),client_source VARCHAR(32) NOT NULL,"
                + "payload_hash CHAR(64) NOT NULL,safe_changes CLOB NOT NULL,created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE command_effect(id BIGINT PRIMARY KEY,effect_value VARCHAR(40) NOT NULL)");
        commands = new JdbcChannelManagementCommandExecutor(jdbc, new DataSourceTransactionManager(dataSource),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void sameRequestReplaysResponseAndDifferentPayloadConflictsWithoutDuplicateAudit() {
        var command = command("request-1", "create|account-1|provider=sim|reason=approved");
        AtomicInteger executions = new AtomicInteger();

        CommandReply original = commands.execute(command, CommandReply.class, () -> {
            int revision = executions.incrementAndGet();
            jdbc.update("INSERT INTO command_effect(id,effect_value) VALUES(?,?)", revision, "created");
            return new CommandReply("account-1", revision);
        });
        CommandReply replay = commands.execute(command, CommandReply.class,
                () -> new CommandReply("must-not-run", executions.incrementAndGet()));

        assertEquals(original, replay);
        assertEquals(1, executions.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM command_effect", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_management_audit", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT payload_hash FROM platform_channel_management_receipt "
                + "WHERE actor_user_id=42 AND request_id='request-1'", String.class).matches("[a-f0-9]{64}"));

        assertThrows(IllegalStateException.class, () -> commands.execute(
                command("request-1", "create|account-1|provider=sim|reason=changed"), CommandReply.class,
                () -> new CommandReply("wrong", 0)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_management_audit", Integer.class));
    }

    @Test
    void businessAuditAndReceiptRollBackTogetherWhenMutationFails() {
        assertThrows(IllegalStateException.class, () -> commands.execute(command("request-fail", "bad"),
                CommandReply.class, () -> {
                    jdbc.update("INSERT INTO command_effect(id,effect_value) VALUES(1,'partial')");
                    throw new IllegalStateException("simulated failure");
                }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_effect", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_management_receipt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_management_audit", Integer.class));
    }

    @Test
    void legacyCommandKeepsItsSourceAndDoesNotInventRequestMetadata() {
        var legacy = new ChannelManagementCommandExecutor.Command(42, "REVOKE_IDENTITY", "account-1", "external-1",
                null, null, "LEGACY_CLIENT", "revoke|account-1|external-1", "fields=state");

        commands.execute(legacy, CommandReply.class, () -> new CommandReply("account-1", 1));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_management_receipt", Integer.class));
        assertEquals("LEGACY_CLIENT", jdbc.queryForObject("SELECT client_source FROM platform_channel_management_audit",
                String.class));
        assertNull(jdbc.queryForObject("SELECT request_id FROM platform_channel_management_audit", String.class));
        assertNull(jdbc.queryForObject("SELECT reason FROM platform_channel_management_audit", String.class));
    }

    private static ChannelManagementCommandExecutor.Command command(String requestId, String payload) {
        return new ChannelManagementCommandExecutor.Command(42, "CREATE_ACCOUNT", "account-1", null,
                requestId, "配置已核准", "ADMIN_UI", payload, "fields=provider,enabled");
    }

    private record CommandReply(String bindingId, int revision) {
    }
}
