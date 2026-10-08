package com.haizhuo.brain.infrastructure.run;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.haizhuo.brain.infrastructure.session.JdbcSessionEventProjector;
import com.haizhuo.brain.infrastructure.tool.JdbcToolExecutionRepository;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.*;
import com.haizhuo.brain.platform.tool.*;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DurableEventAtomicityTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    @BeforeEach void setup() {
        jdbc = newJdbc("durable_event"); createRunAndToolTables(jdbc);
        insertRun(jdbc,"run-a","session-a","WAITING_TOOL");
        tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }
    @Test void appenderRefusesAutocommitAndDeduplicatesWithoutConsumingCursor() {
        var appender = new JdbcRunEventAppender(jdbc,new JdbcSessionEventProjector(jdbc));
        assertThrows(IllegalStateException.class,()->appender.append(new RunId("run-a"),"TEST","safe",T0));
        tx.executeWithoutResult(s -> {
            long first = appender.append(new RunId("run-a"),"TEST","safe",EventVisibility.USER,DurableEventMetadata.platform(),"fact-1",T0);
            long replay = appender.append(new RunId("run-a"),"TEST","safe",EventVisibility.USER,DurableEventMetadata.platform(),"fact-1",T0);
            assertEquals(first,replay);
        });
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event",Integer.class));
        assertEquals(2L,jdbc.queryForObject("SELECT next_event_cursor FROM platform_agent_session",Long.class));
    }
    @Test void projectionFailureRollsBackBusinessFactAndAllocatedCursor() {
        var projector = new JdbcSessionEventProjector(jdbc) {
            @Override public void projectAt(SessionId sid,long cursor,RunId rid,Integer seq,String type,String content,
                                             EventVisibility visibility,DurableEventMetadata metadata,Instant at) {
                super.projectAt(sid,cursor,rid,seq,type,content,visibility,metadata,at);
                throw new IllegalStateException("injected projection failure");
            }
        };
        var appender = new JdbcRunEventAppender(jdbc,projector);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            appender.lockRun(new RunId("run-a"));
            jdbc.update("UPDATE platform_agent_run SET state='QUEUED' WHERE run_id='run-a'");
            appender.append(new RunId("run-a"),"TEST","safe",T0);
        }));
        assertEquals("WAITING_TOOL",runState(jdbc,"run-a"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event",Integer.class));
        assertEquals(1L,jdbc.queryForObject("SELECT next_event_cursor FROM platform_agent_session",Long.class));
    }
    @Test void toolResultAndRequeueAppearInTheSameSessionProjection() {
        insertExecution(jdbc,"tool-a","run-a","use-a","EXECUTING",null);
        var tools = transactional(new JdbcToolExecutionRepository(jdbc),jdbc);
        PlatformToolExecution old = tools.findById("tool-a").orElseThrow();
        tools.completeExecution(new PlatformToolExecution(old.id(),old.runId(),old.toolUseId(),old.capabilityRevisionId(),
                old.toolName(),old.inputJson(),old.inputDigest(),ToolExecutionState.SUCCEEDED,old.idempotencyKey(),
                null,"{\"success\":true,\"content\":\"ok\"}","d".repeat(64),ResultDeliveryState.READY,null,T0,T0));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event",Integer.class),
                "TOOL_RESULT_READY and RUN_RESUME_QUEUED must both be projected");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_event e LEFT JOIN platform_agent_session_event p "
                + "ON p.run_id=e.run_id AND p.run_sequence=e.sequence_no AND p.session_cursor=e.session_cursor WHERE p.run_id IS NULL",Integer.class));
    }
    @Test void projectionDeletionCannotReuseCursorAndFactsCanRestoreIt() {
        var appender = new JdbcRunEventAppender(jdbc,new JdbcSessionEventProjector(jdbc));
        tx.executeWithoutResult(s->appender.append(new RunId("run-a"),"FIRST","one",T0));
        jdbc.update("DELETE FROM platform_agent_session_event");
        tx.executeWithoutResult(s->appender.append(new RunId("run-a"),"SECOND","two",T0));
        assertEquals(2L,jdbc.queryForObject("SELECT session_cursor FROM platform_agent_session_event",Long.class));
        assertEquals(3L,jdbc.queryForObject("SELECT next_event_cursor FROM platform_agent_session",Long.class));
    }
}
