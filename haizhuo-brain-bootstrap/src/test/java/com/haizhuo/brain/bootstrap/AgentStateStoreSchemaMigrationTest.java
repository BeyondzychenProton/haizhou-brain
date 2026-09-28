package com.haizhuo.brain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * 守住 V13 迁移与 AgentScope 方言之间的契约（启动故障复盘）：
 * {@code JdbcAgentStateStore} 在构造器里校验 {@code agentscope_sessions} 是否存在，缺失即抛
 * {@code IllegalStateException}，进而让整个 run-worker bean 图装配失败。该表此前没有迁移脚本，
 * 只有生产 MySQL 上手工建表才不会崩；V13 把这份 DDL 收编进 Flyway。
 *
 * <p>两个用例分工：
 * <ul>
 *   <li>静态比对：迁移脚本的列名集合与主键必须覆盖 {@code MysqlDialect#sessionStateCreateTableDdls()}
 *       的声明——升级 AgentScope 后若方言改了 schema，这条会先红，而不是等到上线启动才炸。</li>
 *   <li>动态验证：把迁移脚本真跑一遍（H2 MODE=MySQL），再让 Store 在其上读写，证明列类型/主键
 *       满足 Store 的 upsert 与查询。</li>
 * </ul>
 *
 * <p>动态用例必须用 3 参构造 {@code createIfNotExist=true}：MysqlDialect 的存在性校验是
 * {@code INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()}，H2 的 TABLE_SCHEMA 存
 * {@code public} 而 {@code DATABASE()} 返回库名，恒不相等；真实 MySQL 两侧一致，生产用 2 参
 * 构造即可（见 AgentRuntimeConfiguration）。
 */
class AgentStateStoreSchemaMigrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V13__agentscope_session_state.sql";

    @Test
    void migrationDeclaresEveryColumnTheDialectReads() throws IOException {
        String sql = readMigration();
        String normalized = sql.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);

        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS AGENTSCOPE_SESSIONS"),
                "V13 必须创建 AgentScope 会话状态表：\n" + sql);
        assertTrue(normalized.contains("PRIMARY KEY (SESSION_ID, STATE_KEY, ITEM_INDEX)"),
                "方言按 (session_id, state_key, item_index) 三元组 upsert/定位，主键必须一致：\n" + sql);

        List<String> missing = new ArrayList<>();
        for (String column : dialectColumns()) {
            if (!normalized.contains(column.toUpperCase(Locale.ROOT))) {
                missing.add(column);
            }
        }
        assertTrue(missing.isEmpty(),
                "V13 缺少 AgentScope 方言要读写的列 " + missing + "（方言 DDL="
                        + String.join(" ", new MysqlDialect().sessionStateCreateTableDdls()) + "）");
    }

    @Test
    void migratedSchemaSupportsTheStoreRoundTrip() throws SQLException, IOException {
        DataSource dataSource = newH2();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(readMigration());
        }

        // 表已由迁移建好，createIfNotExist 在此只是绕过 H2 的大小写/schema 差异，不会再建一次。
        JdbcAgentStateStore store = new JdbcAgentStateStore(dataSource, new MysqlDialect(), true);

        String userId = "user-13";
        String sessionId = "hs-13";
        AgentState state = AgentState.builder().sessionId(sessionId).userId(userId)
                .summary("V13 migration guard").build();

        store.save(userId, sessionId, "agent_state", state);
        assertTrue(store.exists(userId, sessionId), "迁移后的表必须能承载会话状态");
        assertTrue(store.listSessionIds(userId).contains(sessionId));

        AgentState reloaded = store.get(userId, sessionId, "agent_state", AgentState.class).orElse(null);
        assertNotNull(reloaded, "状态必须可读回");
        assertEquals(sessionId, reloaded.getSessionId());
        assertEquals("V13 migration guard", reloaded.getSummary());

        store.delete(userId, sessionId);
        assertFalse(store.exists(userId, sessionId), "delete 必须按 (userId, sessionId) 清空该会话");
    }

    /** 从方言 DDL 里抽出列定义行首的名字，跳过 PRIMARY/INDEX 等约束行。 */
    private static List<String> dialectColumns() {
        List<String> columns = new ArrayList<>();
        for (String ddl : new MysqlDialect().sessionStateCreateTableDdls()) {
            String body = ddl.substring(ddl.indexOf('(') + 1, ddl.lastIndexOf(')'));
            for (String line : body.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String head = trimmed.split("\\s+")[0];
                if (head.startsWith("PRIMARY") || head.startsWith("INDEX") || head.startsWith("KEY")
                        || head.startsWith("UNIQUE") || head.startsWith("CONSTRAINT") || head.startsWith("FOREIGN")) {
                    continue;
                }
                if (!trimmed.matches("(?i)^[a-z_][a-z0-9_]*\\s+[A-Z].*")) {
                    continue;
                }
                columns.add(head.toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private static String readMigration() throws IOException {
        try (InputStream in = AgentStateStoreSchemaMigrationTest.class.getClassLoader()
                .getResourceAsStream(MIGRATION_RESOURCE)) {
            assertNotNull(in, "classpath 上找不到迁移脚本 " + MIGRATION_RESOURCE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static DataSource newH2() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:v13_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        h2.setUser("sa");
        return h2;
    }
}
