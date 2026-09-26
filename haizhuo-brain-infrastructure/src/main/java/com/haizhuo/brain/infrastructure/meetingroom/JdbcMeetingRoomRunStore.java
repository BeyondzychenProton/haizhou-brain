package com.haizhuo.brain.infrastructure.meetingroom;

import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunStore;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL-backed inbox, Run state, idempotency and user-visible event store for the local loop. */
@Repository
@Profile("meeting-mock")
public class JdbcMeetingRoomRunStore implements MeetingRoomRunStore {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String RUN_SELECT = "SELECT r.run_id, r.session_id, r.user_id, r.state, r.business_outcome, r.answer, r.message, r.created_at, "
            + "b.booking_id AS b_booking_id, b.room_id AS b_room_id, b.start_at AS b_start_at, b.end_at AS b_end_at, b.attendees AS b_attendees, b.user_id AS b_user_id "
            + "FROM agent_run r LEFT JOIN mock_meeting_booking b ON b.booking_id = r.booking_id ";
    private static final RowMapper<RunRecord> RUN_MAPPER = JdbcMeetingRoomRunStore::mapRun;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcMeetingRoomRunStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public SessionRecord createSession(long userId) {
        String sessionId = UUID.randomUUID().toString();
        LocalDateTime createdAt = LocalDateTime.now(ZONE);
        jdbc.update("INSERT INTO agent_session (session_id, user_id, active_run_id, created_at) VALUES (?, ?, NULL, ?)",
                sessionId, userId, Timestamp.valueOf(createdAt));
        return new SessionRecord(sessionId, userId, createdAt.atZone(ZONE).toInstant());
    }

    @Override
    public AcceptedRun acceptRun(long userId, String sessionId, String clientRequestId, String message) {
        String normalizedMessage = message.trim();
        String digest = digest(sessionId + "\n" + normalizedMessage);
        try {
            return transactions.execute(status -> {
                List<String> owners = jdbc.query("SELECT user_id FROM agent_session WHERE session_id = ? FOR UPDATE",
                        (rs, rowNum) -> rs.getString(1), sessionId);
                if (owners.isEmpty() || Long.parseLong(owners.get(0)) != userId) throw new IllegalArgumentException("Session not found");
                RunRecord previous = findByRequest(userId, clientRequestId);
                if (previous != null) {
                    if (!digest.equals(readDigest(previous.runId()))) throw new IllegalStateException("clientRequestId was already used with different content");
                    return new AcceptedRun(previous, false);
                }
                List<String> active = jdbc.query("SELECT active_run_id FROM agent_session WHERE session_id = ?",
                        (rs, rowNum) -> rs.getString(1), sessionId);
                String activeRun = active.isEmpty() ? null : active.get(0);
                if (activeRun != null) throw new IllegalStateException("Session already has an active Run");

                String runId = UUID.randomUUID().toString();
                String operationKey = "run:" + runId;
                LocalDateTime createdAt = LocalDateTime.now(ZONE);
                jdbc.update("INSERT INTO agent_run (run_id, session_id, user_id, client_request_id, request_digest, message, state, business_outcome, answer, booking_id, operation_key, event_sequence, created_at) VALUES (?, ?, ?, ?, ?, ?, 'ACCEPTED', 'PENDING', NULL, NULL, ?, 0, ?)",
                        runId, sessionId, userId, clientRequestId, digest, normalizedMessage, operationKey, Timestamp.valueOf(createdAt));
                jdbc.update("UPDATE agent_session SET active_run_id = ? WHERE session_id = ?", runId, sessionId);
                appendEventInTransaction(runId, "RUN_ACCEPTED", "已接收会议室预定请求");
                return new AcceptedRun(findRun(userId, runId).orElseThrow(), true);
            });
        } catch (DuplicateKeyException duplicate) {
            RunRecord previous = findByRequest(userId, clientRequestId);
            if (previous == null) throw duplicate;
            if (!digest.equals(readDigest(previous.runId()))) throw new IllegalStateException("clientRequestId was already used with different content");
            return new AcceptedRun(previous, false);
        }
    }

    @Override
    public Optional<RunRecord> findRun(long userId, String runId) {
        List<RunRecord> rows = jdbc.query(RUN_SELECT + "WHERE r.user_id = ? AND r.run_id = ?", RUN_MAPPER, userId, runId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void markRunning(String runId) {
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update("UPDATE agent_run SET state = 'RUNNING', started_at = ? WHERE run_id = ? AND state = 'ACCEPTED'",
                    Timestamp.valueOf(LocalDateTime.now(ZONE)), runId);
            if (changed > 0) appendEventInTransaction(runId, "RUN_STARTED", "数字员工开始处理请求");
        });
    }

    @Override
    public void appendEvent(String runId, String type, String summary) {
        transactions.executeWithoutResult(status -> appendEventInTransaction(runId, type, summary));
    }

    @Override
    public void complete(String runId, String outcome, String answer, MeetingRoomSystem.Booking booking) {
        finish(runId, "SUCCEEDED", outcome, answer, booking, "RUN_COMPLETED");
    }

    @Override
    public void fail(String runId, String outcome, String answer, MeetingRoomSystem.Booking booking) {
        finish(runId, "FAILED", outcome, answer, booking, "RUN_FAILED");
    }

    @Override
    public List<RunRecord> findRecoverableRuns() {
        return jdbc.query(RUN_SELECT + "WHERE r.state IN ('ACCEPTED', 'RUNNING') ORDER BY r.created_at", RUN_MAPPER);
    }

    private void finish(String runId, String state, String outcome, String answer, MeetingRoomSystem.Booking booking, String eventType) {
        transactions.executeWithoutResult(status -> {
            List<String> states = jdbc.query("SELECT state FROM agent_run WHERE run_id = ? FOR UPDATE", (rs, rowNum) -> rs.getString(1), runId);
            if (states.isEmpty() || "SUCCEEDED".equals(states.get(0)) || "FAILED".equals(states.get(0))) return;
            String bookingId = booking == null ? null : booking.bookingId();
            jdbc.update("UPDATE agent_run SET state = ?, business_outcome = ?, answer = ?, booking_id = ?, finished_at = ? WHERE run_id = ?",
                    state, outcome, answer, bookingId, Timestamp.valueOf(LocalDateTime.now(ZONE)), runId);
            jdbc.update("UPDATE agent_session SET active_run_id = NULL WHERE active_run_id = ?", runId);
            appendEventInTransaction(runId, eventType, safeSummary(answer));
        });
    }

    private void appendEventInTransaction(String runId, String type, String summary) {
        List<Integer> sequences = jdbc.query("SELECT event_sequence FROM agent_run WHERE run_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getInt(1), runId);
        if (sequences.isEmpty()) throw new IllegalArgumentException("Run not found");
        int next = sequences.get(0) + 1;
        jdbc.update("UPDATE agent_run SET event_sequence = ? WHERE run_id = ?", next, runId);
        jdbc.update("INSERT INTO agent_run_event (run_id, event_sequence, event_type, summary, created_at) VALUES (?, ?, ?, ?, ?)",
                runId, next, type, safeSummary(summary), Timestamp.valueOf(LocalDateTime.now(ZONE)));
    }

    private RunRecord findByRequest(long userId, String clientRequestId) {
        List<RunRecord> rows = jdbc.query(RUN_SELECT + "WHERE r.user_id = ? AND r.client_request_id = ?", RUN_MAPPER, userId, clientRequestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String readDigest(String runId) {
        return jdbc.queryForObject("SELECT request_digest FROM agent_run WHERE run_id = ?", String.class, runId);
    }

    private static RunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        String bookingId = rs.getString("b_booking_id");
        MeetingRoomSystem.Booking booking = bookingId == null ? null : new MeetingRoomSystem.Booking(
                bookingId, rs.getString("b_room_id"), fromDatabase(rs.getTimestamp("b_start_at")),
                fromDatabase(rs.getTimestamp("b_end_at")), rs.getInt("b_attendees"), rs.getLong("b_user_id"));
        return new RunRecord(rs.getString("run_id"), rs.getString("session_id"), rs.getLong("user_id"),
                rs.getString("state"), rs.getString("business_outcome"), rs.getString("answer"), booking,
                rs.getString("message"), fromDatabase(rs.getTimestamp("created_at")).toInstant());
    }

    private static java.time.OffsetDateTime fromDatabase(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
    }

    private static String safeSummary(String value) {
        if (value == null || value.isBlank()) return "执行结束";
        String normalized = value.replaceAll("(?i)(api[-_ ]?key|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[已隐藏]");
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
