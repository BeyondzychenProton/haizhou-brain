package com.haizhuo.brain.api.admin;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RecoveryRunQuery;
import com.haizhuo.brain.platform.run.RecoveryRunQueryStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 校验管理员筛选条件，并将不透明分页游标绑定到调用者和查询条件。 */
@Service
public class RecoveryRunQueryService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private final RecoveryRunQueryStore store;

    public RecoveryRunQueryService(RecoveryRunQueryStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public PageResponse list(UserId actor, Filter filter) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        String state = normalizeState(filter.state());
        Integer limit = filter.limit() == null ? DEFAULT_LIMIT : filter.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (filter.userId() != null && filter.userId() <= 0) throw new IllegalArgumentException("userId must be positive");
        if (filter.employeeId() != null && filter.employeeId() <= 0)
            throw new IllegalArgumentException("employeeId must be positive");
        if (filter.createdFrom() != null && filter.createdTo() != null
                && filter.createdFrom().isAfter(filter.createdTo()))
            throw new IllegalArgumentException("createdFrom must be before createdTo");

        RecoveryRunQuery.Filter storeFilter = new RecoveryRunQuery.Filter(state, filter.userId(), filter.employeeId(),
                filter.createdFrom(), filter.createdTo());
        Cursor cursor = decodeCursor(filter.cursor(), actor, storeFilter);
        List<RecoveryRunQuery.Summary> rows = store.findPage(storeFilter,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.runId(), limit + 1);
        boolean hasMore = rows.size() > limit;
        List<RecoveryRunQuery.Summary> visible = hasMore ? rows.subList(0, limit) : rows;
        String next = hasMore && !visible.isEmpty()
                ? encodeCursor(actor, storeFilter, visible.get(visible.size() - 1)) : null;
        return new PageResponse(visible.stream().map(RecoveryRunQueryService::summary).toList(), next, hasMore);
    }

    public DetailResponse detail(String runId) {
        if (runId == null || runId.isBlank() || runId.length() > 36)
            throw new IllegalArgumentException("runId is invalid");
        RecoveryRunQuery.Detail detail = store.findDetail(runId).orElseThrow(RecoveryRunNotFoundException::new);
        RecoveryRunQuery.Summary summary = detail.summary();
        return new DetailResponse(summary.runId(), summary.sessionId(), summary.ownerUserId(), summary.employeeId(),
                summary.definitionVersionId(), summary.runtimeProfile(), summary.state(), summary.failureCode(),
                summary.createdAt(), summary.startedAt(), summary.finishedAt(), summary.recoveryRequiredAt(),
                summary.recoveryRequiredAtStatus(), detail.latestAttempt(), detail.terminationEligibility(),
                detail.publicEventSummary(), detail.recoveryActions(), detail.sessionActivity(), detail.observedAt());
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) return "RECOVERY_REQUIRED";
        if ("RECOVERY_REQUIRED".equals(state) || "TERMINATED".equals(state)) return state;
        throw new IllegalArgumentException("state must be RECOVERY_REQUIRED or TERMINATED");
    }

    private static String encodeCursor(UserId actor, RecoveryRunQuery.Filter filter,
                                       RecoveryRunQuery.Summary last) {
        return "1." + actor.value() + "." + filterDigest(filter) + "." + last.createdAt().toEpochMilli()
                + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(last.runId().getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String value, UserId actor, RecoveryRunQuery.Filter filter) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 2048) throw new IllegalArgumentException("cursor is invalid");
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 5 || !"1".equals(parts[0]) || !Long.toString(actor.value()).equals(parts[1])
                    || !filterDigest(filter).equals(parts[2]))
                throw new IllegalArgumentException("cursor does not match the current admin query");
            long epochMillis = Long.parseLong(parts[3]);
            String runId = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            if (runId.isBlank() || runId.length() > 36)
                throw new IllegalArgumentException("cursor is invalid");
            return new Cursor(Instant.ofEpochMilli(epochMillis), runId);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cursor is invalid", error);
        }
    }

    private static String filterDigest(RecoveryRunQuery.Filter filter) {
        String canonical = String.join("\n", filter.state(), value(filter.userId()), value(filter.employeeId()),
                value(filter.createdFrom()), value(filter.createdTo()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private static SummaryResponse summary(RecoveryRunQuery.Summary summary) {
        return new SummaryResponse(summary.runId(), summary.sessionId(), summary.ownerUserId(), summary.employeeId(),
                summary.definitionVersionId(), summary.runtimeProfile(), summary.state(), summary.failureCode(),
                summary.createdAt(), summary.startedAt(), summary.finishedAt(), summary.recoveryRequiredAt(),
                summary.recoveryRequiredAtStatus());
    }

    private record Cursor(Instant createdAt, String runId) { }

    public record Filter(String state, Long userId, Long employeeId, Instant createdFrom, Instant createdTo,
                         String cursor, Integer limit) { }

    public record PageResponse(List<SummaryResponse> items, String nextCursor, boolean hasMore) {
        public PageResponse { items = List.copyOf(items); }
    }

    public record SummaryResponse(String runId, String sessionId, long ownerUserId, long employeeId,
                                  long definitionVersionId, String runtimeProfile, String state,
                                  String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt,
                                  Instant recoveryRequiredAt, String recoveryRequiredAtStatus) { }

    public record DetailResponse(String runId, String sessionId, long ownerUserId, long employeeId,
                                 long definitionVersionId, String runtimeProfile, String state,
                                 String failureCode, Instant createdAt, Instant startedAt, Instant finishedAt,
                                 Instant recoveryRequiredAt, String recoveryRequiredAtStatus,
                                 RecoveryRunQuery.Attempt latestAttempt,
                                 RecoveryRunQuery.Eligibility terminationEligibility,
                                 List<RecoveryRunQuery.PublicEvent> publicEventSummary,
                                 List<RecoveryRunQuery.Action> recoveryActions,
                                 RecoveryRunQuery.SessionActivity sessionActivity, Instant observedAt) {
        public DetailResponse {
            publicEventSummary = List.copyOf(publicEventSummary);
            recoveryActions = List.copyOf(recoveryActions);
        }
    }

    public static final class RecoveryRunNotFoundException extends RuntimeException { }
}
