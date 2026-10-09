package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.SessionRenderMessage;
import com.haizhuo.brain.platform.run.SessionRenderStore;
import com.haizhuo.brain.platform.run.SessionRenderView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class SessionRenderViewService {
    private final SessionRenderStore store;
    public SessionRenderViewService(SessionRenderStore store) { this.store = Objects.requireNonNull(store); }

    public SessionRenderView view(SessionId sessionId, UserId owner, Integer requestedLimit, String cursor) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        SessionRenderStore.HistoryBoundary before = decode(cursor, sessionId, owner);
        SessionRenderView page = store.view(sessionId, owner, limit, before);
        if (!page.hasMore() || page.items().isEmpty()) return page;
        SessionRenderMessage oldest = page.items().get(0);
        String next = encode(sessionId, owner, page.snapshotCursor(), page.renderCursor(), oldest);
        return new SessionRenderView(page.sessionId(), page.runs(), page.items(), page.resultRefs(),
                page.snapshotCursor(), page.cursorFloor(), page.renderCursor(), page.renderCursorFloor(),
                page.draftRecoveryStatus(), next, true);
    }

    private static String encode(SessionId sid, UserId owner, long c, long r, SessionRenderMessage item) {
        String raw = String.join("|", Long.toString(owner.value()), sid.value(), Long.toString(c), Long.toString(r),
                Long.toString(item.runCreatedAt().toEpochMilli()), item.runId(), Integer.toString(item.kindOrder()),
                Long.toString(item.messageOrdinal()), item.messageId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static SessionRenderStore.HistoryBoundary decode(String cursor, SessionId sid, UserId owner) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 2048) throw new IllegalArgumentException("cursor is invalid");
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 9 || !Long.toString(owner.value()).equals(parts[0]) || !sid.value().equals(parts[1]))
                throw new IllegalArgumentException("cursor does not match this Session and user");
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(parts[4]));
            if (parts[5].isBlank() || parts[5].length() > 36 || parts[8].isBlank() || parts[8].length() > 191)
                throw new IllegalArgumentException("cursor is invalid");
            return new SessionRenderStore.HistoryBoundary(Long.parseLong(parts[2]), Long.parseLong(parts[3]),
                    createdAt, parts[5], Integer.parseInt(parts[6]), Long.parseLong(parts[7]), parts[8]);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("cursor is invalid", error);
        }
    }
}
