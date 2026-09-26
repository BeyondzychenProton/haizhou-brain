package com.haizhuo.brain.platform.meetingroom;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable boundary for the local meeting-room workflow. */
public interface MeetingRoomRunStore {
    SessionRecord createSession(long userId);

    AcceptedRun acceptRun(long userId, String sessionId, String clientRequestId, String message);

    Optional<RunRecord> findRun(long userId, String runId);

    void markRunning(String runId);

    void appendEvent(String runId, String type, String summary);

    void complete(String runId, String outcome, String answer, MeetingRoomSystem.Booking booking);

    void fail(String runId, String outcome, String answer, MeetingRoomSystem.Booking booking);

    List<RunRecord> findRecoverableRuns();

    record SessionRecord(String sessionId, long userId, Instant createdAt) {}

    record AcceptedRun(RunRecord run, boolean created) {}

    record RunRecord(String runId, String sessionId, long userId, String state, String businessOutcome,
                     String answer, MeetingRoomSystem.Booking booking, String message, Instant createdAt) {}
}
