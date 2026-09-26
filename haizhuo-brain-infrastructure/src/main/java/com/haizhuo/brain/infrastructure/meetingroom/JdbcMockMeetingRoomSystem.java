package com.haizhuo.brain.infrastructure.meetingroom;

import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL-backed deterministic A-201 mock, active only for the local meeting workflow. */
@Component
@Profile("meeting-mock")
public class JdbcMockMeetingRoomSystem implements MeetingRoomSystem {
    private static final String ROOM_ID = "A-201";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final RowMapper<Booking> BOOKING_MAPPER = (rs, rowNum) -> new Booking(
            rs.getString("booking_id"), rs.getString("room_id"),
            fromDatabase(rs.getTimestamp("start_at")), fromDatabase(rs.getTimestamp("end_at")),
            rs.getInt("attendees"), rs.getLong("user_id"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcMockMeetingRoomSystem(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public Availability inspect(OffsetDateTime startAt, OffsetDateTime endAt) {
        Room room = jdbc.queryForObject("SELECT room_id, capacity, projector_available, monitor_summary, monitor_observed_at FROM mock_meeting_room WHERE room_id = ?",
                (rs, rowNum) -> new Room(rs.getString("room_id"), rs.getInt("capacity"), rs.getBoolean("projector_available"),
                        rs.getString("monitor_summary"), fromDatabase(rs.getTimestamp("monitor_observed_at"))), ROOM_ID);
        if (room == null) throw new IllegalStateException("Mock room A-201 has not been initialized");
        int overlaps = jdbc.queryForObject("SELECT COUNT(*) FROM mock_meeting_booking WHERE room_id = ? AND start_at < ? AND end_at > ?",
                Integer.class, ROOM_ID, toDatabase(endAt), toDatabase(startAt));
        return new Availability(room.roomId(), room.capacity(), room.projectorAvailable(), overlaps == 0,
                room.monitorSummary(), room.monitorObservedAt());
    }

    @Override
    public Booking reserve(String operationKey, long userId, OffsetDateTime startAt, OffsetDateTime endAt, int attendees) {
        if (operationKey == null || operationKey.isBlank()) throw new IllegalArgumentException("operationKey is required");
        if (attendees < 1) throw new IllegalArgumentException("attendees must be positive");
        if (endAt == null || startAt == null || !endAt.isAfter(startAt)) throw new IllegalArgumentException("endAt must be after startAt");
        String digest = digest(userId + "|" + ROOM_ID + "|" + toDatabase(startAt) + "|" + toDatabase(endAt) + "|" + attendees);
        return transactions.execute(status -> {
            Integer capacity = jdbc.queryForObject("SELECT capacity FROM mock_meeting_room WHERE room_id = ? FOR UPDATE", Integer.class, ROOM_ID);
            if (capacity == null) throw new IllegalStateException("Mock room A-201 has not been initialized");
            Booking previous = findByOperationKey(operationKey);
            if (previous != null) {
                String priorDigest = jdbc.queryForObject("SELECT request_digest FROM mock_meeting_booking WHERE operation_key = ?", String.class, operationKey);
                if (!digest.equals(priorDigest)) throw new IllegalStateException("idempotency key reused with different booking details");
                return previous;
            }
            if (attendees > capacity) throw new IllegalArgumentException("A-201 capacity is " + capacity);
            Integer overlaps = jdbc.queryForObject("SELECT COUNT(*) FROM mock_meeting_booking WHERE room_id = ? AND start_at < ? AND end_at > ?",
                    Integer.class, ROOM_ID, toDatabase(endAt), toDatabase(startAt));
            if (overlaps != null && overlaps > 0) throw new IllegalStateException("A-201 is already booked for this time range");
            Booking booking = new Booking("BK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(),
                    ROOM_ID, startAt, endAt, attendees, userId);
            jdbc.update("INSERT INTO mock_meeting_booking (booking_id, room_id, user_id, start_at, end_at, attendees, operation_key, request_digest, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    booking.bookingId(), ROOM_ID, userId, toDatabase(startAt), toDatabase(endAt), attendees, operationKey, digest, Timestamp.valueOf(LocalDateTime.now(ZONE)));
            return booking;
        });
    }

    @Override
    public Booking find(String bookingId) {
        return first("SELECT booking_id, room_id, user_id, start_at, end_at, attendees FROM mock_meeting_booking WHERE booking_id = ?", bookingId);
    }

    @Override
    public Booking findByOperationKey(String operationKey) {
        return first("SELECT booking_id, room_id, user_id, start_at, end_at, attendees FROM mock_meeting_booking WHERE operation_key = ?", operationKey);
    }

    private Booking first(String sql, String value) {
        List<Booking> rows = jdbc.query(sql, BOOKING_MAPPER, value);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Timestamp toDatabase(OffsetDateTime value) {
        return Timestamp.valueOf(value.atZoneSameInstant(ZONE).toLocalDateTime());
    }

    private static OffsetDateTime fromDatabase(Timestamp value) {
        return value.toLocalDateTime().atZone(ZONE).toOffsetDateTime();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Room(String roomId, int capacity, boolean projectorAvailable, String monitorSummary,
                        OffsetDateTime monitorObservedAt) {}
}
