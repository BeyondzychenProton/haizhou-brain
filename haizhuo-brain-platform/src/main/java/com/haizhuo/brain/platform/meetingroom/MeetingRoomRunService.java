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
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Meeting-room Run orchestration. Session, Run and result facts are delegated to a durable store. */
public class MeetingRoomRunService {
    private final AgentRuntime runtime;
    private final MeetingRoomSystem rooms;
    private final MeetingRoomRunStore store;
    private final MeetingRoomToolGateway toolGateway;
    private final Executor executor;
    private final String modelProvider;
    private final String primaryModel;
    private final long testUserId;

    public MeetingRoomRunService(AgentRuntime runtime, MeetingRoomSystem rooms, MeetingRoomRunStore store,
                                 MeetingRoomToolGateway toolGateway, Executor meetingRunExecutor,
                                 String modelProvider, String primaryModel, long testUserId) {
        this.runtime = runtime;
        this.rooms = rooms;
        this.store = store;
        this.toolGateway = toolGateway;
        this.executor = meetingRunExecutor;
        this.modelProvider = modelProvider;
        this.primaryModel = primaryModel;
        this.testUserId = testUserId;
    }

    public SessionView createSession() {
        MeetingRoomRunStore.SessionRecord session = store.createSession(testUserId);
        return new SessionView(session.sessionId());
    }

    public RunView submit(String sessionId, String clientRequestId, String message) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128)
            throw new IllegalArgumentException("clientRequestId is required and must be at most 128 characters");
        if (message == null || message.isBlank() || message.length() > 8000)
            throw new IllegalArgumentException("message is required and must be at most 8000 characters");
        MeetingRoomRunStore.AcceptedRun accepted = store.acceptRun(testUserId, sessionId, clientRequestId, message);
        if (accepted.created()) schedule(accepted.run());
        return toView(accepted.run());
    }

    public RunView get(String runId) {
        return store.findRun(testUserId, runId).map(MeetingRoomRunService::toView)
                .orElseThrow(() -> new IllegalArgumentException("Run not found"));
    }

    /** Called once after application startup: resumes not-yet-started work and resolves interrupted work safely. */
    public void recoverAfterRestart() {
        for (MeetingRoomRunStore.RunRecord run : store.findRecoverableRuns()) {
            if ("ACCEPTED".equals(run.state())) {
                schedule(run);
                continue;
            }
            MeetingRoomSystem.Booking booking = rooms.findByOperationKey(operationKey(run.runId()));
            if (booking != null) {
                String answer = bookingAnswer(booking);
                store.complete(run.runId(), "BOOKED", answer, booking);
            } else {
                store.fail(run.runId(), "UNKNOWN", "服务在执行期间重启；系统核验后未发现已提交的 Mock 预定，本次请求已结束。", null);
            }
        }
    }

    private void schedule(MeetingRoomRunStore.RunRecord accepted) {
        try {
            executor.execute(() -> execute(accepted));
        } catch (RejectedExecutionException rejected) {
            store.fail(accepted.runId(), "UNKNOWN", "当前执行队列繁忙，请稍后使用新的请求编号重试。", null);
        }
    }

    private void execute(MeetingRoomRunStore.RunRecord accepted) {
        try {
            store.markRunning(accepted.runId());
            var request = new AgentExecutionRequest(new TenantId(1), new UserId(testUserId), new SessionId(accepted.sessionId()),
                    new RunId(accepted.runId()), TraceId.newId(), 1L, "会议室预定员工",
                    "你是会议室预定员工。本轮只处理固定会议室 A-201，时间按 Asia/Shanghai 解释。只接受明确的未来日期、开始时间、结束时间和正整数参会人数；如果缺少任何条件、时间含糊、用户要多个预定或提出当前 Mock 不支持的条件，先简洁说明缺少什么或不支持什么，不调用预定工具。条件完整时必须先调用 meeting_room_search；仅当工具明确返回 available=true 且人数在容量内时，才可调用 meeting_room_reserve。预定结果以工具返回为准，不能声称未返回的 bookingId 或预定成功。",
                    modelProvider, primaryModel,
                    List.of(new RuntimeCapability("tool", "meeting_room.search", "1"), new RuntimeCapability("tool", "meeting_room.reserve", "1")),
                    accepted.message());

            String modelAnswer = null;
            for (BrainAgentEvent event : runtime.execute(request).toIterable()) {
                if (event instanceof AgentRunFailedEvent) throw new AgentRunFailure();
                if (event instanceof AgentRunCompletedEvent completed) modelAnswer = completed.result();
            }
            MeetingRoomSystem.Booking booking = rooms.findByOperationKey(operationKey(accepted.runId()));
            if (booking != null) {
                store.complete(accepted.runId(), "BOOKED", bookingAnswer(booking), booking);
            } else {
                String outcome = toolGateway.businessOutcome(request);
                String answer = switch (outcome) {
                    case "UNAVAILABLE" -> "A-201 在所需时段不可预定或容量不足，本次没有创建预定。";
                    case "DENIED" -> "本次运行未获得执行该会议室动作的权限，没有创建预定。";
                    default -> noBookingAnswer(modelAnswer);
                };
                store.complete(accepted.runId(), outcome, answer, null);
            }
        } catch (Exception failure) {
            MeetingRoomSystem.Booking booking = rooms.findByOperationKey(operationKey(accepted.runId()));
            if (booking != null) {
                store.complete(accepted.runId(), "BOOKED", bookingAnswer(booking), booking);
            } else {
                store.fail(accepted.runId(), "UNKNOWN", safeFailure(failure), null);
            }
        } finally {
            toolGateway.finishRequest(accepted.runId());
        }
    }

    private static String operationKey(String runId) { return "run:" + runId; }

    private static String bookingAnswer(MeetingRoomSystem.Booking booking) {
        return "已通过模拟会议室系统预定：" + booking.roomId() + "，" + booking.startAt() + " 至 " + booking.endAt()
                + "，" + booking.attendees() + "人。模拟预定编号 " + booking.bookingId() + "。";
    }

    private static String noBookingAnswer(String modelAnswer) {
        if (modelAnswer == null || modelAnswer.isBlank())
            return "本次尚未创建预定。请提供明确的未来日期、开始时间、结束时间和参会人数。";
        if (modelAnswer.matches("(?is).*(已预定|已经预定|预定成功|成功预定|预定编号|booking\\s*(?:id|number)|reservation\\s+(?:is\\s+)?confirmed).*"))
            return "本次没有创建可核验的预定记录。模型答复未获得会议室系统确认，请补充条件后重新查询。";
        return "本次尚未创建预定。\n" + modelAnswer;
    }

    private static String safeFailure(Exception failure) {
        if (failure instanceof SecurityException) return "本次运行未获准执行相应动作，未确认生成预定。";
        if (failure instanceof AgentRunFailure) return "数字员工执行失败，请检查模型服务状态和本地模型配置。";
        return "会议室预定流程执行失败；系统未确认生成预定，请查询 Run 状态后再决定是否重试。";
    }

    private static RunView toView(MeetingRoomRunStore.RunRecord run) {
        return new RunView(run.runId(), run.sessionId(), run.state(), run.businessOutcome(), run.answer(),
                run.booking(), run.message(), run.createdAt());
    }

    private static final class AgentRunFailure extends RuntimeException {}

    public record SessionView(String sessionId) {}
    public record RunView(String runId, String sessionId, String state, String businessOutcome,
                          String answer, MeetingRoomSystem.Booking booking, String message, Instant createdAt) {}
}
