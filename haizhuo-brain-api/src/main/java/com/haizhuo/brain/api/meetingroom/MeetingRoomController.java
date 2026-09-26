package com.haizhuo.brain.api.meetingroom;

import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunService;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("meeting-mock | test")
public class MeetingRoomController {
    private final MeetingRoomRunService runs;
    private final MeetingRoomSystem rooms;
    private final long testUserId;
    public MeetingRoomController(MeetingRoomRunService runs, MeetingRoomSystem rooms,
                                 @Value("${haizhuo.brain.meeting-room.test-user-id:1001}") long testUserId) {
        this.runs = runs; this.rooms = rooms; this.testUserId = testUserId;
    }

    @PostMapping("/api/v1/sessions")
    public MeetingRoomRunService.SessionView createSession() { return runs.createSession(); }

    @PostMapping("/api/v1/sessions/{sessionId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MeetingRoomRunService.RunView submit(@PathVariable String sessionId, @Valid @RequestBody SubmitRunRequest request) {
        return runs.submit(sessionId, request.clientRequestId(), request.message());
    }

    @GetMapping("/api/v1/runs/{runId}")
    public MeetingRoomRunService.RunView getRun(@PathVariable String runId) { return runs.get(runId); }

    /** Local verification of the mock outcome; booking writes only pass through the Agent tool gateway. */
    @GetMapping("/mock/bookings/{bookingId}")
    public MeetingRoomSystem.Booking getMockBooking(@PathVariable String bookingId) {
        return findBooking(bookingId);
    }

    @GetMapping("/mock/api/v1/rooms/{roomId}/availability")
    public AvailabilityResponse availability(@PathVariable String roomId,
            @RequestParam OffsetDateTime startAt, @RequestParam OffsetDateTime endAt,
            @RequestParam int attendees) {
        if (!"A-201".equals(roomId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mock room not found");
        if (attendees < 1) throw new IllegalArgumentException("attendees must be positive");
        if (startAt == null || endAt == null || !endAt.isAfter(startAt) || !startAt.isAfter(OffsetDateTime.now()))
            throw new IllegalArgumentException("provide a future startAt and an endAt after it");
        var inspected = rooms.inspect(startAt, endAt);
        return new AvailabilityResponse(inspected.roomId(), inspected.capacity(), inspected.projector(),
                inspected.available() && attendees <= inspected.capacity(), inspected.monitorSummary(),
                inspected.observedAt(), "MOCK");
    }

    @PostMapping("/mock/api/v1/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingRoomSystem.Booking createMockBooking(@RequestHeader("Idempotency-Key") String operationKey,
            @Valid @RequestBody MockBookingRequest request) {
        if (!"A-201".equals(request.roomId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mock room not found");
        if (request.attendees() < 1) throw new IllegalArgumentException("attendees must be positive");
        if (request.startAt() == null || request.endAt() == null || !request.endAt().isAfter(request.startAt())
                || !request.startAt().isAfter(OffsetDateTime.now()))
            throw new IllegalArgumentException("provide a future startAt and an endAt after it");
        return rooms.reserve(operationKey, testUserId, request.startAt(), request.endAt(), request.attendees());
    }

    @GetMapping("/mock/api/v1/bookings/{bookingId}")
    public MeetingRoomSystem.Booking getMockBookingV1(@PathVariable String bookingId) {
        return findBooking(bookingId);
    }

    private MeetingRoomSystem.Booking findBooking(String bookingId) {
        var booking = rooms.find(bookingId);
        if (booking == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mock booking not found");
        return booking;
    }

    public record SubmitRunRequest(@NotBlank String clientRequestId, @NotBlank String message) {}
    public record AvailabilityResponse(String roomId, int capacity, boolean projector, boolean available,
                                       String monitorSummary, OffsetDateTime observedAt, String dataSource) {}
    public record MockBookingRequest(@NotBlank String roomId, @NotNull OffsetDateTime startAt,
                                    @NotNull OffsetDateTime endAt, int attendees) {}
}
