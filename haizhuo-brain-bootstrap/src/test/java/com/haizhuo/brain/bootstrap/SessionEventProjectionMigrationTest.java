package com.haizhuo.brain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 守住 V14 的会话事件投影迁移：建表 + 历史事件补入必须能在 MySQL 兼容库上执行，
 * 且补入的 session_cursor 在同一 Session 内从 1 开始按 (created_at, run_id, sequence_no) 递增。
 * 测试库关闭了 Flyway，所以这条用例直接跑迁移脚本本身，避免脚本只在上线时才第一次执行。
 */
class SessionEventProjectionMigrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V14__session_event_projection.sql";

    @Test
    void migrationCreatesProjectionAndBackfillsHistoryPerSession() throws SQLException, IOException {
        JdbcTemplate jdbc = new JdbcTemplate(newH2());
        createMinimalRunTables(jdbc);
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,last_active_at,row_version)"
                + " VALUES('session-1',42,1,'ACTIVE',?,?,0)", java.sql.Timestamp.valueOf("2026-01-01 00:00:00"),
                java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,last_active_at,row_version)"
                + " VALUES('session-2',43,1,'ACTIVE',?,?,0)", java.sql.Timestamp.valueOf("2026-01-01 00:00:00"),
                java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,"
                + "client_request_id,input_digest,state,created_at) VALUES('run-1','session-1',42,1,1,'req-1','d','SUCCEEDED',?)",
                java.sql.Timestamp.valueOf("2026-01-01 00:00:01"));
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,"
                + "client_request_id,input_digest,state,created_at) VALUES('run-2','session-2',43,1,1,'req-2','d','SUCCEEDED',?)",
                java.sql.Timestamp.valueOf("2026-01-01 00:00:02"));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES('run-1',1,'USER_INPUT','一',?)",
                java.sql.Timestamp.valueOf("2026-01-01 00:00:01"));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES('run-1',2,'RUN_COMPLETED','结果一',?)",
                java.sql.Timestamp.valueOf("2026-01-01 00:00:03"));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at) VALUES('run-2',1,'USER_INPUT','二',?)",
                java.sql.Timestamp.valueOf("2026-01-01 00:00:02"));

        try (Connection connection = jdbc.getDataSource().getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements(readMigration())) {
                statement.execute(sql);
            }
        }

        assertEquals(List.of(1L, 2L), jdbc.query("SELECT session_cursor FROM platform_agent_session_event"
                        + " WHERE session_id='session-1' ORDER BY session_cursor", (rs, row) -> rs.getLong(1)),
                "历史补入必须从 1 开始并在同一 Session 内递增");
        assertEquals(List.of(1L), jdbc.query("SELECT session_cursor FROM platform_agent_session_event"
                        + " WHERE session_id='session-2' ORDER BY session_cursor", (rs, row) -> rs.getLong(1)),
                "每个 Session 独立编号");
        assertEquals(List.of("USER_INPUT", "RUN_COMPLETED"), jdbc.query("SELECT event_type FROM platform_agent_session_event"
                        + " WHERE session_id='session-1' ORDER BY session_cursor", (rs, row) -> rs.getString(1)));
        assertEquals(List.of(1, 2), jdbc.query("SELECT run_sequence FROM platform_agent_session_event"
                        + " WHERE session_id='session-1' ORDER BY session_cursor", (rs, row) -> rs.getInt(1)),
                "投影必须保留事件在原 Run 中的序号");
        assertEquals("USER", jdbc.queryForObject("SELECT visibility FROM platform_agent_session_event"
                + " WHERE session_id='session-1' AND session_cursor=1", String.class));
    }

    private static void createMinimalRunTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_agent_session(session_id VARCHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL,"
                + "employee_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "last_active_at TIMESTAMP NOT NULL,row_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(64) PRIMARY KEY,session_id VARCHAR(64) NOT NULL,"
                + "user_id BIGINT NOT NULL,employee_id BIGINT NOT NULL,definition_version_id BIGINT NOT NULL,"
                + "client_request_id VARCHAR(128) NOT NULL,input_digest CHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE platform_agent_run_event(run_id VARCHAR(64) NOT NULL,sequence_no INT NOT NULL,"
                + "event_type VARCHAR(64) NOT NULL,content VARCHAR(4000) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(run_id,sequence_no))");
    }

    /** 迁移脚本不含字符串字面量里的分号，可以按语句切分后逐条执行。 */
    private static List<String> statements(String sql) {
        List<String> statements = new java.util.ArrayList<>();
        for (String raw : sql.split(";")) {
            StringBuilder kept = new StringBuilder();
            for (String line : raw.split("\n")) {
                if (!line.trim().startsWith("--")) {
                    kept.append(line).append('\n');
                }
            }
            String trimmed = kept.toString().trim();
            if (!trimmed.isEmpty()) {
                statements.add(trimmed);
            }
        }
        return statements;
    }

    private static String readMigration() throws IOException {
        try (InputStream in = SessionEventProjectionMigrationTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION_RESOURCE)) {
            assertNotNull(in, "classpath 上找不到迁移脚本 " + MIGRATION_RESOURCE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static DataSource newH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:v14_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        h2.setUser("sa");
        return h2;
    }
}
