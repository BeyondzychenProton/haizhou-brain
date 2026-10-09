package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.session.SessionHistoryNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 管理持久化的用户反馈；观测数据导出有意放在此服务之外。 */
public final class RunFeedbackService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private final SessionApplicationService sessions;
    private final AgentResultRepository results;
    private final RunFeedbackRepository feedback;
    private final Clock clock;

    public RunFeedbackService(SessionApplicationService sessions, AgentResultRepository results,
                              RunFeedbackRepository feedback, Clock clock) {
        this.sessions = sessions;
        this.results = results;
        this.feedback = feedback;
        this.clock = clock;
    }

    public Submission submit(UserId owner, String runIdValue, String clientRequestId,
                             double value, String comment) {
        RunId runId = parseRunId(runIdValue);
        requireOwnedRun(owner, runId);
        requireEligibleResult(owner, runId);
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128)
            throw new IllegalArgumentException("clientRequestId must contain 1 to 128 characters");
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("value must be between 0 and 1");
        if (comment != null && comment.length() > 1000) throw new IllegalArgumentException("comment is too long");

        String digest = requestDigest(runId, value, comment);
        RunFeedback proposed = new RunFeedback(UUID.randomUUID().toString(), runId, owner, clientRequestId,
                digest, value, comment, clock.instant().truncatedTo(ChronoUnit.MILLIS),
                RunFeedback.ExportDisposition.QUEUE_STATUS_UNKNOWN);
        RunFeedbackRepository.SaveResult saved = feedback.saveOrGet(proposed);
        if (!saved.feedback().requestDigest().equals(digest))
            throw new IllegalStateException("clientRequestId is already used for different feedback");
        return new Submission(saved.feedback(), saved.created());
    }

    public Page list(UserId owner, String runIdValue, String cursor, Integer requestedLimit) {
        RunId runId = parseRunId(runIdValue);
        requireOwnedRun(owner, runId);
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        Position before = decode(cursor, owner, runId);
        List<RunFeedback> fetched = feedback.list(owner, runId,
                before == null ? null : new RunFeedbackRepository.Position(before.createdAt(), before.feedbackId()), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<RunFeedback> items = hasMore ? new ArrayList<>(fetched.subList(0, limit)) : fetched;
        String nextCursor = hasMore && !items.isEmpty() ? encode(owner, runId, items.get(items.size() - 1)) : null;
        return new Page(items, nextCursor, hasMore);
    }

    public RunFeedback updateExportDisposition(UserId owner, String runIdValue, String feedbackId,
                                               RunFeedback.ExportDisposition disposition) {
        RunId runId = parseRunId(runIdValue);
        requireOwnedRun(owner, runId);
        if (disposition == RunFeedback.ExportDisposition.QUEUE_STATUS_UNKNOWN)
            throw new IllegalArgumentException("export disposition cannot move back to unknown");
        return feedback.updateExportDisposition(owner, runId, feedbackId, disposition)
                .orElseThrow(() -> new IllegalStateException("feedback fact was not found"));
    }

    private void requireEligibleResult(UserId owner, RunId runId) {
        AgentRun run = requireOwnedRun(owner, runId);
        if (run.state() != RunState.SUCCEEDED) throw new RunFeedbackPreconditionException();
        AgentResult result = results.findRoot(runId, owner).orElseThrow(RunFeedbackPreconditionException::new);
        if (result.legacySummary() || result.visibility() != EventVisibility.USER || result.body() == null
                || result.bodySha256() == null || result.bodySha256().isBlank())
            throw new RunFeedbackPreconditionException();
    }

    private AgentRun requireOwnedRun(UserId owner, RunId runId) {
        try {
            return sessions.getRun(runId, owner);
        } catch (IllegalArgumentException notOwnedOrMissing) {
            throw new SessionHistoryNotFoundException();
        }
    }

    private static RunId parseRunId(String runIdValue) {
        try { return new RunId(runIdValue); }
        catch (IllegalArgumentException invalid) { throw invalid; }
    }

    private static String requestDigest(RunId runId, double value, String comment) {
        String canonicalValue = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        String normalizedComment = comment == null ? "N" : "S" + comment.length() + ":" + comment;
        String canonical = runId.value().length() + ":" + runId.value() + "|" + canonicalValue.length() + ":"
                + canonicalValue + "|" + normalizedComment;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String encode(UserId owner, RunId runId, RunFeedback item) {
        String raw = String.join("\n", "v1", Long.toString(owner.value()), runId.value(),
                Long.toString(item.createdAt().toEpochMilli()), item.feedbackId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String token, UserId owner, RunId runId) {
        if (token == null || token.isBlank()) return null;
        if (token.length() > 1024) throw new IllegalArgumentException("cursor is invalid");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\n", -1);
            if (parts.length != 5 || !"v1".equals(parts[0]) || !Long.toString(owner.value()).equals(parts[1])
                    || !runId.value().equals(parts[2]) || parts[4].isBlank() || parts[4].length() > 64)
                throw new IllegalArgumentException("cursor does not match the current query");
            return new Position(Instant.ofEpochMilli(Long.parseLong(parts[3])), parts[4]);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cursor is invalid", invalid);
        }
    }

    public record Submission(RunFeedback feedback, boolean created) { }
    public record Page(List<RunFeedback> items, String nextCursor, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }
    private record Position(Instant createdAt, String feedbackId) { }
}
