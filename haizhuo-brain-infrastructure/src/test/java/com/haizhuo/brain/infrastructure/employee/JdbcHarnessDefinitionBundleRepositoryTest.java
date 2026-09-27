package com.haizhuo.brain.infrastructure.employee;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.runtime.HarnessDefinitionBundle;
import com.haizhuo.brain.platform.employee.runtime.PublishedToolSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 运行时 bundle 仓库（spec §52.1/§6.1）：内容寻址、不可变、insert-only；tool catalog
 * JSON 往返必须保持 capabilityRevisionId/requiresConfirmation 等冻结字段完整。
 */
class JdbcHarnessDefinitionBundleRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcHarnessDefinitionBundleRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:bundle_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE agent_definition_runtime_bundle(id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "definition_version_id BIGINT NOT NULL,employee_name VARCHAR(128) NOT NULL,"
                + "model_provider VARCHAR(64) NOT NULL,model_name VARCHAR(128) NOT NULL,max_iterations INT NOT NULL,"
                + "instructions TEXT NOT NULL,workspace_manifest_json TEXT NOT NULL,workspace_content_hash CHAR(64) NOT NULL,"
                + "tool_catalog_json TEXT NOT NULL,tool_catalog_hash CHAR(64) NOT NULL,subagent_manifest_json TEXT NULL,"
                + "policy_json TEXT NULL,bundle_hash CHAR(64) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "UNIQUE(definition_version_id),UNIQUE(bundle_hash))");
        repository = new JdbcHarnessDefinitionBundleRepository(jdbc, new ObjectMapper());
    }

    @Test
    void saveAssignsIdentityAndRoundTripsFrozenCatalog() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        PublishedToolSchema search = new PublishedToolSchema(101L, "meeting-room.search", "meeting_room_search",
                "查询会议室", Map.of("type", "object", "properties", Map.of("attendees", Map.of("type", "integer"))),
                true, false, "meeting-room.search");
        PublishedToolSchema reserve = new PublishedToolSchema(102L, "meeting-room.reserve", "meeting_room_reserve",
                "预订会议室", Map.of("type", "object"), false, true, "meeting-room.reserve");
        HarnessDefinitionBundle bundle = new HarnessDefinitionBundle(0L, 7L, "行政助理",
                "你是行政助理，只处理会议室。", "openai-compatible", "test-model", 8,
                "{\"files\":[\"AGENTS.md\"]}", "w".repeat(64), List.of(search, reserve),
                "c".repeat(64), null, "{\"policy\":\"p0\"}", "b".repeat(64), now);

        HarnessDefinitionBundle saved = repository.save(bundle);
        assertTrue(saved.id() > 0, "保存必须分配数据库身份");

        HarnessDefinitionBundle byVersion = repository.findByDefinitionVersionId(7L).orElseThrow();
        assertEquals(saved.id(), byVersion.id());
        assertEquals("行政助理", byVersion.employeeName());
        assertEquals("openai-compatible", byVersion.modelProvider());
        assertEquals(8, byVersion.maxIterations());
        assertEquals("b".repeat(64), byVersion.bundleHash());
        assertEquals("{\"policy\":\"p0\"}", byVersion.policyJson());
        assertNull(byVersion.subAgentManifestJson());

        List<PublishedToolSchema> catalog = byVersion.toolCatalog();
        assertEquals(2, catalog.size());
        PublishedToolSchema restoredReserve = catalog.get(1);
        assertEquals(102L, restoredReserve.capabilityRevisionId());
        assertEquals("meeting_room_reserve", restoredReserve.toolName());
        assertTrue(restoredReserve.requiresConfirmation(), "审批标记必须随目录冻结（§39）");
        assertFalse(restoredReserve.readOnly());
        assertEquals("object", restoredReserve.inputSchema().get("type"));
        assertTrue(catalog.get(0).readOnly());

        HarnessDefinitionBundle byHash = repository.findByBundleHash("b".repeat(64)).orElseThrow();
        assertEquals(saved.id(), byHash.id(), "内容寻址查找（§18 模板键的一部分）");
        assertTrue(repository.findByDefinitionVersionId(999L).isEmpty());
        assertTrue(repository.findByBundleHash("f".repeat(64)).isEmpty());
    }

    @Test
    void bundleIsInsertOnlyAndContentAddressed() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        HarnessDefinitionBundle bundle = new HarnessDefinitionBundle(0L, 7L, "行政助理", "指令",
                "openai", "m", 8, "{}", "w".repeat(64), List.of(), "c".repeat(64), null, null,
                "b".repeat(64), now);
        repository.save(bundle);

        HarnessDefinitionBundle conflicting = new HarnessDefinitionBundle(0L, 8L, "另一个员工", "指令",
                "openai", "m", 8, "{}", "w".repeat(64), List.of(), "c".repeat(64), null, null,
                "b".repeat(64), now);
        assertThrows(Exception.class, () -> repository.save(conflicting),
                "相同 bundleHash 不得重复插入（内容寻址唯一约束）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_runtime_bundle", Integer.class));
    }
}
