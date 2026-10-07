package com.haizhuo.brain.infrastructure.session;

import static org.junit.jupiter.api.Assertions.*;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SessionFixedIdentityMigrationTest {
    @Test
    void migrationPinsLatestRunAndLeavesRuntimeDataUntouched() {
        var jdbc = newJdbc("session_identity_migration");
        jdbc.execute("CREATE TABLE agent_definition_version(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE platform_agent_session(session_id VARCHAR(64) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(64),session_id VARCHAR(64),"
                + "definition_version_id BIGINT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE agentscope_sessions(session_id VARCHAR(255),state_data TEXT)");
        jdbc.update("INSERT INTO agent_definition_version VALUES(1),(2)");
        jdbc.update("INSERT INTO platform_agent_session VALUES('old'),('empty')");
        jdbc.update("INSERT INTO platform_agent_run VALUES('a','old',1,'2026-09-01 00:00:00'),"
                + "('b','old',2,'2026-09-02 00:00:00')");
        jdbc.update("INSERT INTO agentscope_sessions VALUES('42:hs-old','original-state')");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V19__session_fixed_runtime_identity.sql"))
                .execute(jdbc.getDataSource());
        assertEquals(2L, jdbc.queryForObject("SELECT definition_version_id FROM platform_agent_session WHERE session_id='old'", Long.class));
        assertNull(jdbc.queryForObject("SELECT definition_version_id FROM platform_agent_session WHERE session_id='empty'", Long.class));
        assertTrue(jdbc.queryForObject("SELECT legacy_runtime FROM platform_agent_session WHERE session_id='old'", Boolean.class));
        assertEquals("original-state", jdbc.queryForObject("SELECT state_data FROM agentscope_sessions WHERE session_id='42:hs-old'", String.class));
    }
}
