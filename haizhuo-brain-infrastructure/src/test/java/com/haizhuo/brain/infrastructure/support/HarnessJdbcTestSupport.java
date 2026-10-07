package com.haizhuo.brain.infrastructure.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 共享的 H2 测试支撑：内联 V6/V8/V9/V11/V12 中 durable run/tool 相关表结构（仅保留
 * store 实际访问的列，省略与断言无关的外键/生成列），并提供行级夹具。每个测试使用独立
 * 内存库，保证可重复执行。
 */
public final class HarnessJdbcTestSupport {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private HarnessJdbcTestSupport() {
    }

    public static JdbcTemplate newJdbc(String databasePrefix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + databasePrefix + "_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        return new JdbcTemplate(dataSource);
    }

    /** V6 + V8（cancel_requested_at）+ V9（run spec）+ V11 + V12 的最小内联结构。 */
    public static void createRunAndToolTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_agent_run(run_id VARCHAR(64) PRIMARY KEY,session_id VARCHAR(64) NOT NULL,"
                + "user_id BIGINT NOT NULL,employee_id BIGINT NOT NULL,definition_version_id BIGINT NOT NULL,"
                + "client_request_id VARCHAR(128) NOT NULL,input_digest CHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "cancel_requested_at TIMESTAMP NULL,created_at TIMESTAMP NOT NULL,started_at TIMESTAMP NULL,"
                + "finished_at TIMESTAMP NULL,failure_code VARCHAR(64) NULL)");
        jdbc.execute("CREATE TABLE platform_agent_run_event(run_id VARCHAR(64) NOT NULL,sequence_no INT NOT NULL,"
                + "event_type VARCHAR(64) NOT NULL,content VARCHAR(4000) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(run_id,sequence_no))");
        jdbc.execute("CREATE TABLE platform_agent_run_spec(run_id VARCHAR(64) PRIMARY KEY,"
                + "definition_version_id BIGINT NOT NULL,definition_bundle_id BIGINT NOT NULL,"
                + "definition_bundle_hash CHAR(64) NOT NULL,model_visible_tool_names_json TEXT NOT NULL,"
                + "tool_view_hash CHAR(64) NOT NULL,effective_capability_hash CHAR(64) NOT NULL,"
                + "channel_type VARCHAR(32) NULL,created_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE platform_run_execution_attempt(attempt_id VARCHAR(64) PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,attempt_no INT NOT NULL,worker_id VARCHAR(128) NOT NULL,"
                + "lease_token VARCHAR(64) NOT NULL,fence_token BIGINT NOT NULL,lease_expires_at TIMESTAMP NOT NULL,"
                + "heartbeat_at TIMESTAMP NOT NULL,state VARCHAR(32) NOT NULL,started_at TIMESTAMP NOT NULL,"
                + "finished_at TIMESTAMP NULL,UNIQUE(run_id,attempt_no),UNIQUE(run_id,fence_token))");
        jdbc.execute("CREATE TABLE platform_tool_execution(tool_execution_id VARCHAR(64) PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,tool_use_id VARCHAR(128) NOT NULL,capability_revision_id BIGINT NOT NULL,"
                + "tool_name VARCHAR(128) NOT NULL,input_json TEXT NOT NULL,input_digest CHAR(64) NOT NULL,"
                + "state VARCHAR(32) NOT NULL,idempotency_key CHAR(64) NOT NULL,external_receipt VARCHAR(256) NULL,"
                + "result_json TEXT NULL,result_digest CHAR(64) NULL,result_delivery_state VARCHAR(16) NULL,"
                + "error_code VARCHAR(64) NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "UNIQUE(run_id,tool_use_id),UNIQUE(idempotency_key))");
        jdbc.execute("CREATE TABLE platform_tool_approval(approval_id VARCHAR(64) PRIMARY KEY,"
                + "tool_execution_id VARCHAR(64) NOT NULL UNIQUE,run_id VARCHAR(64) NOT NULL,"
                + "requested_for_user_id BIGINT NOT NULL,decision VARCHAR(16) NOT NULL,"
                + "decided_by_user_id BIGINT NULL,reason VARCHAR(512) NULL,created_at TIMESTAMP NOT NULL,"
                + "decided_at TIMESTAMP NULL)");
    }

    /** V6 会话表 + V8 引导表的最小内联结构。 */
    public static void createSessionAndGuidanceTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_agent_session(session_id VARCHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL,"
                + "employee_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "last_active_at TIMESTAMP NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,"
                + "definition_version_id BIGINT NULL,legacy_runtime BOOLEAN NOT NULL DEFAULT TRUE)");
        jdbc.execute("CREATE TABLE platform_agent_run_guidance(guidance_id VARCHAR(64) PRIMARY KEY,"
                + "run_id VARCHAR(64) NOT NULL,author_user_id BIGINT NOT NULL,source VARCHAR(24) NOT NULL,"
                + "content VARCHAR(4000) NOT NULL,status VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,"
                + "consumed_at TIMESTAMP NULL)");
    }

    /** V14 会话级事件投影的最小内联结构（与迁移同构，省略外键）。 */
    public static void createSessionEventTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_agent_session_event(session_id VARCHAR(64) NOT NULL,"
                + "session_cursor BIGINT NOT NULL,run_id VARCHAR(64) NULL,run_sequence INT NULL,"
                + "event_type VARCHAR(64) NOT NULL,visibility VARCHAR(32) NOT NULL DEFAULT 'USER',"
                + "content VARCHAR(4000) NULL,created_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(session_id,session_cursor))");
    }

    /** P3 渠道表：与 V15 + V16 迁移保持一致（测试库关闭 Flyway，需要手工建表）。 */
    public static void createChannelTables(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE platform_channel_account(binding_id VARCHAR(64) NOT NULL,tenant_id BIGINT NOT NULL,"
                + "provider VARCHAR(32) NOT NULL,external_account_key VARCHAR(128) NOT NULL,"
                + "credential_ref VARCHAR(256) NOT NULL,default_employee_id BIGINT NOT NULL,"
                + "dm_scope VARCHAR(32) NOT NULL DEFAULT 'PER_PEER',"
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(binding_id),UNIQUE(provider,external_account_key))");
        jdbc.execute("CREATE TABLE platform_channel_identity(binding_id VARCHAR(64) NOT NULL,"
                + "external_user_id VARCHAR(128) NOT NULL,user_id BIGINT NOT NULL,state VARCHAR(16) NOT NULL,"
                + "linked_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(binding_id,external_user_id))");
        jdbc.execute("CREATE TABLE platform_channel_conversation(binding_id VARCHAR(64) NOT NULL,"
                + "external_conversation_id VARCHAR(191) NOT NULL,session_id VARCHAR(64) NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(binding_id,external_conversation_id),UNIQUE(session_id))");
        jdbc.execute("CREATE TABLE platform_channel_inbox(binding_id VARCHAR(64) NOT NULL,"
                + "provider_event_id VARCHAR(191) NOT NULL,provider VARCHAR(32) NOT NULL,"
                + "external_conversation_id VARCHAR(191) NOT NULL,external_user_id VARCHAR(128) NOT NULL,"
                + "user_id BIGINT NOT NULL,session_id VARCHAR(64) NULL,run_id VARCHAR(64) NULL,"
                + "reply_target VARCHAR(512) NOT NULL,content VARCHAR(4000) NOT NULL,accepted_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(binding_id,provider_event_id))");
        jdbc.execute("CREATE TABLE platform_channel_delivery(delivery_id VARCHAR(64) NOT NULL,"
                + "run_id VARCHAR(64) NOT NULL,binding_id VARCHAR(64) NOT NULL,provider VARCHAR(32) NOT NULL,"
                + "reply_target VARCHAR(512) NOT NULL,content VARCHAR(4000) NOT NULL,"
                + "idempotency_key VARCHAR(191) NOT NULL,state VARCHAR(24) NOT NULL,"
                + "external_message_id VARCHAR(191) NULL,attempts INT NOT NULL DEFAULT 0,last_error VARCHAR(256) NULL,"
                + "created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                + "PRIMARY KEY(delivery_id),UNIQUE(idempotency_key))");
    }

    public static void insertSession(JdbcTemplate jdbc, String sessionId, long userId, long employeeId, String status) {
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,last_active_at,row_version)"
                        + " VALUES(?,?,?,?,?,?,0)",
                sessionId, userId, employeeId, status, Timestamp.from(T0), Timestamp.from(T0));
    }

    public static void insertRun(JdbcTemplate jdbc, String runId, String sessionId, String state) {
        insertRun(jdbc, runId, sessionId, 42L, state);
    }

    public static void insertRun(JdbcTemplate jdbc, String runId, String sessionId, long userId, String state) {
        jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,"
                        + "client_request_id,input_digest,state,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                runId, sessionId, userId, 1L, 1L, "req-" + runId, "d".repeat(64), state, Timestamp.from(T0));
    }

    public static void insertRunSpec(JdbcTemplate jdbc, String runId) {
        jdbc.update("INSERT INTO platform_agent_run_spec(run_id,definition_version_id,definition_bundle_id,"
                        + "definition_bundle_hash,model_visible_tool_names_json,tool_view_hash,effective_capability_hash,"
                        + "channel_type,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                runId, 1L, 1L, "b".repeat(64), "[\"meeting_room_search\"]", "t".repeat(64), "e".repeat(64),
                null, Timestamp.from(T0));
    }

    /** 插入一条工具执行；state/delivery 由用例控制，其余字段取合法默认值。 */
    public static void insertExecution(JdbcTemplate jdbc, String id, String runId, String toolUseId,
                                       String state, String deliveryState) {
        jdbc.update("INSERT INTO platform_tool_execution(tool_execution_id,run_id,tool_use_id,capability_revision_id,"
                        + "tool_name,input_json,input_digest,state,idempotency_key,result_delivery_state,created_at,updated_at)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id, runId, toolUseId, 101L, "meeting_room_search", "{\"roomId\":\"A-201\"}", "i".repeat(64),
                state, idempotencyKey(id), deliveryState, Timestamp.from(T0), Timestamp.from(T0));
    }

    public static String idempotencyKey(String id) {
        String seed = "k" + id;
        return seed.length() >= 64 ? seed.substring(0, 64) : seed + "0".repeat(64 - seed.length());
    }

    public static List<String> eventTypes(JdbcTemplate jdbc, String runId) {
        return jdbc.query("SELECT event_type FROM platform_agent_run_event WHERE run_id=? ORDER BY sequence_no",
                (rs, n) -> rs.getString(1), runId);
    }

    public static String runState(JdbcTemplate jdbc, String runId) {
        return jdbc.queryForObject("SELECT state FROM platform_agent_run WHERE run_id=?", String.class, runId);
    }

    public static String executionState(JdbcTemplate jdbc, String toolExecutionId) {
        return jdbc.queryForObject("SELECT state FROM platform_tool_execution WHERE tool_execution_id=?",
                String.class, toolExecutionId);
    }
}
