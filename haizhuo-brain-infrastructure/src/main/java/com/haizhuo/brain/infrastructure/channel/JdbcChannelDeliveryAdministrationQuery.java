package com.haizhuo.brain.infrastructure.channel;

import com.haizhuo.brain.platform.channel.ChannelDeliveryAdministrationQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 提供精简的只读投影；查询不会读取 reply_target 或 idempotency_key。 */
@Repository
public class JdbcChannelDeliveryAdministrationQuery implements ChannelDeliveryAdministrationQuery {
    private static final Set<String> SAFE_ERROR_CODES = Set.of(
            "DELIVERED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "UNCERTAIN",
            "SENDING_LEASE_EXPIRED", "SENDING_UPGRADE_UNCERTAIN", "MIGRATION_SENDING_UNCERTAIN", "NO_SENDER");
    private static final Set<String> SAFE_ATTEMPT_STATUSES = Set.of(
            "SENDING", "DELIVERED", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "UNCERTAIN");
    private static final int MAX_ATTEMPT_HISTORY = 100;

    private static final String SELECT = "SELECT d.delivery_id,d.run_id,r.session_id,d.binding_id,d.provider,"
            + "d.state,d.attempts,d.result_id,d.content,d.external_message_id,d.last_error,d.created_at,"
            + "d.updated_at,d.sending_expires_at,d.revision,r.state run_state "
            + "FROM platform_channel_delivery d LEFT JOIN platform_agent_run r ON r.run_id=d.run_id ";

    private final JdbcTemplate jdbc;

    public JdbcChannelDeliveryAdministrationQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DeliverySummary> findPage(PageQuery query) {
        StringBuilder sql = new StringBuilder(SELECT).append("WHERE d.created_at<=?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(query.createdTo()));
        if (query.state() != null) add(sql, args, "d.state=?", query.state());
        if (query.bindingId() != null) add(sql, args, "d.binding_id=?", query.bindingId());
        if (query.provider() != null) add(sql, args, "d.provider=?", query.provider());
        if (query.runId() != null) add(sql, args, "d.run_id=?", query.runId());
        if (query.createdFrom() != null) {
            add(sql, args, "d.created_at>=?", Timestamp.from(query.createdFrom()));
        }
        if (query.beforeCreatedAt() != null) {
            sql.append(" AND (d.created_at<? OR (d.created_at=? AND d.delivery_id<?))");
            args.add(Timestamp.from(query.beforeCreatedAt()));
            args.add(Timestamp.from(query.beforeCreatedAt()));
            args.add(query.beforeDeliveryId());
        }
        sql.append(" ORDER BY d.created_at DESC,d.delivery_id DESC LIMIT ?");
        args.add(query.limit());
        return jdbc.query(sql.toString(), this::summary, args.toArray());
    }

    @Override
    public Optional<DeliveryDetail> findById(String deliveryId) {
        return jdbc.query(SELECT + "WHERE d.delivery_id=?", (rs, row) -> new DeliveryRow(summary(rs, row),
                        rs.getString("run_state")), deliveryId).stream().findFirst().map(row -> {
            List<AttemptHistory> attempts = jdbc.query("SELECT attempt_no,claim_generation,started_at,finished_at,"
                            + "status,safe_error_code FROM platform_channel_delivery_attempt "
                            + "WHERE delivery_id=? ORDER BY attempt_no DESC LIMIT ?",
                    (rs, index) -> new AttemptHistory(rs.getInt("attempt_no"), rs.getLong("claim_generation"),
                            instant(rs, "started_at"), instantOrNull(rs, "finished_at"),
                            safeAttemptStatus(rs.getString("status")),
                            safeErrorCode(rs.getString("safe_error_code"))), deliveryId, MAX_ATTEMPT_HISTORY);
            String historyState = attempts.isEmpty() && row.summary().attempts() > 0
                    ? "LEGACY_UNRECORDED"
                    : attempts.size() < row.summary().attempts() ? "PARTIAL" : "RECORDED";
            return new DeliveryDetail(row.summary(), row.runState(), attempts, historyState, List.of());
        });
    }

    private DeliverySummary summary(ResultSet rs, int row) throws SQLException {
        return new DeliverySummary(rs.getString("delivery_id"), rs.getString("run_id"),
                rs.getString("session_id"), rs.getString("binding_id"), rs.getString("provider"),
                rs.getString("state"), rs.getInt("attempts"), rs.getString("result_id"),
                safePreview(rs.getString("content")), rs.getString("external_message_id"),
                safeErrorCode(rs.getString("last_error")), instant(rs, "created_at"), instant(rs, "updated_at"),
                instantOrNull(rs, "sending_expires_at"), evidenceSource(rs.getString("provider")),
                rs.getLong("revision"));
    }

    private static void add(StringBuilder sql, List<Object> args, String predicate, Object value) {
        sql.append(" AND ").append(predicate);
        args.add(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String safeErrorCode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return SAFE_ERROR_CODES.contains(value) ? value : "PROVIDER_ERROR";
    }

    private static String safeAttemptStatus(String raw) {
        if (raw == null || raw.isBlank()) return "UNKNOWN";
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        return SAFE_ATTEMPT_STATUSES.contains(value) ? value : "UNKNOWN";
    }

    private static String evidenceSource(String provider) {
        return "simulated".equalsIgnoreCase(provider) ? "SIMULATED" : "UNVERIFIED";
    }

    private static String safePreview(String raw) {
        if (raw == null || raw.isBlank()) return "（无摘要）";
        String safe = raw.replaceAll("(?i)bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|token|secret|password|credential)(\\s*[:=]\\s*)[^\\s,;]+",
                        "$1$2[REDACTED]")
                .replaceAll("eyJ[a-zA-Z0-9_-]{8,}(?:\\.[a-zA-Z0-9_-]{8,}){2}", "[REDACTED]")
                .replaceAll("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)", "[PHONE]")
                .replaceAll("(?i)(?:[a-z]:\\\\|/home/|/root/)[^\\s]+", "[PATH]");
        int maxCodePoints = 180;
        int points = safe.codePointCount(0, safe.length());
        if (points > maxCodePoints) safe = safe.substring(0, safe.offsetByCodePoints(0, maxCodePoints)) + "…";
        return safe;
    }

    private record DeliveryRow(DeliverySummary summary, String runState) { }
}
