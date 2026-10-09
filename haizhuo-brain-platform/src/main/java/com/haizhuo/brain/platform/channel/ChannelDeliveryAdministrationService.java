package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.UserId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 对现有 Delivery Outbox 执行只读管理查询。 */
public final class ChannelDeliveryAdministrationService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final List<String> STATES = List.of(
            "PENDING", "SENDING", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "DELIVERED", "UNCERTAIN");

    private final ChannelDeliveryAdministrationQuery query;
    private final Clock clock;

    public ChannelDeliveryAdministrationService(ChannelDeliveryAdministrationQuery query, Clock clock) {
        this.query = Objects.requireNonNull(query);
        this.clock = Objects.requireNonNull(clock);
    }

    public ChannelDeliveryAdministrationQuery.Page list(UserId actor, Filter filter) {
        Objects.requireNonNull(actor, "actor");
        Filter normalized = normalize(filter);
        int limit = normalized.limit() == null ? DEFAULT_LIMIT : normalized.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");

        Instant now = clock.instant();
        Instant requestedTo = normalized.createdTo();
        if (requestedTo != null && requestedTo.isAfter(now)) {
            throw new IllegalArgumentException("createdTo must not be in the future");
        }
        Instant upperBound = requestedTo == null ? now : requestedTo;
        if (normalized.createdFrom() != null && normalized.createdFrom().isAfter(upperBound)) {
            throw new IllegalArgumentException("createdFrom must not be after createdTo");
        }

        Position position = null;
        String filterHash = filterHash(actor, normalized);
        if (normalized.cursor() != null && !normalized.cursor().isBlank()) {
            Cursor decoded = decode(normalized.cursor());
            if (decoded.actorId() != actor.value() || !decoded.filterHash().equals(filterHash)
                    || (requestedTo != null && !decoded.upperBound().equals(requestedTo))) {
                throw new IllegalArgumentException("cursor does not match the current administrator filters");
            }
            upperBound = decoded.upperBound();
            position = new Position(decoded.beforeCreatedAt(), decoded.beforeDeliveryId());
        }

        List<ChannelDeliveryAdministrationQuery.DeliverySummary> rows = query.findPage(
                new ChannelDeliveryAdministrationQuery.PageQuery(normalized.state(), normalized.bindingId(),
                        normalized.provider(), normalized.runId(), normalized.createdFrom(), upperBound,
                        position == null ? null : position.createdAt(),
                        position == null ? null : position.deliveryId(), limit + 1));
        boolean hasMore = rows.size() > limit;
        List<ChannelDeliveryAdministrationQuery.DeliverySummary> items =
                List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            ChannelDeliveryAdministrationQuery.DeliverySummary last = items.get(items.size() - 1);
            nextCursor = encode(new Cursor(actor.value(), filterHash, upperBound, last.createdAt(), last.deliveryId()));
        }
        return new ChannelDeliveryAdministrationQuery.Page(items, nextCursor, hasMore);
    }

    public Optional<ChannelDeliveryAdministrationQuery.DeliveryDetail> detail(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank() || deliveryId.length() > 64) {
            throw new IllegalArgumentException("deliveryId is invalid");
        }
        return query.findById(deliveryId.trim());
    }

    private Filter normalize(Filter filter) {
        Objects.requireNonNull(filter, "filter");
        String state = clean(filter.state());
        if (state != null && !STATES.contains(state)) throw new IllegalArgumentException("state is invalid");
        String bindingId = bounded(filter.bindingId(), 64, "bindingId");
        String provider = bounded(filter.provider(), 32, "provider");
        String runId = bounded(filter.runId(), 64, "runId");
        return new Filter(state, bindingId, provider, runId, filter.createdFrom(), filter.createdTo(),
                clean(filter.cursor()), filter.limit());
    }

    private static String bounded(String value, int max, String field) {
        String cleaned = clean(value);
        if (cleaned != null && cleaned.length() > max) throw new IllegalArgumentException(field + " is too long");
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private static String filterHash(UserId actor, Filter filter) {
        String canonical = actor.value() + "\n" + nullToEmpty(filter.state()) + "\n"
                + nullToEmpty(filter.bindingId()) + "\n" + nullToEmpty(filter.provider()) + "\n"
                + nullToEmpty(filter.runId()) + "\n" + instant(filter.createdFrom()) + "\n"
                + instant(filter.createdTo());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(Cursor cursor) {
        String raw = "v1\n" + cursor.actorId() + "\n" + cursor.filterHash() + "\n"
                + cursor.upperBound().toEpochMilli() + "\n" + cursor.beforeCreatedAt().toEpochMilli()
                + "\n" + cursor.beforeDeliveryId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String token) {
        try {
            if (token.length() > 2048) throw new IllegalArgumentException("cursor is invalid");
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\n", -1);
            if (parts.length != 6 || !"v1".equals(parts[0]) || parts[2].length() != 64
                    || parts[5].isBlank() || parts[5].length() > 64) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            return new Cursor(Long.parseLong(parts[1]), parts[2], Instant.ofEpochMilli(Long.parseLong(parts[3])),
                    Instant.ofEpochMilli(Long.parseLong(parts[4])), parts[5]);
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalArgumentException argument
                    && "cursor is invalid".equals(argument.getMessage())) throw argument;
            throw new IllegalArgumentException("cursor is invalid", invalid);
        }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String instant(Instant value) { return value == null ? "" : value.toString(); }

    public record Filter(String state, String bindingId, String provider, String runId,
                         Instant createdFrom, Instant createdTo, String cursor, Integer limit) { }

    private record Cursor(long actorId, String filterHash, Instant upperBound,
                          Instant beforeCreatedAt, String beforeDeliveryId) { }
    private record Position(Instant createdAt, String deliveryId) { }
}
