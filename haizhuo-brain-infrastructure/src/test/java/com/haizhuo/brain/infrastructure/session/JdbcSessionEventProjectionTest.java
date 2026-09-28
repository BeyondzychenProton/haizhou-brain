package com.haizhuo.brain.infrastructure.session;

import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.T0;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createRunAndToolTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionAndGuidanceTables;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.createSessionEventTable;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertRun;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.insertSession;
import static com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.newJdbc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.SessionEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会话级事件投影（P2/V14）：run 内序号只在单 Run 有序，跨 Run 续传必须靠 session_cursor。
 * 这里守住两件事——投影与 run 事件一一对应、游标在同一 Session 内严格递增且不因换 Run 回退。
 */
class JdbcSessionEventProjectionTest {

    private static final UserId OWNER = new UserId(42);
    private static final SessionId SESSION = new SessionId("session-1");

    private JdbcTemplate jdbc;
    private JdbcSessionEventProjector projector;

    @BeforeEach
    void setUp() {
        jdbc = newJdbc("sessionevent");
        createRunAndToolTables(jdbc);
        createSessionAndGuidanceTables(jdbc);
        createSessionEventTable(jdbc);
        projector = new JdbcSessionEventProjector(jdbc);
    }

    @Test
    void cursorStaysMonotonicAcrossRunsOfTheSameSession() {
        insertSession(jdbc, SESSION.value(), OWNER.value(), 1L, "ACTIVE");
        insertRun(jdbc, "run-1", SESSION.value(), "RUNNING");
        insertRun(jdbc, "run-2", SESSION.value(), "RUNNING");

        projector.project(SESSION, new RunId("run-1"), 1, "USER_INPUT", "第一轮", T0);
        projector.project(SESSION, new RunId("run-1"), 2, "RUN_COMPLETED", "第一轮结果", T0.plusSeconds(1));
        projector.project(SESSION, new RunId("run-2"), 1, "USER_INPUT", "第二轮", T0.plusSeconds(2));

        List<SessionEvent> events = jdbc.query("SELECT session_id,session_cursor,run_id,run_sequence,event_type,"
                        + "visibility,content,created_at FROM platform_agent_session_event WHERE session_id=? "
                        + "ORDER BY session_cursor",
                (rs, row) -> new SessionEvent(new SessionId(rs.getString("session_id")), rs.getLong("session_cursor"),
                        new RunId(rs.getString("run_id")), rs.getInt("run_sequence"), rs.getString("event_type"),
                        EventVisibility.valueOf(rs.getString("visibility")), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant()), SESSION.value());

        assertEquals(List.of(1L, 2L, 3L), events.stream().map(SessionEvent::sessionCursor).toList(),
                "换 Run 后游标必须继续递增，不能回到 1");
        assertEquals(List.of("run-1", "run-1", "run-2"),
                events.stream().map(event -> event.runId().value()).toList());
        assertEquals(List.of(1, 2, 1), events.stream().map(SessionEvent::runSequence).toList());
        assertTrue(events.stream().allMatch(event -> event.visibility() == EventVisibility.USER));
    }

    @Test
    void projectionIsIsolatedPerSessionAndRespectsOwnerFilter() {
        insertSession(jdbc, SESSION.value(), OWNER.value(), 1L, "ACTIVE");
        insertSession(jdbc, "session-2", 99L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", SESSION.value(), "RUNNING");
        insertRun(jdbc, "run-2", "session-2", 99L, "RUNNING");

        projector.project(SESSION, new RunId("run-1"), 1, "USER_INPUT", "我的", T0);
        projector.project(new SessionId("session-2"), new RunId("run-2"), 1, "USER_INPUT", "别人的", T0);

        List<Long> mine = jdbc.query("SELECT session_cursor FROM platform_agent_session_event event "
                        + "JOIN platform_agent_session session ON session.session_id=event.session_id "
                        + "WHERE event.session_id=? AND session.user_id=? ORDER BY event.session_cursor",
                (rs, row) -> rs.getLong(1), SESSION.value(), OWNER.value());
        assertEquals(List.of(1L), mine, "属主过滤之后只看得到自己会话的投影");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event", Integer.class),
                "另一个会话的投影仍独立编号");
    }

    @Test
    void longContentIsTruncatedToTheColumnWidth() {
        insertSession(jdbc, SESSION.value(), OWNER.value(), 1L, "ACTIVE");
        insertRun(jdbc, "run-1", SESSION.value(), "RUNNING");

        projector.project(SESSION, new RunId("run-1"), 1, "RUN_COMPLETED", "x".repeat(5000), Instant.now());

        Integer length = jdbc.queryForObject("SELECT LENGTH(content) FROM platform_agent_session_event WHERE session_id=?",
                Integer.class, SESSION.value());
        assertEquals(4000, length, "投影内容必须与列宽一致，不能让插入失败");
    }
}
