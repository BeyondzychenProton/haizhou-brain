package com.haizhuo.brain.platform.session;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RunState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 校验公开历史筛选条件，并将游标绑定到属主、资源和查询条件。 */
public final class SessionHistoryQueryService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private final SessionHistoryQuery query;

    public SessionHistoryQueryService(SessionHistoryQuery query) {
        this.query = Objects.requireNonNull(query);
    }

    public SessionHistoryQuery.Page<SessionHistoryQuery.SessionItem> sessions(UserId owner, SessionFilter filter) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(filter, "filter");
        int limit = limit(filter.limit());
        Long employeeId = filter.employeeId();
        if (employeeId != null && employeeId <= 0) throw new IllegalArgumentException("employeeId must be positive");
        String status = clean(filter.status());
        if (status != null && !List.of("ACTIVE", "CLOSED").contains(status))
            throw new IllegalArgumentException("status must be ACTIVE or CLOSED");
        SessionHistoryQuery.SessionFilter storeFilter = new SessionHistoryQuery.SessionFilter(
                owner.value(), employeeId, status);
        String scope = "sessions";
        String digest = digest(scope, owner.value(), employeeId, status, null);
        SessionHistoryQuery.Position before = decode(filter.cursor(), "sessions", owner.value(), "", digest);
        List<SessionHistoryQuery.SessionItem> rows = query.findSessions(storeFilter, before, limit + 1);
        return page(rows, limit, owner.value(), "sessions", "", digest,
                SessionHistoryQuery.SessionItem::createdAt, SessionHistoryQuery.SessionItem::sessionId);
    }

    public SessionHistoryQuery.Page<SessionHistoryQuery.RunItem> runs(UserId owner, String sessionId,
                                                                       RunFilter filter) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(filter, "filter");
        requireOwnedSession(owner, sessionId);
        int limit = limit(filter.limit());
        String state = clean(filter.state());
        if (state != null) {
            try { state = RunState.valueOf(state).name(); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("state is invalid", invalid); }
        }
        SessionHistoryQuery.RunFilter storeFilter = new SessionHistoryQuery.RunFilter(owner.value(), sessionId, state);
        String digest = digest("runs", owner.value(), null, state, sessionId);
        SessionHistoryQuery.Position before = decode(filter.cursor(), "runs", owner.value(), sessionId, digest);
        List<SessionHistoryQuery.RunItem> rows = query.findRuns(storeFilter, before, limit + 1);
        return page(rows, limit, owner.value(), "runs", sessionId, digest,
                SessionHistoryQuery.RunItem::createdAt, SessionHistoryQuery.RunItem::runId);
    }

    public SessionHistoryQuery.Page<SessionHistoryQuery.ResultItem> results(UserId owner, String sessionId,
                                                                             ResultFilter filter) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(filter, "filter");
        requireOwnedSession(owner, sessionId);
        int limit = limit(filter.limit());
        SessionHistoryQuery.ResultFilter storeFilter = new SessionHistoryQuery.ResultFilter(owner.value(), sessionId);
        String digest = digest("results", owner.value(), null, null, sessionId);
        SessionHistoryQuery.Position before = decode(filter.cursor(), "results", owner.value(), sessionId, digest);
        List<SessionHistoryQuery.ResultItem> rows = query.findResults(storeFilter, before, limit + 1);
        return page(rows, limit, owner.value(), "results", sessionId, digest,
                SessionHistoryQuery.ResultItem::createdAt, SessionHistoryQuery.ResultItem::resultId);
    }

    private void requireOwnedSession(UserId owner, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 36
                || !query.ownsSession(owner.value(), sessionId)) throw new SessionHistoryNotFoundException();
    }

    private static int limit(Integer requested) {
        int value = requested == null ? DEFAULT_LIMIT : requested;
        if (value < 1 || value > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        return value;
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static <T> SessionHistoryQuery.Page<T> page(List<T> rows, int limit, long owner, String kind,
                                                        String scope, String filterDigest,
                                                        java.util.function.Function<T, Instant> createdAt,
                                                        java.util.function.Function<T, String> id) {
        boolean hasMore = rows.size() > limit;
        List<T> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = null;
        if (hasMore && !items.isEmpty()) {
            T last = items.get(items.size() - 1);
            next = encode(new Cursor(kind, owner, scope, filterDigest,
                    createdAt.apply(last), id.apply(last)));
        }
        return new SessionHistoryQuery.Page<>(items, next, hasMore);
    }

    private static String encode(Cursor cursor) {
        String raw = String.join("\n", "v1", cursor.kind(), Long.toString(cursor.ownerUserId()), cursor.scope(),
                cursor.filterDigest(), Long.toString(cursor.createdAt().toEpochMilli()), cursor.id());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static SessionHistoryQuery.Position decode(String token, String kind, long owner, String scope,
                                                        String filterDigest) {
        if (token == null || token.isBlank()) return null;
        if (token.length() > 2048) throw new IllegalArgumentException("cursor is invalid");
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", -1);
            if (parts.length != 7 || !"v1".equals(parts[0]) || !kind.equals(parts[1])
                    || !Long.toString(owner).equals(parts[2]) || !scope.equals(parts[3])
                    || !filterDigest.equals(parts[4]) || parts[6].isBlank() || parts[6].length() > 64)
                throw new IllegalArgumentException("cursor does not match the current query");
            return new SessionHistoryQuery.Position(Instant.ofEpochMilli(Long.parseLong(parts[5])), parts[6]);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("cursor is invalid", invalid);
        }
    }

    private static String digest(String kind, long owner, Long employeeId, String status, String sessionId) {
        String canonical = String.join("\n", kind, Long.toString(owner), value(employeeId), value(status), value(sessionId));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }

    public record SessionFilter(Long employeeId, String status, String cursor, Integer limit) { }
    public record RunFilter(String state, String cursor, Integer limit) { }
    public record ResultFilter(String cursor, Integer limit) { }

    private record Cursor(String kind, long ownerUserId, String scope, String filterDigest,
                          Instant createdAt, String id) { }
}
