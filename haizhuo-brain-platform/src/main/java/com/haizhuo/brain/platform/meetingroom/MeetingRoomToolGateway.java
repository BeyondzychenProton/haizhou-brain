package com.haizhuo.brain.platform.meetingroom;

import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import com.haizhuo.brain.platform.capability.CapabilityRunAuthorizer;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.ToolInvocationAudit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Validates every model tool call and delegates to the configured meeting-room adapter. */
public class MeetingRoomToolGateway implements ToolExecutionGateway {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MeetingRoomSystem system;
    private final MeetingRoomRunStore runStore;
    private final CapabilityRunAuthorizer authorizer;
    private final AgentDefinitionRepository definitions;
    private final Map<String, QueryReceipt> successfulQueries = new ConcurrentHashMap<>();
    private final Map<String, String> outcomes = new ConcurrentHashMap<>();

    public MeetingRoomToolGateway(MeetingRoomSystem system, MeetingRoomRunStore runStore,
                                  CapabilityRunAuthorizer authorizer, AgentDefinitionRepository definitions) {
        this.system = system;
        this.runStore = runStore;
        this.authorizer = authorizer;
        this.definitions = definitions;
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
        RuntimeCapability capability = run.capabilities().stream()
                .filter(item -> capabilityId.equals(item.referenceId())).findFirst().orElse(null);
        if (capability == null || authorizer == null || !authorizer.isAllowedNow(run, capabilityId)) {
            outcomes.put(run.runId().value(), "DENIED");
            record(run, "TOOL_DENIED", "本次运行未获准使用会议室动作");
            if (capability != null) audit(run, capability, args, "DENY", "POLICY_DENIED", "执行前权限复核未通过");
            throw new SecurityException("Run is not granted capability " + capabilityId);
        }
        try {
            String result = executeAuthorized(run, capabilityId, args);
            audit(run, capability, args, "ALLOW", "SUCCEEDED", "工具动作执行完成");
            return result;
        } catch (RuntimeException failure) {
            audit(run, capability, args, "ALLOW", "FAILED", "工具动作执行失败");
            throw failure;
        }
    }

    private String executeAuthorized(AgentExecutionRequest run, String capabilityId, Map<String, Object> args) {
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
    private void audit(AgentExecutionRequest run, RuntimeCapability capability, Map<String, Object> arguments,
                       String decision, String resultStatus, String resultSummary) {
        // 中文注释：审计只写参数哈希和结果摘要，避免把会议信息原文扩散到审计表。
        definitions.recordToolInvocation(new ToolInvocationAudit(UUID.randomUUID().toString(), run.runId().value(),
                capability.referenceId(), capability.revision(), run.userId().value(), capability.businessAction(),
                argumentsHash(arguments), decision, resultStatus, resultSummary, Instant.now()));
    }
    private static String argumentsHash(Map<String, Object> arguments) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new TreeMap<>(arguments).toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Could not hash tool arguments", e); }
    }
    private record QueryReceipt(OffsetDateTime startAt, OffsetDateTime endAt, int attendees, boolean available) {}
    private record AvailabilityView(MeetingRoomSystem.Availability availability, int attendees) {
        @Override public String toString() {
            var a = availability;
            return "room=" + a.roomId() + ", capacity=" + a.capacity() + ", attendees=" + attendees + ", projector=" + a.projector() + ", available=" + (a.available() && attendees <= a.capacity()) + ", monitor=" + a.monitorSummary() + ", observedAt=" + a.observedAt();
        }
    }
}
