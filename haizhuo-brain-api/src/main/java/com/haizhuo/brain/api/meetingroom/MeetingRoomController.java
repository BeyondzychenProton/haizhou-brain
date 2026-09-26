package com.haizhuo.brain.api.meetingroom;

import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunService;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("meeting-mock | test")
public class MeetingRoomController {
    private final MeetingRoomRunService runs;
    private final MeetingRoomSystem rooms;
    public MeetingRoomController(MeetingRoomRunService runs, MeetingRoomSystem rooms) { this.runs = runs; this.rooms = rooms; }

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
        var booking = rooms.find(bookingId);
        if (booking == null) throw new IllegalArgumentException("Mock booking not found");
        return booking;
    }

    public record SubmitRunRequest(@NotBlank String clientRequestId, @NotBlank String message) {}
}
