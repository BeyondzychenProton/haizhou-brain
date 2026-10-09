package com.haizhuo.brain.infrastructure.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.SessionRenderDelta;
import com.haizhuo.brain.platform.run.SessionRenderStore;
import com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcSessionRenderStoreTest {
    private static final String SESSION = "session-render-1";
    private static final String RUN = "run-render-1";
    private static final String ATTEMPT = "attempt-render-1";
    private static final long OWNER = 42L;
    private JdbcTemplate jdbc;
    private SessionRenderStore store;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.now().minusSeconds(5);
        jdbc = HarnessJdbcTestSupport.newJdbc("sessionrender");
        HarnessJdbcTestSupport.createRunAndToolTables(jdbc);
        createRenderTables();
        HarnessJdbcTestSupport.insertSession(jdbc, SESSION, OWNER, 8L, "ACTIVE");
        HarnessJdbcTestSupport.insertRun(jdbc, RUN, SESSION, OWNER, "RUNNING");
        jdbc.update("INSERT INTO platform_agent_run_execution_target(run_id,execution_mode,role_id,employee_id,"
                        + "definition_version_id,role_slot_id,created_at) VALUES(?,?,?,?,?,?,?)",
                RUN, "DIRECT", "coordinator", 8L, 8L, null, Timestamp.from(now));
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                ATTEMPT, RUN, 1, "worker-1", "lease-1", 19L, Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), "RUNNING", Timestamp.from(now));
        insertUserInput();
        store = HarnessJdbcTestSupport.transactional(new JdbcSessionRenderStore(jdbc), jdbc);
    }

    @Test
    void commitsBoundedDraftAndCursorAtomicallyAndEnforcesSessionOwner() {
        SessionRenderDelta delta = delta(0, 2, "你好");
        long cursor = store.append(delta);
        assertEquals(cursor, store.append(delta));

        var view = store.view(new SessionId(SESSION), new UserId(OWNER), 20, null);
        assertEquals(1, view.snapshotCursor());
        assertEquals(cursor, view.renderCursor());
        assertEquals(2, view.items().size());
        assertEquals("USER_INPUT", view.items().get(0).kind());
        assertEquals("hello", view.items().get(0).text());
        assertEquals("ASSISTANT_DRAFT", view.items().get(1).kind());
        assertEquals(ATTEMPT, view.items().get(1).attemptId());
        assertEquals("coordinator", view.items().get(1).executorRoleId());
        assertEquals("你好", view.items().get(1).text());
        assertEquals(2, view.items().get(1).blocks().get(0).lastAppliedOffset());

        var batches = store.batches(new SessionId(SESSION), new UserId(OWNER), 1, 0, 20);
        assertEquals(1, batches.batches().size());
        assertEquals("你好", batches.batches().get(0).delta());
        assertThrows(IllegalArgumentException.class,
                () -> store.view(new SessionId(SESSION), new UserId(99L), 20, null));
    }

    @Test
    void offsetGapKeepsCommittedPrefixAndMarksDisplayProjectionDegraded() {
        assertEquals(1, store.append(delta(0, 1, "a")));
        assertEquals(1, store.append(delta(2, 3, "c")));

        var view = store.view(new SessionId(SESSION), new UserId(OWNER), 20, null);
        assertEquals("DEGRADED", view.draftRecoveryStatus());
        assertEquals(2, view.items().size());
        assertEquals("hello", view.items().get(0).text());
        assertEquals("a", view.items().get(1).text());
        assertEquals("OFFSET_GAP", jdbc.queryForObject(
                "SELECT degraded_reason_code FROM platform_session_render_state WHERE session_id=?", String.class, SESSION));
    }

    @Test
    void expiredBatchReadSignalsFloorWithoutMutatingTheReadEndpoint() {
        long cursor = store.append(delta(0, 1, "a"));
        jdbc.update("UPDATE platform_session_render_batch SET expires_at=? WHERE session_id=? AND render_cursor=?",
                Timestamp.from(now.minusSeconds(1)), SESSION, cursor);

        var page = store.batches(new SessionId(SESSION), new UserId(OWNER), 1, 0, 20);

        assertTrue(page.expired());
        assertEquals(cursor, page.cursorFloor());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_session_render_batch WHERE session_id=?", Integer.class, SESSION));
        assertEquals(cursor, store.view(new SessionId(SESSION), new UserId(OWNER), 20, null).renderCursorFloor());
    }

    @Test
    void laggingIndependentReaderResumesFromItsRenderCursorAcrossBoundedPages() {
        SessionRenderStore reader = HarnessJdbcTestSupport.transactional(new JdbcSessionRenderStore(jdbc), jdbc);
        store.append(delta(0, 1, "a"));
        store.append(delta(1, 2, "b"));
        store.append(delta(2, 3, "c"));

        var first = reader.batches(new SessionId(SESSION), new UserId(OWNER), 1, 0, 1);
        var resumed = reader.batches(new SessionId(SESSION), new UserId(OWNER), 1, first.batches().get(0).renderCursor(), 1);
        var caughtUp = reader.batches(new SessionId(SESSION), new UserId(OWNER), 1,
                resumed.batches().get(0).renderCursor(), 1);

        assertEquals(1, first.batches().size());
        assertEquals(1, first.batches().get(0).renderCursor());
        assertEquals("a", first.batches().get(0).delta());
        assertEquals(1, resumed.batches().size());
        assertEquals(2, resumed.batches().get(0).renderCursor());
        assertEquals("b", resumed.batches().get(0).delta());
        assertEquals(1, caughtUp.batches().size());
        assertEquals(3, caughtUp.batches().get(0).renderCursor());
        assertEquals("c", caughtUp.batches().get(0).delta());
        assertEquals("abc", reader.view(new SessionId(SESSION), new UserId(OWNER), 20, null)
                .items().get(1).text());
    }

    @Test
    void staleAttemptFenceCannotAppendAfterReplacementAndSecondStoreReadsReplacementDraft() {
        String replacementAttempt = "attempt-render-2";
        jdbc.update("INSERT INTO platform_run_execution_attempt(attempt_id,run_id,attempt_no,worker_id,lease_token,"
                        + "fence_token,lease_expires_at,heartbeat_at,state,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                replacementAttempt, RUN, 2, "worker-2", "lease-2", 20L, Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now.plusSeconds(1)), "RUNNING", Timestamp.from(now.plusSeconds(1)));
        SessionRenderStore secondInstance = HarnessJdbcTestSupport.transactional(new JdbcSessionRenderStore(jdbc), jdbc);

        assertEquals(0, store.append(delta(0, 1, "stale")));
        assertEquals(1, secondInstance.append(delta(replacementAttempt, 20L, 0, 1, "current")));
        assertEquals(1, store.append(delta(0, 1, "late stale")));

        var page = secondInstance.batches(new SessionId(SESSION), new UserId(OWNER), 1, 0, 20);
        var view = secondInstance.view(new SessionId(SESSION), new UserId(OWNER), 20, null);
        var drafts = view.items().stream().filter(item -> "ASSISTANT_DRAFT".equals(item.kind())).toList();
        assertEquals(1, page.batches().size());
        assertEquals(replacementAttempt, page.batches().get(0).attemptId());
        assertEquals("current", page.batches().get(0).delta());
        assertEquals(1, drafts.size());
        assertEquals(replacementAttempt, drafts.get(0).attemptId());
        assertEquals("current", drafts.get(0).text());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_session_render_batch WHERE session_id=?",
                Integer.class, SESSION));
    }

    @Test
    void rootFinalUsesStableFormalIdentityAndKeepsResultReferenceSeparateFromDraft() {
        String resultId = "result-render-1";
        jdbc.update("INSERT INTO platform_agent_result(result_id,run_id,result_key,kind,attempt_id,executor_role_id,"
                        + "employee_id,definition_version_id,media_type,body,body_sha256,byte_size,schema_version,visibility,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                resultId, RUN, "r".repeat(64), "ROOT_FINAL", ATTEMPT, "coordinator", 8L, 8L,
                "text/markdown", "完整正式正文", "h".repeat(64), 21L, 1, "USER", Timestamp.from(now));
        jdbc.update("INSERT INTO platform_agent_run_result_reference(run_id,reference_order,result_id,source_run_id,body_sha256,created_at) "
                        + "VALUES(?,?,?,?,?,?)", RUN, 0, resultId, RUN, "h".repeat(64), Timestamp.from(now));
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at,session_cursor,"
                        + "visibility,result_id,attempt_id) VALUES(?,?,?,?,?,?,?,?,?)",
                RUN, 2, "RUN_COMPLETED", "short summary", Timestamp.from(now.plusSeconds(1)), 2L, "USER", resultId, ATTEMPT);
        jdbc.update("INSERT INTO platform_agent_session_event(session_id,session_cursor,run_id,run_sequence,event_type,"
                        + "visibility,content,created_at) VALUES(?,?,?,?,?,?,?,?)",
                SESSION, 2L, RUN, 2, "RUN_COMPLETED", "USER", "short summary", Timestamp.from(now.plusSeconds(1)));
        jdbc.update("UPDATE platform_agent_session SET next_event_cursor=3 WHERE session_id=?", SESSION);

        var view = store.view(new SessionId(SESSION), new UserId(OWNER), 20, null);

        var finalItem = view.items().stream().filter(item -> "ROOT_FINAL".equals(item.kind())).findFirst().orElseThrow();
        assertEquals(RUN + "-assistant", finalItem.messageId());
        assertEquals("canonical-result", finalItem.bodySource());
        assertEquals(resultId, finalItem.resultId());
        assertEquals(1, view.resultRefs().size());
        assertEquals(resultId, view.resultRefs().get(0).resultId());
    }

    private SessionRenderDelta delta(long from, long to, String text) {
        return delta(ATTEMPT, 19L, from, to, text);
    }

    private SessionRenderDelta delta(String attemptId, long fenceToken, long from, long to, String text) {
        return new SessionRenderDelta(new SessionId(SESSION), new RunId(RUN), attemptId, fenceToken,
                "reply-1", "text", "NATIVE", "ROOT", to, from, to, text, now);
    }

    private void insertUserInput() {
        jdbc.update("INSERT INTO platform_agent_run_event(run_id,sequence_no,event_type,content,created_at,session_cursor,"
                        + "visibility,attempt_id) VALUES(?,?,?,?,?,?,?,?)",
                RUN, 1, "USER_INPUT", "hello", Timestamp.from(now), 1L, "USER", ATTEMPT);
        jdbc.update("INSERT INTO platform_agent_session_event(session_id,session_cursor,run_id,run_sequence,event_type,"
                        + "visibility,content,created_at) VALUES(?,?,?,?,?,?,?,?)",
                SESSION, 1L, RUN, 1, "USER_INPUT", "USER", "hello", Timestamp.from(now));
        jdbc.update("UPDATE platform_agent_session SET next_event_cursor=2 WHERE session_id=?", SESSION);
    }

    private void createRenderTables() {
        jdbc.execute("CREATE TABLE platform_session_render_state(session_id VARCHAR(64) PRIMARY KEY,next_render_cursor BIGINT,"
                + "committed_render_cursor BIGINT,render_cursor_floor BIGINT,draft_recovery_status VARCHAR(24),"
                + "degraded_reason_code VARCHAR(64),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE platform_session_render_attempt(session_id VARCHAR(64),run_id VARCHAR(64),"
                + "attempt_id VARCHAR(64),fence_token BIGINT,last_text_offset BIGINT,phase VARCHAR(24),updated_at TIMESTAMP,"
                + "PRIMARY KEY(session_id,run_id,attempt_id))");
        jdbc.execute("CREATE TABLE platform_session_message(message_id VARCHAR(191) PRIMARY KEY,session_id VARCHAR(64),"
                + "run_id VARCHAR(64),attempt_id VARCHAR(64),kind VARCHAR(24),executor_role_id VARCHAR(64),"
                + "identity_quality VARCHAR(16),message_ordinal BIGINT,phase VARCHAR(24),result_id VARCHAR(64),"
                + "last_render_cursor BIGINT,partial BOOLEAN,created_at TIMESTAMP,UNIQUE(session_id,message_ordinal))");
        jdbc.execute("CREATE TABLE platform_session_message_block(message_id VARCHAR(191),block_id VARCHAR(96),"
                + "block_type VARCHAR(24),block_ordinal BIGINT,safe_body CLOB,last_applied_offset BIGINT,byte_size BIGINT,"
                + "body_sha256 CHAR(64),created_at TIMESTAMP,PRIMARY KEY(message_id,block_id))");
        jdbc.execute("CREATE TABLE platform_session_render_batch(session_id VARCHAR(64),render_cursor BIGINT,run_id VARCHAR(64),"
                + "attempt_id VARCHAR(64),fence_token BIGINT,stream_offset BIGINT,from_offset BIGINT,to_offset BIGINT,"
                + "message_id VARCHAR(191),block_id VARCHAR(96),safe_segments_json CLOB,safe_delta CLOB,content_sha256 CHAR(64),"
                + "committed_at TIMESTAMP,expires_at TIMESTAMP,PRIMARY KEY(session_id,render_cursor),"
                + "UNIQUE(session_id,run_id,attempt_id,from_offset))");
    }
}
