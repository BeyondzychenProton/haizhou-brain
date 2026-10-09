package com.haizhuo.brain.infrastructure.employee;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class JdbcEmployeeAdministrationStoreTest {
    private JdbcTemplate jdbc;
    private JdbcEmployeeAdministrationStore store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:employee_admin_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        var tx = new DataSourceTransactionManager(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        var commands = new JdbcEmployeeAdminCommandExecutor(jdbc, tx, mapper);
        store = new JdbcEmployeeAdministrationStore(jdbc, tx, mapper, commands,
                new EmployeeRuntimeProfileSettings(java.util.Set.of(RuntimeProfile.LEGACY_STABLE)));

        Instant createdAt = Instant.parse("2024-01-01T00:00:00Z");
        jdbc.update("INSERT INTO digital_employee(id,tenant_id,employee_code,display_name,enabled,current_published_version_id,row_version,created_at) "
                + "VALUES(7,1,'seed','种子员工',TRUE,70,0,?)", Timestamp.from(createdAt));
        jdbc.update("INSERT INTO agent_definition_draft(employee_id,draft_revision,instructions,model_provider,model_name,configuration_json,updated_by,updated_at) "
                + "VALUES(7,1,'已发布指令','openai','test-model',NULL,42,?)", Timestamp.from(createdAt));
        jdbc.update("INSERT INTO agent_definition_version(id,employee_id,version_no,instructions,model_provider,model_name,content_hash,configuration_json,published_by,published_at) "
                + "VALUES(70,7,1,'已发布指令','openai','test-model',?,NULL,42,?)", "d".repeat(64), Timestamp.from(createdAt));
        jdbc.update("INSERT INTO agent_definition_runtime_bundle(definition_version_id) VALUES(70)");
        jdbc.update("INSERT INTO platform_id_allocator(scope_key,next_id) VALUES('digital_employee',8)");
    }

    @Test
    void createUsesLockedAllocatorAndReceiptReplaysWholeEmployeeAndDraft() {
        String hash = "a".repeat(64);
        EmployeeAdministrationStore.CreatedEmployee created = store.createEmployee(
                "new-employee", "新员工", 42, "create-1", hash, "创建员工");
        EmployeeAdministrationStore.CreatedEmployee replay = store.createEmployee(
                "new-employee", "新员工", 42, "create-1", hash, "创建员工");

        assertEquals(created, replay);
        assertEquals(8, created.employee().employeeId(), "ID 从迁移初始化的 allocator 领取");
        assertFalse(created.employee().enabled());
        assertFalse(created.employee().published());
        assertEquals(1, created.draft().draftRevision());
        assertEquals("LEGACY_STABLE", created.draft().configuration().profile().name());
        assertNotNull(created.employee().createdAt());
        assertEquals(9L, jdbc.queryForObject("SELECT next_id FROM platform_id_allocator WHERE scope_key='digital_employee'", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM digital_employee", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_draft", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition_management_audit WHERE event_type='EMPLOYEE_CREATED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM employee_admin_command_receipt WHERE response_json IS NOT NULL", Integer.class));

        assertThrows(IllegalStateException.class, () -> store.createEmployee(
                "different-employee", "其他员工", 42, "create-1", "b".repeat(64), "重用请求键"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM digital_employee", Integer.class),
                "同 requestId 的冲突请求不得创建第二条员工");
    }

    @Test
    void keysetCursorStaysStableWhenNewerEmployeeAppearsAndBindsFilters() {
        jdbc.update("INSERT INTO digital_employee(id,tenant_id,employee_code,display_name,enabled,current_published_version_id,row_version,created_at) "
                        + "VALUES(6,1,'older','更早员工',FALSE,NULL,0,?)",
                Timestamp.from(Instant.parse("2023-01-01T00:00:00Z")));
        EmployeeAdministrationStore.EmployeePage first = store.listEmployees(null, null, null, null, 1);
        assertEquals(7, first.items().get(0).employeeId());
        assertTrue(first.hasMore());

        jdbc.update("INSERT INTO digital_employee(id,tenant_id,employee_code,display_name,enabled,current_published_version_id,row_version,created_at) "
                        + "VALUES(12,1,'inserted-later','后插入',FALSE,NULL,0,?)",
                Timestamp.from(Instant.parse("2025-01-01T00:00:00Z")));
        EmployeeAdministrationStore.EmployeePage second = store.listEmployees(null, null, null, first.nextCursor(), 1);
        assertEquals(6, second.items().get(0).employeeId(), "新插入项不会被旧页游标重复或移入后续页");
        assertFalse(second.hasMore());

        assertThrows(IllegalArgumentException.class,
                () -> store.listEmployees(null, false, null, first.nextCursor(), 1),
                "游标绑定 enabled/published/query 过滤条件");
    }

    @Test
    void metadataAndStatusWritesUseRowVersionAndRequirePublishedVersionForEnablement() {
        store.createEmployee("unpublished", "未发布员工", 42, "create-before-enable", "8".repeat(64), "创建草稿");
        var updated = store.updateEmployee(7, 0, "新显示名", 42, "rename-1", "c".repeat(64), "改名");
        assertEquals("新显示名", updated.displayName());
        assertEquals(1, updated.rowVersion());
        assertEquals(updated, store.updateEmployee(7, 0, "新显示名", 42, "rename-1", "c".repeat(64), "改名"));

        assertThrows(IllegalStateException.class,
                () -> store.updateEmployee(7, 0, "过期写入", 42, "rename-2", "e".repeat(64), "并发冲突"));
        var disabled = store.setEmployeeEnabled(7, 1, false, 42, "disable-1", "f".repeat(64), "停用");
        assertFalse(disabled.enabled());
        assertEquals(2, disabled.rowVersion());

        assertThrows(IllegalStateException.class,
                () -> store.setEmployeeEnabled(8, 0, true, 42, "enable-unpublished", "9".repeat(64), "启用未发布"),
                "存储层仍拒绝启用未发布员工");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM employee_admin_command_receipt WHERE request_id='enable-unpublished'", Integer.class),
                "失败命令回滚幂等回执");
    }

    @Test
    void immutableVersionDetailsReturnExactVersionAndSafeCapabilityMemberProjection() {
        var page = store.listVersions(7, null, 20);
        assertEquals(1, page.items().size());
        assertEquals(70, page.items().get(0).versionId());
        assertTrue(page.items().get(0).current());

        var detail = store.findVersion(7, 70).orElseThrow();
        assertEquals("已发布指令", detail.instructions());
        assertEquals("test-model", detail.modelName());
        assertTrue(detail.runtimeBundleAvailable());
        assertTrue(detail.capabilities().isEmpty());
        assertTrue(detail.members().isEmpty());
        assertTrue(store.findVersion(7, 71).isEmpty(), "跨员工或不存在的版本不会泄露详情");
    }

    @Test
    void enablementRechecksCurrentProfileAndCapabilityRowsInsideTheWriteTransaction() throws Exception {
        String singleConfiguration = mapper.writeValueAsString(
                com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration.singleSkilled(6));
        jdbc.update("UPDATE agent_definition_version SET configuration_json=? WHERE id=70", singleConfiguration);
        assertThrows(IllegalStateException.class,
                () -> store.setEmployeeEnabled(7, 0, true, 42, "enable-closed-profile", "1".repeat(64), "启用"),
                "服务端禁用的 profile 必须在持锁事务内再次拦截");

        jdbc.update("UPDATE agent_definition_version SET configuration_json=NULL WHERE id=70");
        jdbc.update("INSERT INTO capability_definition(capability_code,capability_type,status) VALUES('cap.read','TOOL','DISABLED')");
        jdbc.update("INSERT INTO capability_revision(capability_code,revision,display_name,content_hash) VALUES('cap.read','1','只读能力',?)",
                "b".repeat(64));
        jdbc.update("INSERT INTO agent_definition_version_capability(definition_version_id,capability_code,capability_revision,position_no) "
                + "VALUES(70,'cap.read','1',1)");
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> store.setEmployeeEnabled(7, 0, true, 42, "enable-disabled-capability", "2".repeat(64), "启用")).getMessage());
        assertEquals(0L, jdbc.queryForObject("SELECT row_version FROM digital_employee WHERE id=7", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM employee_admin_command_receipt WHERE request_id LIKE 'enable-%'", Integer.class),
                "profile 和依赖门槛失败时，员工状态及命令回执都回滚");
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE digital_employee(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,employee_code VARCHAR(64) NOT NULL,"
                + "display_name VARCHAR(128) NOT NULL,enabled BOOLEAN NOT NULL,current_published_version_id BIGINT,row_version BIGINT NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,UNIQUE(tenant_id,employee_code))");
        jdbc.execute("CREATE TABLE platform_id_allocator(scope_key VARCHAR(64) PRIMARY KEY,next_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE employee_admin_command_receipt(actor_user_id BIGINT NOT NULL,request_id VARCHAR(128) NOT NULL,"
                + "action_code VARCHAR(64) NOT NULL,employee_id BIGINT,request_hash CHAR(64) NOT NULL,response_json CLOB,created_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(actor_user_id,request_id))");
        jdbc.execute("CREATE TABLE agent_definition_draft(employee_id BIGINT PRIMARY KEY,draft_revision INT NOT NULL,instructions CLOB NOT NULL,"
                + "model_provider VARCHAR(32) NOT NULL,model_name VARCHAR(128) NOT NULL,configuration_json CLOB,updated_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE agent_definition_management_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_user_id BIGINT NOT NULL,event_type VARCHAR(64) NOT NULL,"
                + "target_type VARCHAR(64) NOT NULL,target_id VARCHAR(128) NOT NULL,request_id VARCHAR(128),reason VARCHAR(500) NOT NULL,"
                + "previous_summary CLOB,new_summary CLOB,occurred_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE agent_definition_version(id BIGINT PRIMARY KEY,employee_id BIGINT NOT NULL,version_no INT NOT NULL,"
                + "instructions CLOB NOT NULL,model_provider VARCHAR(32) NOT NULL,model_name VARCHAR(128) NOT NULL,content_hash CHAR(64) NOT NULL,"
                + "configuration_json CLOB,published_by BIGINT NOT NULL,published_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE agent_definition_version_capability(definition_version_id BIGINT,capability_code VARCHAR(128),"
                + "capability_revision VARCHAR(32),position_no INT)");
        jdbc.execute("CREATE TABLE capability_definition(capability_code VARCHAR(128) PRIMARY KEY,capability_type VARCHAR(24),status VARCHAR(24))");
        jdbc.execute("CREATE TABLE capability_revision(capability_code VARCHAR(128),revision VARCHAR(32),display_name VARCHAR(128),content_hash CHAR(64),"
                + "PRIMARY KEY(capability_code,revision))");
        jdbc.execute("CREATE TABLE agent_definition_version_member(parent_definition_version_id BIGINT,member_role_id VARCHAR(64),"
                + "member_employee_id BIGINT,member_definition_version_id BIGINT,member_steps INT,position_no INT)");
        jdbc.execute("CREATE TABLE agent_definition_runtime_bundle(definition_version_id BIGINT PRIMARY KEY)");
    }
}
