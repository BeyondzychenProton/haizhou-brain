package com.haizhuo.brain.infrastructure.meetingroom;

import static org.junit.jupiter.api.Assertions.*;

import org.h2.jdbcx.JdbcDataSource;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunStore;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class MeetingRoomJdbcPersistenceTest {
    private JdbcTemplate jdbc;
    private JdbcMockMeetingRoomSystem rooms;
    private JdbcMeetingRoomRunStore runs;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:meeting_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE agent_session (session_id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, active_run_id VARCHAR(36), created_at DATETIME(3) NOT NULL)");
        jdbc.execute("CREATE TABLE agent_run (run_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL, client_request_id VARCHAR(128) NOT NULL, request_digest CHAR(64) NOT NULL, message TEXT NOT NULL, state VARCHAR(24) NOT NULL, business_outcome VARCHAR(24) NOT NULL, answer TEXT, booking_id VARCHAR(48), operation_key VARCHAR(128) NOT NULL, event_sequence INT NOT NULL DEFAULT 0, created_at DATETIME(3) NOT NULL, started_at DATETIME(3), finished_at DATETIME(3), UNIQUE (user_id, client_request_id), FOREIGN KEY (session_id) REFERENCES agent_session(session_id))");
        jdbc.execute("CREATE TABLE agent_run_event (run_id VARCHAR(36) NOT NULL, event_sequence INT NOT NULL, event_type VARCHAR(64) NOT NULL, summary VARCHAR(1000) NOT NULL, created_at DATETIME(3) NOT NULL, PRIMARY KEY (run_id, event_sequence), FOREIGN KEY (run_id) REFERENCES agent_run(run_id))");
        jdbc.execute("CREATE TABLE mock_meeting_room (room_id VARCHAR(32) PRIMARY KEY, capacity INT NOT NULL, projector_available BOOLEAN NOT NULL, monitor_summary VARCHAR(500) NOT NULL, monitor_observed_at DATETIME(3) NOT NULL)");
        jdbc.execute("CREATE TABLE mock_meeting_booking (booking_id VARCHAR(48) PRIMARY KEY, room_id VARCHAR(32) NOT NULL, user_id BIGINT NOT NULL, start_at DATETIME(3) NOT NULL, end_at DATETIME(3) NOT NULL, attendees INT NOT NULL, operation_key VARCHAR(128) NOT NULL UNIQUE, request_digest CHAR(64) NOT NULL, created_at DATETIME(3) NOT NULL, FOREIGN KEY (room_id) REFERENCES mock_meeting_room(room_id))");
        jdbc.update("INSERT INTO mock_meeting_room VALUES ('A-201', 8, TRUE, '当前空闲，设备正常（模拟监控）', CURRENT_TIMESTAMP)");
        var transactionManager = new DataSourceTransactionManager(source);
        rooms = new JdbcMockMeetingRoomSystem(jdbc, transactionManager);
        runs = new JdbcMeetingRoomRunStore(jdbc, transactionManager);
    }

    @Test
    void reservationPersistsAndUsesStableOperationIdempotency() {
        OffsetDateTime start = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end = start.plusHours(1);
        MeetingRoomSystem.Booking first = rooms.reserve("run:one", 1001, start, end, 6);

        assertEquals(first, rooms.reserve("run:one", 1001, start, end, 6));
        assertEquals(first, rooms.find(first.bookingId()));
        assertEquals(first, rooms.findByOperationKey("run:one"));
        assertThrows(IllegalStateException.class, () -> rooms.reserve("run:one", 1001, start, end, 5));
        assertThrows(IllegalStateException.class, () -> rooms.reserve("run:two", 1001, start.plusMinutes(30), end.plusMinutes(30), 4));
        assertThrows(IllegalArgumentException.class, () -> rooms.reserve("run:large", 1001, end.plusDays(1), end.plusDays(2), 9));
    }

    @Test
    void overlappingConcurrentReservationsCannotBothCommit() throws Exception {
        OffsetDateTime start = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(3).withNano(0);
        OffsetDateTime end = start.plusHours(1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> reserveAfterBarrier("parallel:one", start, end, ready, go));
            var second = executor.submit(() -> reserveAfterBarrier("parallel:two", start, end, ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            int successes = 0;
            int conflicts = 0;
            for (var future : java.util.List.of(first, second)) {
                try { future.get(10, TimeUnit.SECONDS); successes++; }
                catch (java.util.concurrent.ExecutionException failure) {
                    if (failure.getCause() instanceof IllegalStateException) conflicts++;
                    else throw failure;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        } finally {
            executor.shutdownNow();
        }
    }

    private MeetingRoomSystem.Booking reserveAfterBarrier(String key, OffsetDateTime start, OffsetDateTime end,
                                                           CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        assertTrue(go.await(5, TimeUnit.SECONDS));
        return rooms.reserve(key, 1001, start, end, 4);
    }

    @Test
    void runRequestDedupAndSessionExclusionAreDurableStoreRules() {
        MeetingRoomRunStore.SessionRecord session = runs.createSession(1001);
        MeetingRoomRunStore.AcceptedRun first = runs.acceptRun(1001, session.sessionId(), "request-1", "reserve at 14:00");
        assertTrue(first.created());
        MeetingRoomRunStore.AcceptedRun replay = runs.acceptRun(1001, session.sessionId(), "request-1", "reserve at 14:00");
        assertFalse(replay.created());
        assertEquals(first.run().runId(), replay.run().runId());
        assertThrows(IllegalStateException.class, () -> runs.acceptRun(1001, session.sessionId(), "request-1", "different content"));
        assertThrows(IllegalStateException.class, () -> runs.acceptRun(1001, session.sessionId(), "request-2", "another run"));

        runs.markRunning(first.run().runId());
        runs.appendEvent(first.run().runId(), "ROOM_SEARCH_AVAILABLE", "A-201 is available");
        runs.complete(first.run().runId(), "NEEDS_INFO", "No booking was made", null);
        var saved = runs.findRun(1001, first.run().runId()).orElseThrow();
        assertEquals("SUCCEEDED", saved.state());
        assertEquals("NEEDS_INFO", saved.businessOutcome());
        assertEquals("No booking was made", saved.answer());
        assertEquals(4, jdbc.queryForObject("SELECT event_sequence FROM agent_run WHERE run_id = ?", Integer.class, first.run().runId()));

        MeetingRoomRunStore.AcceptedRun next = runs.acceptRun(1001, session.sessionId(), "request-2", "another run");
        assertTrue(next.created());
    }
}
