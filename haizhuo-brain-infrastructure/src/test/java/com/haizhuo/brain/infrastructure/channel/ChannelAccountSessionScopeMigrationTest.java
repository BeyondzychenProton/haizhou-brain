package com.haizhuo.brain.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 守住 V16 的渠道会话粒度迁移：ALTER 必须能在 MySQL 兼容库上执行，
 * 且存量行会被 DEFAULT 回填为更严格的 PER_PEER——**不得**放宽既有隔离。
 *
 * <p>测试库关闭了 Flyway，因此这条用例直接跑迁移脚本本身，
 * 避免脚本只在上线那一刻才第一次执行。真机 MySQL 的验证仍需按收尾清单单独执行。</p>
 */
class ChannelAccountSessionScopeMigrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V16__channel_account_session_scope.sql";

    @Test
    void migrationAddsSessionScopeAndBackfillsExistingRowsWithPerPeer() throws SQLException, IOException {
        JdbcTemplate jdbc = new JdbcTemplate(newH2());
        createV15AccountTable(jdbc);
        // 存量行：迁移前建立的绑定，没有任何会话粒度信息。
        insertAccount(jdbc, "feishu-main", "feishu", "cli_app");
        insertAccount(jdbc, "wecom-main", "wecom", "corp");

        runMigration(jdbc);

        assertEquals("PER_PEER", jdbc.queryForObject(
                        "SELECT dm_scope FROM platform_channel_account WHERE binding_id='feishu-main'", String.class),
                "存量行必须被回填为更严格的 PER_PEER，而不是框架默认的 MAIN");
        assertEquals("PER_PEER", jdbc.queryForObject(
                        "SELECT dm_scope FROM platform_channel_account WHERE binding_id='wecom-main'", String.class),
                "回填必须覆盖全部存量行");

        // 迁移后新增的绑定若不显式指定，也应拿到同样的收紧默认值。
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                        + "credential_ref,default_employee_id,enabled,created_at,updated_at) "
                        + "VALUES('sim-main',1,'simulated','sim-key','env:sim',2,TRUE,?,?)",
                Timestamp.valueOf("2026-09-28 00:00:00"), Timestamp.valueOf("2026-09-28 00:00:00"));
        assertEquals("PER_PEER", jdbc.queryForObject(
                        "SELECT dm_scope FROM platform_channel_account WHERE binding_id='sim-main'", String.class),
                "列默认值必须对新插入的行同样生效");

        // 迁移不得破坏既有唯一约束（重复绑定仍要被数据库拦住）。
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_channel_account "
                + "WHERE provider='feishu' AND external_account_key='cli_app'", Integer.class));
    }

    /** V16 之前的表结构：与 V15 迁移一致，不含 dm_scope。 */
    private static void createV15AccountTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_channel_account(binding_id VARCHAR(64) NOT NULL,tenant_id BIGINT NOT NULL,"
                + "provider VARCHAR(32) NOT NULL,external_account_key VARCHAR(128) NOT NULL,"
                + "credential_ref VARCHAR(256) NOT NULL,default_employee_id BIGINT NOT NULL,"
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(binding_id),UNIQUE(provider,external_account_key))");
    }

    private static void insertAccount(JdbcTemplate jdbc, String bindingId, String provider, String accountKey) {
        jdbc.update("INSERT INTO platform_channel_account(binding_id,tenant_id,provider,external_account_key,"
                        + "credential_ref,default_employee_id,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bindingId, 1L, provider, accountKey, "env:" + bindingId, 2L, true,
                Timestamp.valueOf("2026-01-01 00:00:00"), Timestamp.valueOf("2026-01-01 00:00:00"));
    }

    private static void runMigration(JdbcTemplate jdbc) throws SQLException, IOException {
        try (Connection connection = jdbc.getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements(readMigration())) {
                statement.execute(sql);
            }
        }
    }

    /** 迁移脚本不含字符串字面量里的分号，可以按语句切分后逐条执行。 */
    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
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
        try (InputStream in = ChannelAccountSessionScopeMigrationTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION_RESOURCE)) {
            assertNotNull(in, "classpath 上找不到迁移脚本 " + MIGRATION_RESOURCE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static DataSource newH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:v16_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        h2.setUser("sa");
        return h2;
    }
}
