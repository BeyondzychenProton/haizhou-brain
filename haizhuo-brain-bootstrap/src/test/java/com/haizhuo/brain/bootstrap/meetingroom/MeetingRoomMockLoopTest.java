package com.haizhuo.brain.bootstrap.meetingroom;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.infrastructure.meetingroom.InMemoryMeetingRoomRunStore;
import com.haizhuo.brain.infrastructure.meetingroom.InMemoryMockMeetingRoomSystem;
import com.haizhuo.brain.infrastructure.employee.InMemoryAgentDefinitionRepository;
import com.haizhuo.brain.platform.capability.EffectiveCapabilitySetResolver;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunService;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunStore;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomToolGateway;
import com.haizhuo.brain.runtime.api.AgentRuntime;
import com.haizhuo.brain.runtime.api.event.AgentRunCompletedEvent;
import com.haizhuo.brain.runtime.api.event.AgentRunFailedEvent;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class MeetingRoomMockLoopTest {
    @Test
    void runCallsAuthorizedSearchAndReserveAndReturnsVerifiableBooking() {
        var mock = new InMemoryMockMeetingRoomSystem();
        var store = new InMemoryMeetingRoomRunStore();
        var definitions = new InMemoryAgentDefinitionRepository();
        var resolver = resolver(definitions);
        var gateway = new MeetingRoomToolGateway(mock, store, resolver, definitions);
        AgentRuntime fakeModel = request -> {
            OffsetDateTime start = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime end = start.plusHours(1);
            Map<String, Object> args = Map.of("startAt", start.toString(), "endAt", end.toString(), "attendees", 6);
            String search = gateway.execute(request, "meeting_room.search", args);
            assertTrue(search.contains("available=true"));
            gateway.execute(request, "meeting_room.reserve", args);
            return Flux.just(new AgentRunCompletedEvent(request.runId(), "已完成"));
        };
        var service = new MeetingRoomRunService(fakeModel, mock, store, gateway, Runnable::run, definitions, resolver, 1001L);
        var session = service.createSession();
        var accepted = service.submit(session.sessionId(), "request-1", "预定后天14:00到15:00，6人");
        var result = service.get(accepted.runId());
        assertEquals("SUCCEEDED", result.state());
        assertEquals("BOOKED", result.businessOutcome());
        assertNotNull(result.booking());
        assertEquals(result.booking(), mock.find(result.booking().bookingId()));
        assertTrue(result.answer().contains(result.booking().bookingId()));
    }

    @Test
    void mockIsIdempotentAndRejectsAnOverlappingBooking() {
        var mock = new InMemoryMockMeetingRoomSystem();
        OffsetDateTime start = OffsetDateTime.now().plusDays(2);
        OffsetDateTime end = start.plusHours(1);
        var first = mock.reserve("operation-1", 1001, start, end, 4);
        assertEquals(first, mock.reserve("operation-1", 1001, start, end, 4));
        assertThrows(IllegalStateException.class, () -> mock.reserve("operation-2", 1001, start.plusMinutes(30), end.plusMinutes(30), 4));
        assertThrows(IllegalStateException.class, () -> mock.reserve("operation-1", 1001, start, end, 5));
    }

    @Test
    void modelCannotClaimBookingWithoutACommittedMockResult() {
        var mock = new InMemoryMockMeetingRoomSystem();
        var store = new InMemoryMeetingRoomRunStore();
        var definitions = new InMemoryAgentDefinitionRepository();
        var resolver = resolver(definitions);
        var gateway = new MeetingRoomToolGateway(mock, store, resolver, definitions);
        AgentRuntime falseClaim = request -> Flux.just(new AgentRunCompletedEvent(request.runId(), "已预定，预定编号 BK-FAKE"));
        var service = new MeetingRoomRunService(falseClaim, mock, store, gateway, Runnable::run, definitions, resolver, 1001L);

        var session = service.createSession();
        var accepted = service.submit(session.sessionId(), "request-false-claim", "预定 A-201");
        var result = service.get(accepted.runId());

        assertEquals("SUCCEEDED", result.state());
        assertEquals("NEEDS_INFO", result.businessOutcome());
        assertNull(result.booking());
        assertFalse(result.answer().contains("BK-FAKE"));
        assertTrue(result.answer().contains("没有创建可核验的预定记录"));
    }

    @Test
    void modelFailureDoesNotClaimBooking() {
        var mock = new InMemoryMockMeetingRoomSystem();
        var store = new InMemoryMeetingRoomRunStore();
        var definitions = new InMemoryAgentDefinitionRepository();
        var resolver = resolver(definitions);
        var gateway = new MeetingRoomToolGateway(mock, store, resolver, definitions);
        AgentRuntime failedModel = request -> Flux.just(new AgentRunFailedEvent(request.runId(), "test provider failure"));
        var service = new MeetingRoomRunService(failedModel, mock, store, gateway, Runnable::run, definitions, resolver, 1001L);

        var session = service.createSession();
        var accepted = service.submit(session.sessionId(), "request-model-failure", "预定 A-201");
        var result = service.get(accepted.runId());

        assertEquals("FAILED", result.state());
        assertEquals("UNKNOWN", result.businessOutcome());
        assertNull(result.booking());
        assertTrue(result.answer().contains("检查模型服务状态"));
    }

    @Test
    void nextRunUsesTheNewlyPublishedDefinitionAndOnlyItsSelectedTools() {
        var mock = new InMemoryMockMeetingRoomSystem();
        var store = new InMemoryMeetingRoomRunStore();
        var definitions = new InMemoryAgentDefinitionRepository();
        var resolver = resolver(definitions);
        var gateway = new MeetingRoomToolGateway(mock, store, resolver, definitions);
        var management = new AgentDefinitionManagementService(definitions, (key, code) ->
                "meeting-room-v1".equals(key) && "meeting_room.search".equals(code));
        management.saveDraft(1, 1, new AgentDefinitionManagementService.DraftUpdate("新版本指令", "openai-compatible", "dynamic-test-model",
                List.of(new CapabilitySelection("meeting_room.search", "1"))), 1001);
        management.publish(1, 2, "publish-dynamic-2", 1001);
        AtomicReference<com.haizhuo.brain.runtime.api.model.AgentExecutionRequest> observed = new AtomicReference<>();
        AgentRuntime fakeModel = request -> {
            observed.set(request);
            return Flux.just(new AgentRunCompletedEvent(request.runId(), "本轮只开放查询工具"));
        };
        var service = new MeetingRoomRunService(fakeModel, mock, store, gateway, Runnable::run, definitions, resolver, 1001L);

        var session = service.createSession();
        var run = service.submit(session.sessionId(), "dynamic-definition", "查询会议室");
        var request = observed.get();

        assertEquals(2, request.definitionVersionId());
        assertEquals("新版本指令", request.instructions());
        assertEquals("dynamic-test-model", request.modelName());
        assertEquals(List.of("meeting_room.search"), request.capabilities().stream().map(c -> c.referenceId()).toList());
        assertTrue(definitions.findEffectiveCapabilitySet(run.runId()).isPresent());
    }

    @Test
    void revokedGrantIsDeniedAtTheToolExecutionBoundaryAndAudited() {
        var mock = new InMemoryMockMeetingRoomSystem();
        var store = new InMemoryMeetingRoomRunStore();
        var definitions = new InMemoryAgentDefinitionRepository();
        var resolver = resolver(definitions);
        var gateway = new MeetingRoomToolGateway(mock, store, resolver, definitions);
        AgentRuntime fakeModel = request -> {
            OffsetDateTime start = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(2).withHour(14).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime end = start.plusHours(1);
            Map<String, Object> args = Map.of("startAt", start.toString(), "endAt", end.toString(), "attendees", 3);
            gateway.execute(request, "meeting_room.search", args);
            definitions.setUserCapabilityGrant(1001, "meeting_room.reserve", false, 1001);
            assertThrows(SecurityException.class, () -> gateway.execute(request, "meeting_room.reserve", args));
            return Flux.just(new AgentRunCompletedEvent(request.runId(), "已停止"));
        };
        var service = new MeetingRoomRunService(fakeModel, mock, store, gateway, Runnable::run, definitions, resolver, 1001L);

        var session = service.createSession();
        var result = service.get(service.submit(session.sessionId(), "revoked-grant", "预定会议室").runId());

        assertNull(result.booking());
        assertEquals("DENIED", result.businessOutcome());
        assertEquals("DENY", definitions.recordedAudits().stream().filter(a -> "meeting_room.reserve".equals(a.capabilityCode()))
                .map(a -> a.decision()).findFirst().orElseThrow());
    }

    private static EffectiveCapabilitySetResolver resolver(InMemoryAgentDefinitionRepository definitions) {
        return new EffectiveCapabilitySetResolver(definitions, (implementationKey, capabilityCode) ->
                "meeting-room-v1".equals(implementationKey)
                        && ("meeting_room.search".equals(capabilityCode) || "meeting_room.reserve".equals(capabilityCode)));
    }
}
