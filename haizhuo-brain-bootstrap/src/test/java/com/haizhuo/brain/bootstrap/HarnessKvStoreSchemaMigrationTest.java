package com.haizhuo.brain.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/** Contract test between Flyway V24 and AgentScope 2.0.3 JdbcStore. */
class HarnessKvStoreSchemaMigrationTest {

    private static final String RESOURCE = "db/migration/V24__agentscope_harness_kv_store.sql";

    @Test
    void migrationMatchesPinnedMysqlDialectColumnsAndIndex() throws IOException {
        String migration = readMigration().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        MysqlDialect dialect = new MysqlDialect();
        String nativeDdl = dialect.storeCreateTableDdls().get(0).replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
        assertTrue(migration.contains("CREATE TABLE " + dialect.storeTableName().toUpperCase(Locale.ROOT)));
        assertTrue(nativeDdl.contains(dialect.storeTableName().toUpperCase(Locale.ROOT)));
        assertTrue(nativeDdl.contains("PRIMARY KEY (NAMESPACE_PATH, ITEM_KEY)"));
        assertTrue(migration.contains("PRIMARY KEY (NAMESPACE_PATH, ITEM_KEY)"));
        assertTrue(nativeDdl.contains("INDEX IDX_NAMESPACE (NAMESPACE_PATH)"));
        assertTrue(migration.contains("INDEX IDX_NAMESPACE (NAMESPACE_PATH)"));
        for (String name : List.of("NAMESPACE_PATH", "ITEM_KEY", "VALUE_JSON", "VERSION", "UPDATED_AT")) {
            assertTrue(migration.contains(name + " "), "V24 must declare dialect column " + name);
        }
    }

    @Test
    void migratedSchemaSupportsNativeJdbcStoreRoundTripAndCas() throws Exception {
        DataSource dataSource = newDataSource();
        String sql = readMigration().replaceAll("(?i)\\)\\s*ENGINE=InnoDB.*;", ");");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }

        JdbcStore store = JdbcStore.builder(dataSource).dialect(new MysqlDialect()).initializeSchema(false).build();
        List<String> namespace = List.of("agents", "employee-version-3", "sessions", "session-unique", "plans");
        store.put(namespace, "plan.md", Map.of("content", "durable plan"));
        StoreItem stored = store.get(namespace, "plan.md");
        assertNotNull(stored);
        assertEquals("durable plan", stored.value().get("content"));
        assertTrue(store.putIfVersion(namespace, "plan.md", Map.of("content", "updated plan"), stored.version()));
        assertEquals("updated plan", store.get(namespace, "plan.md").value().get("content"));
    }

    private static String readMigration() throws IOException {
        try (InputStream in = HarnessKvStoreSchemaMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("Missing " + RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static DataSource newDataSource() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:harness_store_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        h2.setUser("sa");
        return h2;
    }
}
