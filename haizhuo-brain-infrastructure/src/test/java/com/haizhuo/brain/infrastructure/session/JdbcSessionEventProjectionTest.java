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
        projector = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(new JdbcSessionEventProjector(jdbc), jdbc);
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

    @Test
    void retentionWindowExposesFloorAndTrimsOnlyTheOldestHistory() {
        insertSession(jdbc, SESSION.value(), OWNER.value(), 1L, "ACTIVE");
        insertRun(jdbc, "run-1", SESSION.value(), "RUNNING");
        JdbcSessionRunStore store = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(new JdbcSessionRunStore(jdbc, projector), jdbc);
        for (int sequence = 1; sequence <= 5; sequence++) {
            projector.project(SESSION, new RunId("run-1"), sequence, "RUN_COMPLETED", "第" + sequence + "条",
                    T0.plusSeconds(sequence));
        }

        assertEquals(1L, store.oldestSessionCursor(SESSION, OWNER), "未裁剪时下界是第一条事件");
        assertEquals(5, store.findSessionEvents(SESSION, OWNER, 0L, 200).size());

        assertEquals(3, store.trimSessionEvents(SESSION, OWNER, 2), "保留最近 2 条，其余最旧优先删除");

        assertEquals(4L, store.oldestSessionCursor(SESSION, OWNER), "裁剪后下界必须前移");
        assertEquals(List.of(4L, 5L),
                store.findSessionEvents(SESSION, OWNER, 0L, 200).stream().map(SessionEvent::sessionCursor).toList(),
                "裁剪只能从最旧一侧切");
        assertEquals(0, store.trimSessionEvents(SESSION, OWNER, 10), "保留条数超过现有条数时不应删除任何行");
    }

    @Test
    void trimmingNeverTouchesAnotherSessionAndRefusesNonOwner() {
        insertSession(jdbc, SESSION.value(), OWNER.value(), 1L, "ACTIVE");
        insertSession(jdbc, "session-2", 99L, 1L, "ACTIVE");
        insertRun(jdbc, "run-1", SESSION.value(), "RUNNING");
        insertRun(jdbc, "run-2", "session-2", 99L, "RUNNING");
        JdbcSessionRunStore store = com.haizhuo.brain.infrastructure.support.HarnessJdbcTestSupport.transactional(new JdbcSessionRunStore(jdbc, projector), jdbc);
        for (int sequence = 1; sequence <= 3; sequence++) {
            projector.project(SESSION, new RunId("run-1"), sequence, "RUN_COMPLETED", "mine-" + sequence,
                    T0.plusSeconds(sequence));
        }
        projector.project(new SessionId("session-2"), new RunId("run-2"), 1, "USER_INPUT", "theirs", T0);

        assertEquals(0, store.trimSessionEvents(SESSION, new UserId(99), 1), "非属主不得裁剪他人会话历史");
        assertEquals(0L, store.oldestSessionCursor(SESSION, new UserId(99)), "非属主读不到他人会话的下界");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event WHERE session_id=?",
                Integer.class, SESSION.value()), "被拒绝的裁剪不能真的删掉数据");

        store.trimSessionEvents(SESSION, OWNER, 1);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_session_event WHERE session_id='session-2'",
                Integer.class), "另一个会话的投影不受本会话裁剪影响");
    }
}
