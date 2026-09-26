package com.haizhuo.brain.infrastructure.meetingroom;

import com.haizhuo.brain.platform.meetingroom.MeetingRoomRunStore;
import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Lightweight store used only by tests so test contexts never touch a developer database. */
@Repository
@Profile("test")
public class InMemoryMeetingRoomRunStore implements MeetingRoomRunStore {
    private final Map<String, SessionRecord> sessions = new HashMap<>();
    private final Map<String, MutableRun> runs = new HashMap<>();
    private final Map<String, String> requests = new HashMap<>();

    @Override public synchronized SessionRecord createSession(long userId) {
        SessionRecord session = new SessionRecord(UUID.randomUUID().toString(), userId, Instant.now());
        sessions.put(session.sessionId(), session);
        return session;
    }

    @Override public synchronized AcceptedRun acceptRun(long userId, String sessionId, String clientRequestId, String message) {
        SessionRecord session = sessions.get(sessionId);
        if (session == null || session.userId() != userId) throw new IllegalArgumentException("Session not found");
        String digest = sessionId + "\n" + message.trim();
        String priorId = requests.get(userId + ":" + clientRequestId);
        if (priorId != null) {
            MutableRun prior = runs.get(priorId);
            if (!prior.digest.equals(digest)) throw new IllegalStateException("clientRequestId was already used with different content");
            return new AcceptedRun(prior.view(), false);
        }
        if (sessionHasActiveRun(sessionId)) throw new IllegalStateException("Session already has an active Run");
        MutableRun run = new MutableRun(UUID.randomUUID().toString(), sessionId, userId, clientRequestId, digest, message.trim());
        runs.put(run.runId, run);
        requests.put(userId + ":" + clientRequestId, run.runId);
        return new AcceptedRun(run.view(), true);
    }

    @Override public synchronized Optional<RunRecord> findRun(long userId, String runId) {
        MutableRun run = runs.get(runId);
        return run == null || run.userId != userId ? Optional.empty() : Optional.of(run.view());
    }

    @Override public synchronized void markRunning(String runId) {
        MutableRun run = require(runId);
        if ("ACCEPTED".equals(run.state)) run.state = "RUNNING";
    }

    @Override public synchronized void appendEvent(String runId, String type, String summary) { require(runId); }

    @Override public synchronized void complete(String runId, String outcome, String answer, MeetingRoomSystem.Booking booking) {
        MutableRun run = require(runId);
        run.state = "SUCCEEDED";
        run.businessOutcome = outcome;
        run.answer = answer;
        run.booking = booking;
        run.sessionActive = false;
    }

    @Override public synchronized void fail(String runId, String outcome, String answer, MeetingRoomSystem.Booking booking) {
        MutableRun run = require(runId);
        run.state = "FAILED";
        run.businessOutcome = outcome;
        run.answer = answer;
        run.booking = booking;
        run.sessionActive = false;
    }

    @Override public synchronized List<RunRecord> findRecoverableRuns() {
        List<RunRecord> result = new ArrayList<>();
        for (MutableRun run : runs.values()) if ("ACCEPTED".equals(run.state) || "RUNNING".equals(run.state)) result.add(run.view());
        return result;
    }

    private boolean sessionHasActiveRun(String sessionId) {
        return runs.values().stream().anyMatch(run -> sessionId.equals(run.sessionId) && run.sessionActive);
    }

    private MutableRun require(String runId) {
        MutableRun run = runs.get(runId);
        if (run == null) throw new IllegalArgumentException("Run not found");
        return run;
    }

    private static final class MutableRun {
        private final String runId;
        private final String sessionId;
        private final long userId;
        private final String clientRequestId;
        private final String digest;
        private final String message;
        private final Instant createdAt = Instant.now();
        private String state = "ACCEPTED";
        private String businessOutcome = "PENDING";
        private String answer;
        private MeetingRoomSystem.Booking booking;
        private boolean sessionActive = true;

        private MutableRun(String runId, String sessionId, long userId, String clientRequestId, String digest, String message) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.clientRequestId = clientRequestId;
            this.digest = digest;
            this.message = message;
        }

        private RunRecord view() {
            return new RunRecord(runId, sessionId, userId, state, businessOutcome, answer, booking, message, createdAt);
        }
    }
}
