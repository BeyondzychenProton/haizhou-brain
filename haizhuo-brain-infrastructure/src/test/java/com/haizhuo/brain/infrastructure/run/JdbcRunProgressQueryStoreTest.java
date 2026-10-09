package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertSession;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.RunProgressQueryStore;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcRunProgressQueryStoreTest {
    private static final UserId OWNER = new UserId(7);
    private static final RunId RUN = new RunId("run-progress");
    private JdbcTemplate jdbc;
    private JdbcRunProgressQueryStore store;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("runprogress");
        createRunAndToolTables(jdbc);
        createCollaborationTables();
        insertSession(jdbc, "session-progress", OWNER.value(), 1, "ACTIVE");
        insertRun(jdbc, RUN.value(), "session-progress", OWNER.value(), "RUNNING");
        jdbc.update("UPDATE platform_agent_session SET next_event_cursor=31 WHERE session_id='session-progress'");
        jdbc.update("UPDATE platform_agent_run SET runtime_profile='TEAM_AUTONOMOUS_READONLY' WHERE run_id=?", RUN.value());
        jdbc.update("INSERT INTO agent_definition_version(id,configuration_json) VALUES(1,?)",
                "{\"runtimePolicy\":{\"maxExpertInvocationsPerRun\":4,\"maxParallelDelegations\":2}}");
        jdbc.update("INSERT INTO agent_definition_runtime_bundle(definition_version_id,employee_name) VALUES(1,'Frozen Expert')");
        store = transactional(new JdbcRunProgressQueryStore(jdbc), jdbc);
    }

    @Test
    void snapshotReadsSafeOwnerFactsAndTreatsBoundMemberAsUnknown() {
        addWorkItem("item-a", "PUBLISHED_EXPERT", "ACTIVE", "VALID", "PENDING", "INTERNAL");
        jdbc.update("INSERT INTO platform_run_team_execution(team_execution_id,run_id,user_id,session_id,attempt_id,fence_token,"
                        + "team_name,native_namespace,state,started_at) VALUES('team-a',?,?,'session-progress','attempt-a',1,"
                        + "'team-name','namespace','RUNNING',?)", RUN.value(), OWNER.value(), Timestamp.from(T0));
        jdbc.update("INSERT INTO platform_run_team_member_binding(team_execution_id,role_id,employee_id,definition_version_id,"
                        + "harness_session_key,member_kind,state,last_transition_ordinal,last_transition_at) "
                        + "VALUES('team-a','expert',9,1,'session/expert','WORKER','BOUND',0,NULL)");
        jdbc.update("INSERT INTO platform_run_team_action_reservation(action_id,team_execution_id,action_type,action_count,reserved_at) "
                        + "VALUES('action-a','team-a','TASK_CREATED',2,?)", Timestamp.from(T0.plusSeconds(1)));

        RunProgressQueryStore.SnapshotFacts facts = store.snapshot(OWNER, RUN);

        assertEquals("TEAM_AUTONOMOUS_READONLY", facts.runtimeProfile());
        assertEquals("RUNNING", facts.runState());
        assertEquals(30, facts.asOfSessionCursor());
        assertEquals(1, facts.workItems().size());
        assertEquals("Frozen Expert", facts.workItems().get(0).executorDisplayName());
        assertEquals(4, facts.invocationLimit());
        assertEquals(2, facts.parallelLimit());
        assertEquals(1, facts.activeInvocationCount());
        assertEquals("BOUND", facts.team().members().get(0).state());
        assertEquals(0, facts.team().members().get(0).lastTransitionOrdinal());
        assertEquals(2, facts.team().actionBudgets().get(0).reserved());
        assertFalse(facts.team().actionBudgets().get(0).actionType().contains("action-a"));
    }

    @Test
    void pagesUseCreatedAtThenReferenceAndOwnerFilteringHidesOtherRuns() {
        addWorkItem("item-b", "BUILTIN_GENERAL_PURPOSE", "ACCEPTED", "PENDING", "PENDING", null);
        addWorkItem("item-a", "BUILTIN_GENERAL_PURPOSE", "ACCEPTED", "PENDING", "PENDING", null);
        addWorkItem("item-c", "BUILTIN_GENERAL_PURPOSE", "ACCEPTED", "PENDING", "PENDING", null);

        List<RunProgressQueryStore.WorkItemFact> first = store.workItems(OWNER, RUN, null, 2);
        List<RunProgressQueryStore.WorkItemFact> second = store.workItems(OWNER, RUN,
                new RunProgressQueryStore.Position(T0, "item-a"), 2);

        assertEquals(List.of("item-a", "item-b"), first.stream().map(RunProgressQueryStore.WorkItemFact::workItemRef).toList());
        assertEquals(List.of("item-b", "item-c"), second.stream().map(RunProgressQueryStore.WorkItemFact::workItemRef).toList());
        assertTrue(store.workItems(new UserId(99), RUN, null, 10).isEmpty());
        assertTrue(store.workItem(new UserId(99), RUN, "item-a").isEmpty());
        assertTrue(store.workItem(OWNER, RUN, "missing").isEmpty());
        var detail = store.workItem(OWNER, RUN, "item-a").orElseThrow();
        assertEquals(1, detail.revisions().size());
        assertNull(detail.revisions().get(0).resultId());
    }

    @Test
    void resultIdentifiersAndPrivatePayloadsAreReadOnlyForExplicitVisibilityCheck() {
        addWorkItem("item-secret", "PUBLISHED_EXPERT", "FINISHED", "VALID", "PENDING", "INTERNAL");
        jdbc.update("INSERT INTO platform_agent_result(result_id,run_id,result_key,kind,employee_id,definition_version_id,"
                        + "media_type,body,body_sha256,byte_size,schema_version,visibility,check_metadata_json,created_at) "
                        + "VALUES('private-result',?,REPEAT('a',64),'DELEGATION_FINAL',9,1,'text/plain','UNIQUE_PRIVATE_BODY',"
                        + "REPEAT('b',64),19,1,'INTERNAL','{}',?)", RUN.value(), Timestamp.from(T0));
        jdbc.update("UPDATE platform_run_work_item_revision SET result_id='private-result' WHERE work_item_ref='item-secret'");

        var detail = store.workItem(OWNER, RUN, "item-secret").orElseThrow();

        assertEquals("INTERNAL", detail.revisions().get(0).resultVisibility());
        assertFalse(detail.revisions().get(0).resultBodyAvailable());
        assertEquals("private-result", detail.revisions().get(0).resultId(),
                "存储端仅为服务端可见性映射提供 id；平台 DTO 必须根据 visibility 过滤");
        assertTrue(store.workItemResult(OWNER, RUN, "item-secret", 1).isEmpty());
        assertFalse(detail.item().toString().contains("UNIQUE_PRIVATE_BODY"));
        assertFalse(detail.item().toString().contains("objective-secret"));
    }

    @Test
    void publicRevisionBodyRequiresRunSessionOwnerAndMatchingRevisionHash() {
        addWorkItem("item-visible", "PUBLISHED_EXPERT", "FINISHED", "VALID", "PENDING", "USER");
        String body = "visible result body";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String hash = CanonicalJson.sha256Hex(bytes);
        jdbc.update("INSERT INTO platform_agent_result(result_id,run_id,result_key,kind,employee_id,definition_version_id,"
                        + "media_type,body,body_sha256,byte_size,schema_version,visibility,check_metadata_json,created_at) "
                        + "VALUES('visible-result',?,REPEAT('d',64),'DELEGATION_FINAL',9,1,'text/plain',?,?,?,1,'USER','{}',?)",
                RUN.value(), body, hash, bytes.length, Timestamp.from(T0));
        jdbc.update("UPDATE platform_run_work_item_revision SET result_id='visible-result',result_sha256=? "
                + "WHERE work_item_ref='item-visible' AND assignment_revision=1", hash);

        var summary = store.workItem(OWNER, RUN, "item-visible").orElseThrow().revisions().get(0);
        var result = store.workItemResult(OWNER, RUN, "item-visible", 1).orElseThrow();

        assertTrue(summary.resultBodyAvailable());
        assertEquals("visible-result", summary.resultId());
        assertEquals(body, result.body());
        assertEquals(hash, result.bodySha256());

        jdbc.update("UPDATE platform_agent_result SET byte_size=? WHERE result_id='visible-result'", bytes.length + 1L);
        assertTrue(store.workItemResult(OWNER, RUN, "item-visible", 1).isEmpty());
        jdbc.update("UPDATE platform_agent_result SET byte_size=? WHERE result_id='visible-result'", bytes.length);

        jdbc.update("UPDATE platform_run_work_item_revision SET result_sha256=? "
                + "WHERE work_item_ref='item-visible' AND assignment_revision=1", "f".repeat(64));
        assertFalse(store.workItem(OWNER, RUN, "item-visible").orElseThrow().revisions().get(0).resultBodyAvailable());
        assertTrue(store.workItemResult(OWNER, RUN, "item-visible", 1).isEmpty());

        jdbc.update("UPDATE platform_run_work_item_revision SET result_sha256=? "
                + "WHERE work_item_ref='item-visible' AND assignment_revision=1", hash);
        jdbc.update("UPDATE platform_agent_session SET user_id=99 WHERE session_id='session-progress'");
        assertTrue(store.workItemResult(OWNER, RUN, "item-visible", 1).isEmpty());
    }

    private void addWorkItem(String ref, String source, String invocationState,
                             String formatStatus, String reviewStatus, String visibility) {
        jdbc.update("INSERT INTO platform_run_work_item(work_item_ref,run_id,role_id,source_kind,employee_id,definition_version_id,"
                        + "required,latest_assignment_revision,state,created_at,updated_at) VALUES(?,?,?, ?,9,1,TRUE,1,?,?,?)",
                ref, RUN.value(), "role-" + ref, source, reviewStatus, Timestamp.from(T0), Timestamp.from(T0));
        jdbc.update("INSERT INTO platform_run_work_item_revision(work_item_ref,assignment_revision,payload_json,payload_sha256,"
                        + "objective,deliverable_media_type,contract_version,required_fields_json,input_refs_json,dependencies_json,"
                        + "format_status,review_status,created_at) VALUES(?,1,'{}',REPEAT('c',64),'objective-secret','text/plain',"
                        + "'v1','[]','[\"private-input-path\"]','[]',?,?,?)",
                ref, formatStatus, reviewStatus, Timestamp.from(T0));
        if (invocationState != null) {
            jdbc.update("INSERT INTO platform_run_delegation_invocation(invocation_id,run_id,attempt_id,fence_token,role_id,state,"
                            + "started_at,finished_at,work_item_ref,assignment_revision) VALUES(?,?, 'attempt-a',1,?,?,?,NULL,?,1)",
                    "inv-" + ref, RUN.value(), "role-" + ref, invocationState, Timestamp.from(T0), ref);
        }
    }

    private void createCollaborationTables() {
        jdbc.execute("CREATE TABLE agent_definition_version(id BIGINT PRIMARY KEY,configuration_json CLOB)");
        jdbc.execute("CREATE TABLE agent_definition_runtime_bundle(definition_version_id BIGINT PRIMARY KEY,employee_name VARCHAR(128))");
        jdbc.execute("CREATE TABLE platform_run_work_item(work_item_ref VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64) NOT NULL,"
                + "role_id VARCHAR(64) NOT NULL,source_kind VARCHAR(32),employee_id BIGINT,definition_version_id BIGINT,"
                + "required BOOLEAN,latest_assignment_revision INT,state VARCHAR(24),created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_run_work_item_revision(work_item_ref VARCHAR(64),assignment_revision INT,payload_json CLOB,"
                + "payload_sha256 CHAR(64),objective CLOB,deliverable_media_type VARCHAR(64),contract_version VARCHAR(64),"
                + "required_fields_json CLOB,input_refs_json CLOB,dependencies_json CLOB,result_id VARCHAR(64),result_sha256 CHAR(64),"
                + "format_status VARCHAR(16),review_status VARCHAR(24),review_reason VARCHAR(2000),created_at TIMESTAMP,"
                + "reviewed_at TIMESTAMP,PRIMARY KEY(work_item_ref,assignment_revision))");
        jdbc.execute("CREATE TABLE platform_run_delegation_invocation(invocation_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "attempt_id VARCHAR(64),fence_token BIGINT,role_id VARCHAR(64),state VARCHAR(16),started_at TIMESTAMP,"
                + "finished_at TIMESTAMP,work_item_ref VARCHAR(64),assignment_revision INT)");
        jdbc.execute("CREATE TABLE platform_run_team_execution(team_execution_id VARCHAR(64) PRIMARY KEY,run_id VARCHAR(64),"
                + "user_id BIGINT,session_id VARCHAR(64),attempt_id VARCHAR(64),fence_token BIGINT,team_name VARCHAR(96),"
                + "native_namespace VARCHAR(128),state VARCHAR(24),started_at TIMESTAMP,completed_at TIMESTAMP,result_sha256 CHAR(64),"
                + "recovery_reason_code VARCHAR(64))");
        jdbc.execute("CREATE TABLE platform_run_team_member_binding(team_execution_id VARCHAR(64),role_id VARCHAR(64),"
                + "employee_id BIGINT,definition_version_id BIGINT,harness_session_key VARCHAR(160),member_kind VARCHAR(16),"
                + "state VARCHAR(16),last_transition_ordinal BIGINT NOT NULL DEFAULT 0,last_transition_phase TINYINT NOT NULL DEFAULT 0,"
                + "last_transition_at TIMESTAMP NULL,"
                + "PRIMARY KEY(team_execution_id,role_id))");
        jdbc.execute("CREATE TABLE platform_run_team_action_reservation(action_id VARCHAR(64) PRIMARY KEY,team_execution_id VARCHAR(64),"
                + "action_type VARCHAR(24),action_count INT,reserved_at TIMESTAMP)");
    }
}
