package com.haizhuo.brain.infrastructure.employee;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementAudit;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.platform.employee.ToolInvocationAudit;
import com.haizhuo.brain.platform.employee.UserCapabilityGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 当前仓库契约（Harness 切换后）：草稿/发布/能力目录/授权/工具调用审计。
 * 运行期有效能力集快照已随 EffectiveCapabilitySetResolver 一并删除（规格 §7.6 由
 * platform_agent_run_spec 承担），本测试不再覆盖已移除的表与 API。
 */
class JdbcAgentDefinitionRepositoryTest {
    private JdbcTemplate jdbc;
    private JdbcAgentDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:agent_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        repository = new JdbcAgentDefinitionRepository(jdbc, new DataSourceTransactionManager(dataSource), new ObjectMapper());
    }

    @Test
    void savesDraftWithOptimisticRevisionAndRedactedAudit() {
        var saved = repository.saveDraft(1, 1, "只执行测试读取。", "openai-compatible", "test-model",
                List.of(new CapabilitySelection("sample.read", "1")), audit("DRAFT_SAVED", "DIGITAL_EMPLOYEE", "1", null));
        assertEquals(2, saved.draftRevision());
        assertEquals(List.of("sample.read"), saved.capabilities().stream().map(CapabilitySelection::capabilityCode).toList());

        var persisted = repository.findDraft(1).orElseThrow();
        assertEquals(2, persisted.draftRevision());
        assertEquals("只执行测试读取。", persisted.instructions());
        assertEquals(List.of("sample.read"), persisted.capabilities().stream().map(CapabilitySelection::capabilityCode).toList());

        assertThrows(IllegalStateException.class, () -> repository.saveDraft(1, 1, "另一版指令", "openai", "m",
                List.of(), audit("DRAFT_SAVED", "DIGITAL_EMPLOYEE", "1", null)), "过期草稿修订必须被拒绝");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_management_audit", Integer.class),
                "被拒绝的保存不应写审计");
        assertEquals(42L, jdbc.queryForObject("SELECT actor_user_id FROM agent_definition_management_audit WHERE event_type='DRAFT_SAVED'", Long.class));
        String draftAudit = jdbc.queryForObject("SELECT new_summary FROM agent_definition_management_audit WHERE event_type='DRAFT_SAVED'", String.class);
        assertFalse(draftAudit.contains("只执行测试读取"), "审计不应保存完整 Agent 指令");
    }

    @Test
    void publishCreatesImmutableVersionAndReplaysByRequestId() {
        var original = repository.findPublished(new TenantId(1), 1).orElseThrow();
        repository.saveDraft(1, 1, "只执行测试读取。", "openai-compatible", "test-model",
                List.of(new CapabilitySelection("sample.read", "1")), audit("DRAFT_SAVED", "DIGITAL_EMPLOYEE", "1", null));

        var published = repository.publish(1, 2, audit("DEFINITION_PUBLISHED", "DIGITAL_EMPLOYEE", "1", "publish-1"));
        assertEquals(2, published.definition().version());
        assertEquals("test-model", published.definition().modelName());
        assertEquals(List.of("sample.read"), published.capabilities().stream().map(b -> b.referenceId()).toList());
        assertEquals("旧指令", original.definition().instructions(), "历史发布版本应保持不可变");

        var replayed = repository.publish(1, 2, audit("DEFINITION_PUBLISHED", "DIGITAL_EMPLOYEE", "1", "publish-1"));
        assertEquals(published.definition().id(), replayed.definition().id(), "发布请求重放应返回同一不可变版本");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_version", Integer.class),
                "重放不应产生新版本");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_management_audit WHERE event_type='DEFINITION_PUBLISHED'", Integer.class),
                "重放走幂等返回，不重复写审计");

        assertThrows(IllegalStateException.class, () -> repository.publish(1, 1,
                audit("DEFINITION_PUBLISHED", "DIGITAL_EMPLOYEE", "1", "publish-2")), "过期草稿修订必须被拒绝");
        assertThrows(IllegalArgumentException.class, () -> repository.publish(1, 2,
                audit("DEFINITION_PUBLISHED", "DIGITAL_EMPLOYEE", "1", " ")), "非法 requestId 必须被拒绝");

        var current = repository.findPublished(new TenantId(1), 1).orElseThrow();
        assertEquals(published.definition().id(), current.definition().id(), "发布后当前指针应指向新版本");
        assertEquals(1, repository.listEnabled(new TenantId(1)).size());
    }

    @Test
    void capabilityCatalogExposesRevisionIdentitySchemaAndConfirmationFlag() {
        List<CapabilityCatalogEntry> catalog = repository.listCapabilities();
        assertEquals(2, catalog.size());

        CapabilityCatalogEntry read = repository.findCapability("sample.read", "1").orElseThrow();
        assertTrue(read.capabilityRevisionId() > 0, "目录条目必须携带稳定数值身份（spec §6.2）");
        assertFalse(read.requiresConfirmation());
        assertEquals("sample-v1", read.implementationKey());
        assertEquals("object", read.inputSchema().get("type"), "inputSchema 必须被解析为 Map");
        assertTrue(read.enabled());

        CapabilityCatalogEntry write = repository.findCapability("sample.write", "1").orElseThrow();
        assertTrue(write.requiresConfirmation(), "审批标记必须从列映射到目录条目（spec §39）");
        assertEquals(write.capabilityRevisionId(),
                repository.findCapabilityByRevisionId(write.capabilityRevisionId()).orElseThrow().capabilityRevisionId());
        assertTrue(repository.findCapability("sample.missing", "1").isEmpty());
        assertTrue(repository.findCapabilityByRevisionId(999999).isEmpty());
    }

    @Test
    void capabilityStatusToggleAndUserGrantsAreAudited() {
        assertTrue(repository.isCapabilityEnabled("sample.write", "1"));
        repository.setCapabilityEnabled("sample.write", false, audit("CAPABILITY_STATUS_CHANGED", "CAPABILITY", "sample.write", null));
        assertFalse(repository.isCapabilityEnabled("sample.write", "1"));
        assertTrue(repository.isCapabilityEnabled("sample.read", "1"), "其他能力不受影响");

        assertTrue(repository.hasUserCapabilityGrant(42, "sample.read"));
        repository.setUserCapabilityGrant(42, "sample.read", false, audit("USER_CAPABILITY_GRANT_CHANGED", "PLATFORM_USER", "42", null));
        assertFalse(repository.hasUserCapabilityGrant(42, "sample.read"), "撤权即时生效");
        repository.setUserCapabilityGrant(42, "sample.read", true, audit("USER_CAPABILITY_GRANT_CHANGED", "PLATFORM_USER", "42", null));
        assertTrue(repository.hasUserCapabilityGrant(42, "sample.read"), "授权 upsert 后应恢复");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_user_capability_grant WHERE user_id=42 AND capability_code='sample.read'", Integer.class),
                "upsert 不应产生重复行");

        repository.setUserCapabilityGrant(43, "sample.read", true, audit("USER_CAPABILITY_GRANT_CHANGED", "PLATFORM_USER", "43", null));
        assertTrue(repository.hasUserCapabilityGrant(43, "sample.read"), "新授权插入路径");

        List<UserCapabilityGrant> user42 = repository.listUserCapabilityGrants(42);
        assertEquals(List.of(new UserCapabilityGrant(42, "sample.read", true),
                        new UserCapabilityGrant(42, "sample.write", true)), user42,
                "查询只返回目标用户的当前授权记录");
        repository.setUserCapabilityGrant(42, "sample.read", false,
                audit("USER_CAPABILITY_GRANT_CHANGED", "PLATFORM_USER", "42", null));
        assertEquals(List.of(new UserCapabilityGrant(42, "sample.read", false),
                        new UserCapabilityGrant(42, "sample.write", true)), repository.listUserCapabilityGrants(42),
                "撤权状态必须可读回");
        assertEquals(List.of(new UserCapabilityGrant(43, "sample.read", true)), repository.listUserCapabilityGrants(43),
                "不同用户授权必须隔离");
        assertEquals(List.of(), repository.listUserCapabilityGrants(99), "无授权用户返回空列表");

        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_management_audit", Integer.class),
                "能力状态 1 条 + 授权变更 4 条");
        assertThrows(IllegalArgumentException.class, () -> repository.setCapabilityEnabled("sample.missing", false,
                audit("CAPABILITY_STATUS_CHANGED", "CAPABILITY", "sample.missing", null)), "不存在的能力必须被拒绝");
    }

    @Test
    void recordsToolInvocationAuditRow() {
        jdbc.update("INSERT INTO agent_run(run_id) VALUES (?)", "run-jdbc-1");
        repository.recordToolInvocation(new ToolInvocationAudit(UUID.randomUUID().toString(), "run-jdbc-1", "sample.read", "1",
                42, "sample.read", "a".repeat(64), "ALLOW", "SUCCEEDED", "读取完成", Instant.now()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tool_invocation_audit WHERE run_id=?", Integer.class, "run-jdbc-1"));
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE digital_employee(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,employee_code VARCHAR(64),display_name VARCHAR(128),enabled BOOLEAN,current_published_version_id BIGINT,row_version BIGINT)");
        jdbc.execute("CREATE TABLE capability_definition(capability_code VARCHAR(128) PRIMARY KEY,capability_type VARCHAR(24),status VARCHAR(24),updated_by BIGINT,updated_at TIMESTAMP,status_reason VARCHAR(500))");
        jdbc.execute("CREATE TABLE capability_revision(capability_revision_id BIGINT AUTO_INCREMENT UNIQUE,capability_code VARCHAR(128),revision VARCHAR(32),display_name VARCHAR(128),description VARCHAR(1000),tool_name VARCHAR(128),implementation_key VARCHAR(64),business_action VARCHAR(128),input_schema_json TEXT,requires_confirmation BOOLEAN DEFAULT FALSE,content_hash CHAR(64),PRIMARY KEY(capability_code,revision),UNIQUE(tool_name),FOREIGN KEY(capability_code) REFERENCES capability_definition(capability_code))");
        jdbc.execute("CREATE TABLE agent_definition_draft(employee_id BIGINT PRIMARY KEY,draft_revision INT,instructions TEXT,model_provider VARCHAR(32),model_name VARCHAR(128),updated_by BIGINT,updated_at TIMESTAMP,FOREIGN KEY(employee_id) REFERENCES digital_employee(id))");
        jdbc.execute("CREATE TABLE agent_definition_draft_capability(employee_id BIGINT,capability_code VARCHAR(128),capability_revision VARCHAR(32),position_no INT,PRIMARY KEY(employee_id,capability_code),FOREIGN KEY(employee_id) REFERENCES agent_definition_draft(employee_id),FOREIGN KEY(capability_code,capability_revision) REFERENCES capability_revision(capability_code,revision))");
        jdbc.execute("CREATE TABLE agent_definition_version(id BIGINT AUTO_INCREMENT PRIMARY KEY,employee_id BIGINT,version_no INT,instructions TEXT,model_provider VARCHAR(32),model_name VARCHAR(128),content_hash CHAR(64),publish_request_id VARCHAR(128),published_by BIGINT,published_at TIMESTAMP,UNIQUE(employee_id,version_no),UNIQUE(employee_id,publish_request_id),FOREIGN KEY(employee_id) REFERENCES digital_employee(id))");
        jdbc.execute("CREATE TABLE agent_definition_version_capability(definition_version_id BIGINT,capability_code VARCHAR(128),capability_revision VARCHAR(32),position_no INT,PRIMARY KEY(definition_version_id,capability_code),FOREIGN KEY(definition_version_id) REFERENCES agent_definition_version(id),FOREIGN KEY(capability_code,capability_revision) REFERENCES capability_revision(capability_code,revision))");
        jdbc.execute("CREATE TABLE agent_user_capability_grant(user_id BIGINT,capability_code VARCHAR(128),enabled BOOLEAN,updated_by BIGINT,updated_at TIMESTAMP,PRIMARY KEY(user_id,capability_code),FOREIGN KEY(capability_code) REFERENCES capability_definition(capability_code))");
        jdbc.execute("CREATE TABLE agent_definition_management_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_user_id BIGINT,event_type VARCHAR(64),target_type VARCHAR(64),target_id VARCHAR(128),request_id VARCHAR(128),reason VARCHAR(500),previous_summary TEXT,new_summary TEXT,occurred_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE agent_run(run_id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE tool_invocation_audit(invocation_id VARCHAR(36) PRIMARY KEY,run_id VARCHAR(36),capability_code VARCHAR(128),capability_revision VARCHAR(32),user_id BIGINT,business_action VARCHAR(128),arguments_hash CHAR(64),decision VARCHAR(24),result_status VARCHAR(24),result_summary VARCHAR(1000),created_at TIMESTAMP,FOREIGN KEY(run_id) REFERENCES agent_run(run_id),FOREIGN KEY(capability_code,capability_revision) REFERENCES capability_revision(capability_code,revision))");
        jdbc.update("INSERT INTO digital_employee VALUES(1,1,'sample-employee','测试员工',TRUE,1,0)");
        jdbc.update("INSERT INTO capability_definition VALUES('sample.read','TOOL','ACTIVE',42,CURRENT_TIMESTAMP,NULL),('sample.write','TOOL','ACTIVE',42,CURRENT_TIMESTAMP,NULL)");
        String schema = "{\"type\":\"object\",\"properties\":{\"startAt\":{\"type\":\"string\"},\"endAt\":{\"type\":\"string\"},\"attendees\":{\"type\":\"integer\"}},\"required\":[\"startAt\",\"endAt\",\"attendees\"],\"additionalProperties\":false}";
        for (String code : List.of("sample.read", "sample.write")) {
            jdbc.update("INSERT INTO capability_revision(capability_code,revision,display_name,description,tool_name,implementation_key,business_action,input_schema_json,requires_confirmation,content_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    code, "1", code, "测试能力", code.replace('.', '_'), "sample-v1", code, schema, code.endsWith("write"), "f".repeat(64));
        }
        jdbc.update("INSERT INTO agent_definition_draft VALUES(1,1,'旧指令','openai','test-model',42,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO agent_definition_draft_capability VALUES(1,'sample.read','1',1),(1,'sample.write','1',2)");
        jdbc.update("INSERT INTO agent_definition_version VALUES(1,1,1,'旧指令','openai','test-model',?,'bootstrap',42,CURRENT_TIMESTAMP)", "f".repeat(64));
        jdbc.update("INSERT INTO agent_definition_version_capability VALUES(1,'sample.read','1',1),(1,'sample.write','1',2)");
        jdbc.update("UPDATE digital_employee SET current_published_version_id=1 WHERE id=1");
        jdbc.update("INSERT INTO agent_user_capability_grant VALUES(42,'sample.read',TRUE,42,CURRENT_TIMESTAMP),(42,'sample.write',TRUE,42,CURRENT_TIMESTAMP)");
    }

    private static AgentDefinitionManagementAudit audit(String eventType, String targetType, String targetId, String requestId) {
        return AgentDefinitionManagementAudit.pending(42, eventType, targetType, targetId, requestId, "测试管理变更");
    }
}
