package com.haizhuo.brain.platform.meetingroom;

import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Validates every model tool call and delegates to the configured meeting-room adapter. */
public class MeetingRoomToolGateway implements ToolExecutionGateway {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MeetingRoomSystem system;
    private final MeetingRoomRunStore runStore;
    private final Map<String, QueryReceipt> successfulQueries = new ConcurrentHashMap<>();
    private final Map<String, String> outcomes = new ConcurrentHashMap<>();

    public MeetingRoomToolGateway(MeetingRoomSystem system) { this(system, null); }

    public MeetingRoomToolGateway(MeetingRoomSystem system, MeetingRoomRunStore runStore) {
        this.system = system;
        this.runStore = runStore;
    }

    @Override public void finish(AgentExecutionRequest run) {
        finishRequest(run.runId().value());
    }

    public void finishRequest(String runId) {
        successfulQueries.remove(runId);
        outcomes.remove(runId);
    }

    public String businessOutcome(AgentExecutionRequest run) {
        String outcome = outcomes.get(run.runId().value());
        if (outcome == null || "SEARCHED_AVAILABLE".equals(outcome)) return "NEEDS_INFO";
        return outcome;
    }

    @Override public String execute(AgentExecutionRequest run, String capabilityId, Map<String, Object> args) {
        boolean granted = run.capabilities().stream().anyMatch(c -> capabilityId.equals(c.referenceId()));
        if (!granted) {
            outcomes.put(run.runId().value(), "DENIED");
            record(run, "TOOL_DENIED", "本次运行未获准使用会议室动作");
            throw new SecurityException("Run is not granted capability " + capabilityId);
        }
        OffsetDateTime start = parse(args.get("startAt"));
        OffsetDateTime end = parse(args.get("endAt"));
        if (!end.isAfter(start) || !start.isAfter(OffsetDateTime.now(ZONE))) throw new IllegalArgumentException("Provide a future startAt and an endAt after it");
        if ("meeting_room.search".equals(capabilityId)) {
            int attendees = integer(args.get("attendees"));
            if (attendees < 1) throw new IllegalArgumentException("attendees must be positive");
            AvailabilityView view = new AvailabilityView(system.inspect(start, end), attendees);
            boolean available = view.availability().available() && attendees <= view.availability().capacity();
            successfulQueries.put(run.runId().value(), new QueryReceipt(start, end, attendees, available));
            outcomes.put(run.runId().value(), available ? "SEARCHED_AVAILABLE" : "UNAVAILABLE");
            record(run, available ? "ROOM_SEARCH_AVAILABLE" : "ROOM_SEARCH_UNAVAILABLE",
                    available ? "A-201 目标时段可预定" : "A-201 目标时段不可预定或容量不足");
            return view.toString();
        }
        if ("meeting_room.reserve".equals(capabilityId)) {
            int attendees = integer(args.get("attendees"));
            if (attendees > 8) throw new IllegalArgumentException("A-201 capacity is 8");
            QueryReceipt receipt = successfulQueries.get(run.runId().value());
            if (receipt == null || !receipt.available() || !receipt.startAt().equals(start) || !receipt.endAt().equals(end) || receipt.attendees() != attendees) {
                outcomes.put(run.runId().value(), "DENIED");
                record(run, "ROOM_RESERVATION_REJECTED", "预定参数未通过查询结果校验");
                throw new IllegalStateException("A successful availability query for the same time and attendee count is required before reservation");
            }
            MeetingRoomSystem.Booking booking;
            try {
                booking = system.reserve("run:" + run.runId().value(), run.userId().value(), start, end, attendees);
            } catch (IllegalStateException conflict) {
                outcomes.put(run.runId().value(), "UNAVAILABLE");
                record(run, "ROOM_RESERVATION_REJECTED", "Mock 会议室系统拒绝预定：时段冲突或幂等参数冲突");
                throw conflict;
            }
            successfulQueries.remove(run.runId().value());
            outcomes.put(run.runId().value(), "BOOKED");
            record(run, "ROOM_RESERVED", "A-201 Mock 预定已写入");
            return "BOOKED bookingId=" + booking.bookingId() + " room=" + booking.roomId() + " startAt=" + booking.startAt() + " endAt=" + booking.endAt() + " attendees=" + booking.attendees();
        }
        throw new SecurityException("Unsupported capability " + capabilityId);
    }

    private static int integer(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }
    private static OffsetDateTime parse(Object value) {
        String text = String.valueOf(value);
        try { return OffsetDateTime.parse(text); }
        catch (Exception ignored) { return java.time.LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZONE).toOffsetDateTime(); }
    }

    private void record(AgentExecutionRequest run, String type, String summary) {
        if (runStore != null) runStore.appendEvent(run.runId().value(), type, summary);
    }
    private record QueryReceipt(OffsetDateTime startAt, OffsetDateTime endAt, int attendees, boolean available) {}
    private record AvailabilityView(MeetingRoomSystem.Availability availability, int attendees) {
        @Override public String toString() {
            var a = availability;
            return "room=" + a.roomId() + ", capacity=" + a.capacity() + ", attendees=" + attendees + ", projector=" + a.projector() + ", available=" + (a.available() && attendees <= a.capacity()) + ", monitor=" + a.monitorSummary() + ", observedAt=" + a.observedAt();
        }
    }
}
