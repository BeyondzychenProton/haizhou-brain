package com.haizhuo.brain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercises the new Run recovery DDL on an empty MySQL-compatible schema. */
class RunRecoveryMigrationTest {
    @Test
    void profileDefaultsAndRecoveryStateKeepTheSessionSlot() throws IOException {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:run_recovery_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(36) PRIMARY KEY,session_id VARCHAR(36) NOT NULL,"
                + "state VARCHAR(32) NOT NULL,active_marker TINYINT GENERATED ALWAYS AS "
                + "(CASE WHEN state IN ('QUEUED','RUNNING','WAITING_CONFIRMATION','WAITING_TOOL','CANCELLING') "
                + "THEN 1 ELSE NULL END))");
        jdbc.execute("CREATE UNIQUE INDEX uk_platform_agent_run_session_active "
                + "ON platform_agent_run(session_id,active_marker)");
        jdbc.execute("CREATE TABLE platform_user(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE platform_agent_session(session_id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL)");
        executeMigration(jdbc, "db/migration/V25__run_runtime_profile_and_recovery_slot.sql");
        executeMigration(jdbc, "db/migration/V26__run_recovery_action_audit.sql");
        executeMigration(jdbc, "db/migration/V27__session_expert_role_slots.sql");
        jdbc.execute("CREATE TABLE platform_agent_result(result_id VARCHAR(64) PRIMARY KEY)");
        executeMigration(jdbc, "db/migration/V28__run_result_references_and_executor_identity.sql");
        executeMigration(jdbc, "db/migration/V29__run_delegation_invocation_budget.sql");

        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,state) VALUES('legacy','s1','QUEUED')");
        assertEquals("LEGACY_STABLE", jdbc.queryForObject(
                "SELECT runtime_profile FROM platform_agent_run WHERE run_id='legacy'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT active_marker FROM platform_agent_run WHERE run_id='legacy'", Integer.class));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,state) "
                        + "VALUES('recovery','s1','RECOVERY_REQUIRED')"));
        jdbc.update("UPDATE platform_agent_run SET state='TERMINATED' WHERE run_id='legacy'");
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,state,runtime_profile) "
                + "VALUES('recovery','s1','RECOVERY_REQUIRED','SINGLE_SKILLED')");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name='platform_run_recovery_action'", Integer.class));
        assertTrue(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name='platform_run_recovery_action'", String.class).stream()
                .anyMatch("stop_evidence_reference"::equalsIgnoreCase));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name='platform_session_role_slot'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name='platform_agent_run_execution_target'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name='platform_agent_run_result_reference'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_name='platform_run_delegation_invocation'", Integer.class));
        assertTrue(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name='platform_run_delegation_invocation'", String.class).stream()
                .anyMatch("fence_token"::equalsIgnoreCase));
    }

    private static void executeMigration(JdbcTemplate jdbc, String resource) throws IOException {
        try (InputStream stream = RunRecoveryMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing " + resource);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?i)\\)\\s*ENGINE=InnoDB[^;]*;", ");")
                    .replaceAll("(?i)\\)\\s+STORED", ")");
            for (String raw : sql.split(";")) {
                String statement = raw.lines().filter(line -> !line.trim().startsWith("--"))
                        .reduce("", (left, right) -> left + " " + right).trim();
                if (!statement.isEmpty()) jdbc.execute(statement);
            }
        }
    }
}
