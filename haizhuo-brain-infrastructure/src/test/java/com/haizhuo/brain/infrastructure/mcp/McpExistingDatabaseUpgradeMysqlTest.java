package com.haizhuo.brain.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/** Exercises V17/V18 against an existing MySQL 8.4 schema with employee and capability data. */
class McpExistingDatabaseUpgradeMysqlTest {

    private static final String CAPABILITY_CODE = "legacy.private_note";
    private static final String TOOL_NAME = "legacy_note_read";

    @Test
    void existingEmployeeAndCapabilitySurviveUpgradeAndToolNameClaimIsBackfilled() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(),
                    mysql.getUsername(), mysql.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            assertTrue(flyway(dataSource, "16").migrate().success);
            seedExistingEmployee(jdbc);
            long originalRevisionId = jdbc.queryForObject("SELECT capability_revision_id FROM capability_revision "
                    + "WHERE capability_code=? AND revision='1'", Long.class, CAPABILITY_CODE);

            assertTrue(indexExists(jdbc, "uk_capability_revision_tool_name"));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insertRevision(jdbc, CAPABILITY_CODE, "2", TOOL_NAME));

            assertTrue(flyway(dataSource, null).migrate().success);

            assertFalse(indexExists(jdbc, "uk_capability_revision_tool_name"));
            assertEquals(originalRevisionId, jdbc.queryForObject("SELECT capability_revision_id "
                    + "FROM capability_revision WHERE capability_code=? AND revision='1'", Long.class,
                    CAPABILITY_CODE));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM digital_employee "
                    + "WHERE id=3101 AND employee_code='legacy-private-note' AND current_published_version_id=3101"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM agent_definition_draft_capability "
                    + "WHERE employee_id=3101 AND capability_code='legacy.private_note' AND capability_revision='1'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM agent_definition_version_capability "
                    + "WHERE definition_version_id=3101 AND capability_code='legacy.private_note' "
                    + "AND capability_revision='1'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM agent_user_capability_grant "
                    + "WHERE user_id=3101 AND capability_code='legacy.private_note' AND enabled=TRUE"));
            assertEquals(CAPABILITY_CODE, jdbc.queryForObject("SELECT capability_code "
                    + "FROM mcp_model_tool_name_claim WHERE tool_name=?", String.class, TOOL_NAME));

            insertRevision(jdbc, CAPABILITY_CODE, "2", TOOL_NAME);
            assertEquals(2, count(jdbc, "SELECT COUNT(*) FROM capability_revision "
                    + "WHERE capability_code='legacy.private_note' AND tool_name='legacy_note_read'"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM mcp_model_tool_name_claim "
                    + "WHERE tool_name='legacy_note_read' AND capability_code='legacy.private_note'"));
        }
    }

    @Test
    void conflictingCapabilityIntroducedAfterV17MakesV18FailWithoutLosingHistory() {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")) {
            mysql.start();
            var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(),
                    mysql.getUsername(), mysql.getPassword());
            var jdbc = new JdbcTemplate(dataSource);
            assertTrue(flyway(dataSource, "16").migrate().success);
            seedExistingEmployee(jdbc);
            assertTrue(flyway(dataSource, "17").migrate().success);
            assertFalse(indexExists(jdbc, "uk_capability_revision_tool_name"));

            jdbc.update("INSERT INTO capability_definition(capability_code,capability_type,status,updated_by,"
                    + "updated_at) VALUES('legacy.other','TOOL','ACTIVE',3101,NOW(3))");
            insertRevision(jdbc, "legacy.other", "1", TOOL_NAME);
            assertEquals(2, count(jdbc, "SELECT COUNT(*) FROM capability_revision "
                    + "WHERE tool_name='legacy_note_read'"));

            assertThrows(FlywayException.class, () -> flyway(dataSource, null).migrate());
            assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM flyway_schema_history "
                    + "WHERE version='18' AND success=TRUE"));
            assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM digital_employee "
                    + "WHERE id=3101 AND current_published_version_id=3101"));
            assertEquals(2, count(jdbc, "SELECT COUNT(*) FROM capability_revision "
                    + "WHERE tool_name='legacy_note_read'"));
        }
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static boolean indexExists(JdbcTemplate jdbc, String indexName) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='capability_revision' AND index_name=?",
                Integer.class, indexName) > 0;
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static void seedExistingEmployee(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO capability_definition(capability_code,capability_type,status,updated_by,"
                + "updated_at) VALUES(?,'TOOL','ACTIVE',3101,NOW(3))", CAPABILITY_CODE);
        insertRevision(jdbc, CAPABILITY_CODE, "1", TOOL_NAME);
        jdbc.update("INSERT INTO digital_employee(id,tenant_id,employee_code,display_name,enabled,row_version) "
                + "VALUES(3101,1,'legacy-private-note','既有便笺员工',TRUE,1)");
        jdbc.update("INSERT INTO agent_definition_draft(employee_id,draft_revision,instructions,model_provider,"
                + "model_name,updated_by,updated_at) VALUES(3101,1,'读取便笺','openai','legacy-model',3101,NOW(3))");
        jdbc.update("INSERT INTO agent_definition_draft_capability(employee_id,capability_code,"
                + "capability_revision,position_no) VALUES(3101,?,'1',1)", CAPABILITY_CODE);
        jdbc.update("INSERT INTO agent_definition_version(id,employee_id,version_no,instructions,"
                + "model_provider,model_name,content_hash,publish_request_id,published_by,published_at) "
                + "VALUES(3101,3101,1,'读取便笺','openai','legacy-model',?,"
                + "'legacy-publish-1',3101,NOW(3))", "a".repeat(64));
        jdbc.update("INSERT INTO agent_definition_version_capability(definition_version_id,capability_code,"
                + "capability_revision,position_no) VALUES(3101,?,'1',1)", CAPABILITY_CODE);
        jdbc.update("UPDATE digital_employee SET current_published_version_id=3101 WHERE id=3101");
        jdbc.update("INSERT INTO agent_user_capability_grant(user_id,capability_code,enabled,updated_by,"
                + "updated_at) VALUES(3101,?,TRUE,3101,NOW(3))", CAPABILITY_CODE);
    }

    private static void insertRevision(JdbcTemplate jdbc, String capabilityCode, String revision,
                                       String toolName) {
        jdbc.update("INSERT INTO capability_revision(capability_code,revision,display_name,description,"
                + "tool_name,implementation_key,business_action,input_schema_json,content_hash) "
                + "VALUES(?,?,'读取便笺','既有能力',?,'legacy-tool','legacy.note.read','{\"type\":\"object\"}',?)",
                capabilityCode, revision, toolName, "b".repeat(64));
    }
}
