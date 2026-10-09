package com.haizhuo.brain.infrastructure.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.CapabilityAssetDraftConflictException;
import com.haizhuo.brain.platform.employee.CapabilityAssetFileInput;
import com.haizhuo.brain.platform.employee.CapabilityAssetManagementService;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcCapabilityAssetRepositoryTest {
    private JdbcTemplate jdbc;
    private JdbcCapabilityAssetRepository repository;
    private CapabilityAssetManagementService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:asset_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        repository = new JdbcCapabilityAssetRepository(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper());
        service = new CapabilityAssetManagementService(repository, Clock.systemUTC());
    }

    @Test
    void publishesImmutableRevisionAndReplaysTheSameRequest() {
        var firstDraft = save(0, "# Customer analysis\nfirst revision");
        var first = service.publish(firstDraft.capabilityCode(), firstDraft.draftRevision(), "asset-publish-1", 7, "审核通过");
        var replay = service.publish(firstDraft.capabilityCode(), firstDraft.draftRevision(), "asset-publish-1", 7, "重复请求");

        assertEquals(first.capabilityRevisionId(), replay.capabilityRevisionId());
        assertEquals("1", first.revision());
        assertEquals("---\nname: customer-analysis\ndescription: Analyze customer needs\n---\n# Customer analysis\nfirst revision",
                service.getRevision(first.capabilityCode(), first.capabilityRevisionId())
                .files().stream().filter(file -> file.relativePath().equals("SKILL.md")).findFirst().orElseThrow().content());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM capability_definition WHERE capability_code=?",
                String.class, first.capabilityCode()));

        assertThrows(CapabilityAssetDraftConflictException.class,
                () -> save(0, "# stale concurrent update"));
        var secondDraft = save(1, "# Customer analysis\nsecond revision");
        var second = service.publish(secondDraft.capabilityCode(), secondDraft.draftRevision(), "asset-publish-2", 7, "更新资料");
        assertEquals("2", second.revision());
        assertNotEquals(first.assetHash(), second.assetHash());
        assertEquals("---\nname: customer-analysis\ndescription: Analyze customer needs\n---\n# Customer analysis\nfirst revision",
                service.getRevision(first.capabilityCode(), first.capabilityRevisionId())
                .files().stream().filter(file -> file.relativePath().equals("SKILL.md")).findFirst().orElseThrow().content());
        var lateReplay = service.publish(firstDraft.capabilityCode(), secondDraft.draftRevision(), "asset-publish-1", 7, "重试旧请求");
        assertEquals(first.capabilityRevisionId(), lateReplay.capabilityRevisionId());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM capability_asset_revision", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM capability_asset_audit", Integer.class));
        assertTrue(service.listAssets().get(0).publishedRevisionId() == second.capabilityRevisionId());
    }

    @Test
    void concurrentPublishRetriesWithSameRequestIdCreateOneRevision() throws Exception {
        var draft = save(0, "# Customer analysis\nconcurrent publish");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var publish = (java.util.concurrent.Callable<com.haizhuo.brain.platform.employee.CapabilityAssetRevision>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Publish test did not start");
                return service.publish(draft.capabilityCode(), draft.draftRevision(), "concurrent-request", 7, "并发重试");
            };
            var first = pool.submit(publish);
            var second = pool.submit(publish);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var firstRevision = first.get(10, TimeUnit.SECONDS);
            var secondRevision = second.get(10, TimeUnit.SECONDS);
            assertEquals(firstRevision.capabilityRevisionId(), secondRevision.capabilityRevisionId());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM capability_asset_revision", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void revisionHistoryUsesStableAssetBoundCursorAndSummarizesFileBytes() {
        var firstDraft = save(0, "# Customer analysis\nfirst revision");
        var first = service.publish(firstDraft.capabilityCode(), firstDraft.draftRevision(), "history-publish-1", 7, "首版");
        var secondDraft = save(1, "# Customer analysis\nsecond revision");
        var second = service.publish(secondDraft.capabilityCode(), secondDraft.draftRevision(), "history-publish-2", 7, "第二版");

        var page1 = service.listRevisions(first.capabilityCode(), null, 1);
        assertEquals(1, page1.items().size());
        assertEquals(second.capabilityRevisionId(), page1.items().get(0).capabilityRevisionId());
        assertEquals(2, page1.items().get(0).fileCount());
        assertEquals(second.files().stream().mapToLong(file -> file.byteSize()).sum(), page1.items().get(0).totalBytes());
        assertTrue(page1.hasMore());

        var thirdDraft = save(2, "# Customer analysis\nthird revision inserted after page one");
        service.publish(thirdDraft.capabilityCode(), thirdDraft.draftRevision(), "history-publish-3", 7, "并发新发布");
        var page2 = service.listRevisions(first.capabilityCode(), page1.nextCursor(), 1);
        assertEquals(1, page2.items().size());
        assertEquals(first.capabilityRevisionId(), page2.items().get(0).capabilityRevisionId());
        assertEquals(false, page2.hasMore());
        assertEquals(null, page2.nextCursor());
        assertThrows(IllegalArgumentException.class,
                () -> repository.listRevisionSummaries("skill.other-asset", page1.nextCursor(), 1),
                "修订游标不能换资产使用");
        assertFalse(service.listRevisions(first.capabilityCode(), null, 1).items().stream()
                .anyMatch(item -> item.capabilityRevisionId() == first.capabilityRevisionId()));
        assertThrows(com.haizhuo.brain.platform.employee.CapabilityAssetNotFoundException.class,
                () -> service.listRevisions("skill.missing", null, 20));
    }

    private com.haizhuo.brain.platform.employee.CapabilityAssetDraft save(int expectedRevision, String skillBody) {
        return service.saveDraft("skill.customer-analysis", expectedRevision,
                CapabilityBinding.CapabilityType.SKILL, "Customer analysis", "Approved analysis workflow",
                List.of(new CapabilityAssetFileInput("SKILL.md",
                                "---\nname: customer-analysis\ndescription: Analyze customer needs\n---\n" + skillBody),
                        new CapabilityAssetFileInput("references/fields.md", "Approved reference fields.")),
                7, "资产管理测试");
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE capability_definition(capability_code VARCHAR(128) PRIMARY KEY,capability_type VARCHAR(24) NOT NULL,"
                + "status VARCHAR(24) NOT NULL,updated_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,status_reason VARCHAR(500))");
        jdbc.execute("CREATE TABLE capability_revision(capability_revision_id BIGINT AUTO_INCREMENT UNIQUE,capability_code VARCHAR(128) NOT NULL,"
                + "revision VARCHAR(32) NOT NULL,display_name VARCHAR(128) NOT NULL,description VARCHAR(1000) NOT NULL,tool_name VARCHAR(128),"
                + "implementation_key VARCHAR(64),business_action VARCHAR(128),input_schema_json CLOB NOT NULL,content_hash CHAR(64) NOT NULL,"
                + "requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(capability_code,revision),"
                + "CONSTRAINT fk_asset_test_capability FOREIGN KEY(capability_code) REFERENCES capability_definition(capability_code),"
                + "CONSTRAINT uk_asset_test_tool_name UNIQUE(tool_name))");
        jdbc.execute("CREATE TABLE capability_asset_draft(capability_code VARCHAR(128) PRIMARY KEY,capability_type VARCHAR(24) NOT NULL,"
                + "draft_revision INT NOT NULL,display_name VARCHAR(128) NOT NULL,description VARCHAR(1000) NOT NULL,files_json CLOB NOT NULL,"
                + "manifest_json CLOB NOT NULL,asset_hash CHAR(64) NOT NULL,updated_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "reason VARCHAR(500) NOT NULL,FOREIGN KEY(capability_code) REFERENCES capability_definition(capability_code))");
        jdbc.execute("CREATE TABLE capability_asset_revision(capability_revision_id BIGINT PRIMARY KEY,capability_code VARCHAR(128) NOT NULL,"
                + "revision VARCHAR(32) NOT NULL,capability_type VARCHAR(24) NOT NULL,manifest_json CLOB NOT NULL,asset_hash CHAR(64) NOT NULL,"
                + "draft_revision INT NOT NULL,publish_request_id VARCHAR(128) NOT NULL,reviewed_by BIGINT NOT NULL,reviewed_at TIMESTAMP NOT NULL,"
                + "reason VARCHAR(500) NOT NULL,UNIQUE(capability_code,revision),UNIQUE(capability_code,publish_request_id),"
                + "FOREIGN KEY(capability_revision_id) REFERENCES capability_revision(capability_revision_id),"
                + "FOREIGN KEY(capability_code) REFERENCES capability_definition(capability_code))");
        jdbc.execute("CREATE TABLE capability_asset_file(capability_revision_id BIGINT NOT NULL,relative_path VARCHAR(512) NOT NULL,"
                + "media_type VARCHAR(128) NOT NULL,content CLOB NOT NULL,sha256 CHAR(64) NOT NULL,byte_size INT NOT NULL,"
                + "PRIMARY KEY(capability_revision_id,relative_path),FOREIGN KEY(capability_revision_id) "
                + "REFERENCES capability_asset_revision(capability_revision_id))");
        jdbc.execute("CREATE TABLE capability_asset_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,capability_code VARCHAR(128) NOT NULL,"
                + "action VARCHAR(32) NOT NULL,actor_user_id BIGINT NOT NULL,request_id VARCHAR(128),reason VARCHAR(500) NOT NULL,"
                + "previous_hash CHAR(64),new_hash CHAR(64) NOT NULL,occurred_at TIMESTAMP NOT NULL,"
                + "FOREIGN KEY(capability_code) REFERENCES capability_definition(capability_code))");
    }
}
