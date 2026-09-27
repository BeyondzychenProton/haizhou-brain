package com.haizhuo.brain.infrastructure.capability;

import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.tool.CapabilityExecutionContext;
import com.haizhuo.brain.platform.tool.CapabilityExecutor;
import com.haizhuo.brain.platform.tool.ToolExecutionResult;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 针对预置会议室能力（implementationKey 为 {@code meeting-room-v1}）的白名单执行器，
 * 数据取自确定性的 A-201 模拟表。副作用只在平台侧发生（I-06）；建单按平台幂等键幂等（§43）。
 * 返回内容是安全且有长度上限的摘要——不暴露内部 SQL 细节（§89）。
 */
@Component
public class MeetingRoomCapabilityExecutor implements CapabilityExecutor {

    public static final String IMPLEMENTATION_KEY = "meeting-room-v1";

    private static final String ROOM_ID = "A-201";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SEARCH_ACTION = "meeting_room.availability.read";
    private static final String RESERVE_ACTION = "meeting_room.booking.create";

    private final JdbcTemplate jdbc;

    public MeetingRoomCapabilityExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String implementationKey() {
        return IMPLEMENTATION_KEY;
    }

    @Override
    public boolean supports(CapabilityCatalogEntry capability) {
        return IMPLEMENTATION_KEY.equals(capability.implementationKey());
    }

    @Override
    @Transactional
    public ToolExecutionResult execute(CapabilityExecutionContext context, Map<String, Object> arguments) {
        String action = context.capability().businessAction();
        try {
            if (SEARCH_ACTION.equals(action)) {
                return search(arguments);
            }
            if (RESERVE_ACTION.equals(action)) {
                return reserve(context, arguments);
            }
            return ToolExecutionResult.failed("UNSUPPORTED_BUSINESS_ACTION", "该操作暂不支持。");
        } catch (IllegalArgumentException validation) {
            return ToolExecutionResult.failed("INVALID_ARGUMENTS", "请求参数不完整或格式不正确。");
        }
    }

    private ToolExecutionResult search(Map<String, Object> arguments) {
        LocalDateTime startAt = parseTime(arguments.get("startAt"));
        LocalDateTime endAt = parseTime(arguments.get("endAt"));
        int attendees = parseAttendees(arguments.get("attendees"));
        var room = jdbc.query("SELECT capacity,projector_available,monitor_summary FROM mock_meeting_room WHERE room_id=?",
                (rs, n) -> new Room(rs.getInt("capacity"), rs.getBoolean("projector_available"),
                        rs.getString("monitor_summary")), ROOM_ID).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Mock room A-201 has not been initialized"));
        Integer overlaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_meeting_booking WHERE room_id=? AND start_at<? AND end_at>?",
                Integer.class, ROOM_ID, Timestamp.valueOf(endAt), Timestamp.valueOf(startAt));
        boolean available = overlaps != null && overlaps == 0 && attendees <= room.capacity();
        String content = "会议室 " + ROOM_ID + "（容量 " + room.capacity() + " 人，投影仪"
                + (room.projectorAvailable() ? "可用" : "不可用") + "）：该时段"
                + (available ? "空闲，可预定。" : "不可预定（" + (attendees > room.capacity() ? "人数超过容量" : "时间冲突") + "）。")
                + "监控摘要：" + room.monitorSummary();
        return ToolExecutionResult.succeeded(content, null);
    }

    private ToolExecutionResult reserve(CapabilityExecutionContext context, Map<String, Object> arguments) {
        LocalDateTime startAt = parseTime(arguments.get("startAt"));
        LocalDateTime endAt = parseTime(arguments.get("endAt"));
        int attendees = parseAttendees(arguments.get("attendees"));
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt must be after startAt");
        }
        String operationKey = context.execution().idempotencyKey();
        String digest = CanonicalJson.sha256Hex((context.userId().value() + "|" + ROOM_ID + "|" + startAt
                + "|" + endAt + "|" + attendees).getBytes(StandardCharsets.UTF_8));

        Integer capacity = jdbc.queryForObject("SELECT capacity FROM mock_meeting_room WHERE room_id=? FOR UPDATE",
                Integer.class, ROOM_ID);
        if (capacity == null) {
            throw new IllegalStateException("Mock room A-201 has not been initialized");
        }
        var previous = jdbc.query("SELECT booking_id,request_digest FROM mock_meeting_booking WHERE operation_key=?",
                (rs, n) -> new PriorBooking(rs.getString("booking_id"), rs.getString("request_digest")), operationKey);
        if (!previous.isEmpty()) {
            if (!digest.equals(previous.get(0).requestDigest())) {
                return ToolExecutionResult.failed("IDEMPOTENCY_CONFLICT", "相同请求标识对应了不同的预订内容。");
            }
            return ToolExecutionResult.succeeded("会议室 " + ROOM_ID + " 已预定（重复请求已去重），预订号："
                    + previous.get(0).bookingId(), previous.get(0).bookingId());
        }
        if (attendees > capacity) {
            return ToolExecutionResult.failed("CAPACITY_EXCEEDED", ROOM_ID + " 最多容纳 " + capacity + " 人。");
        }
        Integer overlaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM mock_meeting_booking WHERE room_id=? AND start_at<? AND end_at>?",
                Integer.class, ROOM_ID, Timestamp.valueOf(endAt), Timestamp.valueOf(startAt));
        if (overlaps != null && overlaps > 0) {
            return ToolExecutionResult.failed("MEETING_ROOM_CONFLICT", ROOM_ID + " 在该时段已被占用。");
        }
        String bookingId = "BK-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        jdbc.update("INSERT INTO mock_meeting_booking(booking_id,room_id,user_id,start_at,end_at,attendees,"
                        + "operation_key,request_digest,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bookingId, ROOM_ID, context.userId().value(), Timestamp.valueOf(startAt), Timestamp.valueOf(endAt),
                attendees, operationKey, digest, Timestamp.valueOf(LocalDateTime.now(ZONE)));
        return ToolExecutionResult.succeeded("已成功预定会议室 " + ROOM_ID + "，时段 " + startAt + " 至 " + endAt
                + "，预订号：" + bookingId, bookingId);
    }

    private static LocalDateTime parseTime(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("time is required");
        }
        try {
            return OffsetDateTime.parse(value.toString()).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid ISO-8601 time: " + value);
        }
    }

    private static int parseAttendees(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // 故意落穿到下面的参数校验错误
            }
        }
        throw new IllegalArgumentException("attendees must be a positive integer");
    }

    private record Room(int capacity, boolean projectorAvailable, String monitorSummary) {
    }

    private record PriorBooking(String bookingId, String requestDigest) {
    }
}
