package com.haizhuo.brain.platform.meetingroom;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.kernel.identity.TraceId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.capability.EffectiveCapabilitySet;
import com.haizhuo.brain.platform.capability.EffectiveCapabilitySetResolver;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.platform.employee.PublishedEmployee;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import com.haizhuo.brain.runtime.api.event.BrainAgentEvent;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Meeting-room Run orchestration. Session, Run and result facts are delegated to a durable store. */
public class MeetingRoomRunService {
    private final AgentRuntime runtime;
    private final MeetingRoomSystem rooms;
    private final MeetingRoomRunStore store;
    private final MeetingRoomToolGateway toolGateway;
    private final AgentDefinitionRepository definitions;
    private final EffectiveCapabilitySetResolver capabilityResolver;
    private final Executor executor;
    private final long tenantId;
    private final long employeeId;
    private final long testUserId;

    public MeetingRoomRunService(AgentRuntime runtime, MeetingRoomSystem rooms, MeetingRoomRunStore store,
                                 MeetingRoomToolGateway toolGateway, Executor meetingRunExecutor,
                                 AgentDefinitionRepository definitions, EffectiveCapabilitySetResolver capabilityResolver,
                                 long testUserId) {
        this(runtime, rooms, store, toolGateway, meetingRunExecutor, definitions, capabilityResolver, 1L, 1L, testUserId);
    }

    public MeetingRoomRunService(AgentRuntime runtime, MeetingRoomSystem rooms, MeetingRoomRunStore store,
                                 MeetingRoomToolGateway toolGateway, Executor meetingRunExecutor,
                                 AgentDefinitionRepository definitions, EffectiveCapabilitySetResolver capabilityResolver,
                                 long tenantId, long employeeId, long testUserId) {
        this.runtime = runtime;
        this.rooms = rooms;
        this.store = store;
        this.toolGateway = toolGateway;
        this.executor = meetingRunExecutor;
        this.definitions = definitions;
        this.capabilityResolver = capabilityResolver;
        this.tenantId = tenantId;
        this.employeeId = employeeId;
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
            // 中文注释：Run 启动时固定读取当前发布版本，并把用户权限交集持久化为该 Run 的能力快照。
            PublishedEmployee published = definitions.findPublished(new TenantId(tenantId), employeeId)
                    .orElseThrow(() -> new IllegalStateException("No enabled published Agent definition is available"));
            EffectiveCapabilitySet effective = capabilityResolver.resolve(new RunId(accepted.runId()),
                    new UserId(testUserId), published);
            var request = new AgentExecutionRequest(new TenantId(tenantId), new UserId(testUserId), new SessionId(accepted.sessionId()),
                    new RunId(accepted.runId()), TraceId.newId(), published.definition().id(), published.employee().displayName(),
                    published.definition().instructions(), published.definition().modelProvider(), published.definition().modelName(),
                    effective.snapshotHash(), effective.allowedCapabilities(), accepted.message());

            String modelAnswer = null;
            for (BrainAgentEvent event : runtime.execute(request).toIterable()) {
                if (event instanceof AgentRunFailedEvent failed) {
                    store.appendEvent(accepted.runId(), "AGENT_EXECUTION_FAILED", failed.message());
                    throw new AgentRunFailure(failed.message());
                }
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
        if (failure instanceof AgentRunFailure runFailure) return "数字员工执行失败，请检查模型服务状态和本地模型配置。" +
                (runFailure.diagnostic == null ? "" : "（" + runFailure.diagnostic + "）");
        return "会议室预定流程执行失败；系统未确认生成预定，请查询 Run 状态后再决定是否重试。";
    }

    private static RunView toView(MeetingRoomRunStore.RunRecord run) {
        return new RunView(run.runId(), run.sessionId(), run.state(), run.businessOutcome(), run.answer(),
                run.booking(), run.message(), run.createdAt());
    }

    private static final class AgentRunFailure extends RuntimeException {
        private final String diagnostic;
        private AgentRunFailure(String diagnostic) { this.diagnostic = diagnostic; }
    }

    public record SessionView(String sessionId) {}
    public record RunView(String runId, String sessionId, String state, String businessOutcome,
                          String answer, MeetingRoomSystem.Booking booking, String message, Instant createdAt) {}
}
