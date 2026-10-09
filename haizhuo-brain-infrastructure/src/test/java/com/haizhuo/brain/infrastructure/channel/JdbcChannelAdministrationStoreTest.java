package com.haizhuo.brain.infrastructure.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.SessionScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcChannelAdministrationStoreTest {
    private JdbcTemplate jdbc;
    private JdbcChannelAdministrationStore store;
    private JdbcChannelAccountDirectory directory;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:channel_account_revision_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE platform_channel_account(binding_id VARCHAR(64) PRIMARY KEY,tenant_id BIGINT NOT NULL,"
                + "provider VARCHAR(32) NOT NULL,external_account_key VARCHAR(128) NOT NULL,credential_ref VARCHAR(256) NOT NULL,"
                + "default_employee_id BIGINT NOT NULL,dm_scope VARCHAR(32) NOT NULL,enabled BOOLEAN NOT NULL,"
                + "revision BIGINT NOT NULL DEFAULT 1,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "UNIQUE(provider,external_account_key))");
        jdbc.execute("CREATE TABLE platform_channel_identity(binding_id VARCHAR(64),external_user_id VARCHAR(128),"
                + "user_id BIGINT,state VARCHAR(16),linked_at TIMESTAMP,updated_at TIMESTAMP,"
                + "PRIMARY KEY(binding_id,external_user_id))");
        var clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
        store = new JdbcChannelAdministrationStore(jdbc, clock);
        directory = new JdbcChannelAccountDirectory(jdbc);
    }

    @Test
    void directoryReadsRevisionAndCasAllowsOnlyOneUpdate() {
        var initial = binding(1, true);
        store.createAccount(initial);

        ChannelAccountBinding loaded = directory.findById("account-1").orElseThrow();
        assertEquals(1L, loaded.revision());
        var revisionTwo = binding(2, false);

        assertTrue(store.updateAccountIfRevision(revisionTwo, 1));
        assertFalse(store.updateAccountIfRevision(binding(3, true), 1), "陈旧 revision 必须无法覆盖当前行");
        assertEquals(2L, directory.findById("account-1").orElseThrow().revision());
        assertFalse(directory.findById("account-1").orElseThrow().enabled());
    }

    private static ChannelAccountBinding binding(long revision, boolean enabled) {
        return new ChannelAccountBinding("account-1", new TenantId(1), "simulated", "account-key", "env:account",
                9, SessionScope.PER_PEER, enabled, revision);
    }
}
