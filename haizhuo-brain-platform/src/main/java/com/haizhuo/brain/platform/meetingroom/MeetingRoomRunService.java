package com.haizhuo.brain.platform.meetingroom;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Volatile single-process Session/Run implementation for the meeting-mock profile. */
public class MeetingRoomRunService {
    private static final UserId TEST_USER = new UserId(1001);
    private final AgentRuntime runtime;
    private final MeetingRoomSystem rooms;
    private final ToolExecutionGateway toolGateway;
    private final Executor executor;
    private final String primaryModel;
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final Map<String, RunView> runs = new ConcurrentHashMap<>();
    private final Map<String, DedupEntry> requestDedup = new ConcurrentHashMap<>();
    private final java.util.Set<String> activeSessions = ConcurrentHashMap.newKeySet();

    public MeetingRoomRunService(AgentRuntime runtime, MeetingRoomSystem rooms, ToolExecutionGateway toolGateway,
                                 Executor meetingRunExecutor,
                                 String primaryModel) {
        this.runtime = runtime; this.rooms = rooms; this.toolGateway = toolGateway; this.executor = meetingRunExecutor; this.primaryModel = primaryModel;
    }

    public SessionView createSession() {
        SessionId id = SessionId.newId(); sessions.put(id.value(), Long.toString(TEST_USER.value()));
        return new SessionView(id.value());
    }

    public RunView submit(String sessionId, String clientRequestId, String message) {
        if (!String.valueOf(TEST_USER.value()).equals(sessions.get(sessionId))) throw new IllegalArgumentException("Session not found");
        if (clientRequestId == null || clientRequestId.isBlank() || message == null || message.isBlank()) throw new IllegalArgumentException("clientRequestId and message are required");
        String key = TEST_USER.value() + ":" + clientRequestId;
        String digest = sessionId + "\n" + message.trim();
        synchronized (requestDedup) {
            DedupEntry existing = requestDedup.get(key);
            if (existing != null) {
                if (!existing.digest().equals(digest)) throw new IllegalStateException("clientRequestId was already used with different content");
                return runs.get(existing.runId());
            }
            if (!activeSessions.add(sessionId)) throw new IllegalStateException("Session already has an active Run");
            RunId id = RunId.newId();
            RunView accepted = new RunView(id.value(), sessionId, "ACCEPTED", "PENDING", null, null, message.trim());
            runs.put(id.value(), accepted);
            requestDedup.put(key, new DedupEntry(digest, id.value()));
            try { executor.execute(() -> execute(accepted)); }
            catch (RuntimeException rejected) { activeSessions.remove(sessionId); runs.remove(id.value()); requestDedup.remove(key); throw rejected; }
            return accepted;
        }
    }

    public RunView get(String runId) {
        RunView view = runs.get(runId);
        if (view == null) throw new IllegalArgumentException("Run not found");
        return view;
    }

    private void execute(RunView accepted) {
        RunView running = new RunView(accepted.runId(), accepted.sessionId(), "RUNNING", "PENDING", null, null, accepted.message());
        runs.put(accepted.runId(), running);
        var request = new AgentExecutionRequest(new TenantId(1), TEST_USER, new SessionId(accepted.sessionId()),
                new RunId(accepted.runId()), TraceId.newId(), 1L, "会议室预定员工",
                "你是会议室预定员工。首版只处理固定会议室 A-201。先从用户消息中识别明确的未来日期、开始时间、结束时间和正整数人数；任一缺失或不明确时，直接向用户说明缺什么，不调用工具。参数齐全时先调用 meeting_room_search，只有 available=true 且容量满足时再调用 meeting_room_reserve。不得声称工具未返回的预定成功。时间使用 ISO-8601 日期时间。",
                "dashscope", primaryModel, List.of(new RuntimeCapability("tool", "meeting_room.search", "1"), new RuntimeCapability("tool", "meeting_room.reserve", "1")), accepted.message());
        try {
            String answer = null;
            for (BrainAgentEvent event : runtime.execute(request).toIterable()) {
                if (event instanceof AgentRunFailedEvent failed) throw new IllegalStateException(failed.message());
                if (event instanceof AgentRunCompletedEvent completed) answer = completed.result();
            }
            MeetingRoomSystem.Booking booking = rooms.findByOperationKey("run:" + accepted.runId());
            String outcome = booking == null ? "NOT_BOOKED" : "BOOKED";
            if (booking != null) answer = "已通过模拟会议室系统预定：A-201，" + booking.startAt() + " 至 " + booking.endAt() + "，" + booking.attendees() + "人。模拟预定编号 " + booking.bookingId() + "。";
            runs.put(accepted.runId(), new RunView(accepted.runId(), accepted.sessionId(), "SUCCEEDED", outcome, answer, booking, accepted.message()));
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            runs.put(accepted.runId(), new RunView(accepted.runId(), accepted.sessionId(), "FAILED", "UNKNOWN", "执行失败：" + detail, rooms.findByOperationKey("run:" + accepted.runId()), accepted.message()));
        } finally {
            toolGateway.finish(request);
            activeSessions.remove(accepted.sessionId());
        }
    }

    public record SessionView(String sessionId) {}
    private record DedupEntry(String digest, String runId) {}
    public record RunView(String runId, String sessionId, String state, String businessOutcome,
                          String answer, MeetingRoomSystem.Booking booking, String message) {}
}
