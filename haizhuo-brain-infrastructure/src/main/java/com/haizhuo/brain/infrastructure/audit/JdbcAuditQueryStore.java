package com.haizhuo.brain.infrastructure.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.audit.AuditChange;
import com.haizhuo.brain.platform.audit.AuditDomain;
import com.haizhuo.brain.platform.audit.AuditEntry;
import com.haizhuo.brain.platform.audit.AuditFilter;
import com.haizhuo.brain.platform.audit.AuditOutcome;
import com.haizhuo.brain.platform.audit.AuditPosition;
import com.haizhuo.brain.platform.audit.AuditQueryService;
import com.haizhuo.brain.platform.audit.AuditQueryStore;
import com.haizhuo.brain.platform.audit.AuditSourceCompleteness;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 仅从现有结构化审计事实中读取白名单数据。表名和列名表达式均为常量；
 * 请求筛选值始终使用绑定参数，不能选择表或拼接 SQL 片段。
 */
@Repository
public class JdbcAuditQueryStore implements AuditQueryStore {
    private static final int TOOL_MAX_LIMIT = 10_000;
    private static final Comparator<AuditEntry> ORDER = Comparator
            .comparing(AuditEntry::createdAt).reversed()
            .thenComparing(AuditEntry::auditId, Comparator.reverseOrder());
    private static final String USER_ACTION = "CASE WHEN event_type IN ('USER_CREATED','ACTIVATION_REISSUED',"
            + "'ADMIN_ROLE_GRANTED','ADMIN_ROLE_REVOKED','USER_STATUS_CHANGED','ACCOUNT_ACTIVATED',"
            + "'INITIAL_ADMIN_CREATED','PASSWORD_CHANGED') THEN event_type ELSE 'OTHER' END";
    private static final String AUTH_ACTION = "CASE WHEN event_type IN ('LOGIN_SUCCEEDED','LOGIN_FAILED',"
            + "'ACTIVATION_SUCCEEDED','ACTIVATION_FAILED') THEN event_type ELSE 'OTHER' END";
    private static final String USER_OUTCOME = "CASE WHEN event_type IN ('USER_CREATED','ACTIVATION_REISSUED',"
            + "'ADMIN_ROLE_GRANTED','ADMIN_ROLE_REVOKED','USER_STATUS_CHANGED','ACCOUNT_ACTIVATED',"
            + "'INITIAL_ADMIN_CREATED','PASSWORD_CHANGED') THEN 'SUCCESS' ELSE 'UNKNOWN' END";
    private static final String MANAGEMENT_ACTION = "CASE WHEN event_type IN ('EMPLOYEE_CREATED','EMPLOYEE_PROFILE_UPDATED',"
            + "'EMPLOYEE_STATUS_CHANGED','DRAFT_SAVED','DEFINITION_PUBLISHED','CAPABILITY_STATUS_CHANGED',"
            + "'USER_CAPABILITY_GRANT_CHANGED','MCP_CONNECTION_CREATED','MCP_CONNECTION_STATUS_CHANGED',"
            + "'MCP_TOOL_APPROVED') THEN event_type ELSE 'OTHER' END";
    private static final String MANAGEMENT_OUTCOME = "CASE WHEN event_type IN ('EMPLOYEE_CREATED','EMPLOYEE_PROFILE_UPDATED',"
            + "'EMPLOYEE_STATUS_CHANGED','DRAFT_SAVED','DEFINITION_PUBLISHED','CAPABILITY_STATUS_CHANGED',"
            + "'USER_CAPABILITY_GRANT_CHANGED','MCP_CONNECTION_CREATED','MCP_CONNECTION_STATUS_CHANGED',"
            + "'MCP_TOOL_APPROVED') THEN 'SUCCESS' ELSE 'UNKNOWN' END";
    private static final String ASSET_ACTION = "CASE WHEN action IN ('DRAFT_SAVED','PUBLISHED') THEN action ELSE 'OTHER' END";
    private static final String ASSET_OUTCOME = "CASE WHEN action IN ('DRAFT_SAVED','PUBLISHED') "
            + "THEN 'SUCCESS' ELSE 'UNKNOWN' END";
    private static final String CHANNEL_OUTCOME = "CASE WHEN action_code IN ('CREATE_ACCOUNT','UPDATE_ACCOUNT',"
            + "'LINK_IDENTITY','REVOKE_IDENTITY') THEN 'SUCCESS' ELSE 'UNKNOWN' END";
    private static final String TOOL_OUTCOME = "CASE WHEN decision IN ('DENY','DENIED') THEN 'DENIED' "
            + "WHEN result_status IN ('FAILED','ERROR') THEN 'FAILED' "
            + "WHEN result_status IN ('SUCCESS','SUCCEEDED','COMPLETED') THEN 'SUCCESS' ELSE 'UNKNOWN' END";
    private static final String AUTH_OUTCOME = "CASE WHEN event_type IN ('LOGIN_FAILED','ACTIVATION_FAILED') THEN 'FAILED' "
            + "WHEN event_type IN ('LOGIN_SUCCEEDED','ACTIVATION_SUCCEEDED') THEN 'SUCCESS' ELSE 'UNKNOWN' END";

    private static final List<Source> SOURCES = List.of(
            new Source(AuditDomain.USER, "platform_user_audit", "id", "occurred_at", numericId(AuditDomain.USER, "id"),
                    USER_ACTION, "actor_user_id", "'PLATFORM_USER'", "CAST(target_user_id AS CHAR)", null, null,
                    USER_OUTCOME, "previous_state", "new_state", null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL, ""),
            new Source(AuditDomain.AUTHENTICATION, "platform_authentication_audit", "id", "occurred_at",
                    numericId(AuditDomain.AUTHENTICATION, "id"), AUTH_ACTION, "NULL", "'AUTHENTICATION_ATTEMPT'",
                    "CAST(id AS CHAR)", null, null, AUTH_OUTCOME, "failure_category", null, null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL, ""),
            new Source(AuditDomain.DEFINITION, "agent_definition_management_audit", "id", "occurred_at",
                    numericId(AuditDomain.DEFINITION, "id"), MANAGEMENT_ACTION, "actor_user_id", "target_type",
                    "target_id", "request_id", null, MANAGEMENT_OUTCOME, "previous_summary", "new_summary", null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL, "target_type='DIGITAL_EMPLOYEE'"),
            new Source(AuditDomain.CAPABILITY, "agent_definition_management_audit", "id", "occurred_at",
                    numericId(AuditDomain.CAPABILITY, "id"), MANAGEMENT_ACTION, "actor_user_id", "target_type",
                    "target_id", "request_id", null, MANAGEMENT_OUTCOME, "previous_summary", "new_summary", null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL,
                    "((target_type='CAPABILITY' AND event_type='CAPABILITY_STATUS_CHANGED') "
                            + "OR (target_type='PLATFORM_USER' AND event_type='USER_CAPABILITY_GRANT_CHANGED'))"),
            new Source(AuditDomain.MCP, "agent_definition_management_audit", "id", "occurred_at",
                    numericId(AuditDomain.MCP, "id"), MANAGEMENT_ACTION, "actor_user_id", "target_type",
                    "target_id", "request_id", null, MANAGEMENT_OUTCOME, "previous_summary", "new_summary", null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL,
                    "event_type IN ('MCP_CONNECTION_CREATED','MCP_CONNECTION_STATUS_CHANGED','MCP_TOOL_APPROVED')"),
            new Source(AuditDomain.ASSET, "capability_asset_audit", "id", "occurred_at",
                    numericId(AuditDomain.ASSET, "id"), ASSET_ACTION, "actor_user_id", "'CAPABILITY_ASSET'",
                    "capability_code", "request_id", null, ASSET_OUTCOME, "previous_hash", "new_hash", null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL, ""),
            new Source(AuditDomain.TOOL, "tool_invocation_audit", "invocation_id", "created_at",
                    "CONCAT('TOOL:',LOWER(invocation_id))", "'TOOL_INVOCATION'", "user_id", "'CAPABILITY'",
                    "capability_code", null, "run_id", TOOL_OUTCOME, "decision", "result_status", null, null,
                    AuditSourceCompleteness.LEGACY_PARTIAL, ""),
            new Source(AuditDomain.RUN_RECOVERY, "platform_run_recovery_action", "request_id", "created_at",
                    "CONCAT('RUN_RECOVERY:',LOWER(HEX(request_id)))", "'RUN_RECOVERY_TERMINATED'", "actor_user_id",
                    "'RUN'", "run_id", "request_id", "run_id", "'SUCCESS'", null, null, null, null,
                    AuditSourceCompleteness.COMPLETE, ""),
            new Source(AuditDomain.CHANNEL, "platform_channel_management_audit", "audit_id", "created_at",
                    numericId(AuditDomain.CHANNEL, "audit_id"),
                    "CASE WHEN action_code IN ('CREATE_ACCOUNT','UPDATE_ACCOUNT','LINK_IDENTITY','REVOKE_IDENTITY') "
                            + "THEN action_code ELSE 'OTHER' END",
                    "actor_user_id",
                    "CASE WHEN action_code IN ('LINK_IDENTITY','REVOKE_IDENTITY') THEN 'CHANNEL_IDENTITY' ELSE 'CHANNEL_ACCOUNT' END",
                    "binding_id", "request_id", null, CHANNEL_OUTCOME, "safe_changes", "client_source", null, null,
                    AuditSourceCompleteness.COMPLETE, "")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate snapshotRead;

    public JdbcAuditQueryStore(JdbcTemplate jdbc, ObjectMapper json,
                               org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.snapshotRead = new TransactionTemplate(transactions);
        this.snapshotRead.setReadOnly(true);
        this.snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public List<AuditEntry> findCandidates(AuditFilter filter, Instant upperBound,
                                          AuditPosition after, int perSourceLimit) {
        if (perSourceLimit < 1 || perSourceLimit > TOOL_MAX_LIMIT + 1)
            throw new IllegalArgumentException("Invalid audit candidate limit");
        List<AuditEntry> rows = new ArrayList<>();
        for (Source source : SOURCES) {
            if (filter.domain() != null && filter.domain() != source.domain()) continue;
            if (!supports(source, filter)) continue;
            rows.addAll(query(source, filter, upperBound, after, perSourceLimit));
        }
        return rows.stream().sorted(ORDER).limit(perSourceLimit).toList();
    }

    @Override
    public Optional<AuditEntry> findById(String auditId) {
        ParsedId parsed = parseId(auditId);
        if (parsed == null) return Optional.empty();
        Source source = sourceFor(parsed.domain());
        if (source == null) return Optional.empty();
        String sql = selectSql(source) + " WHERE " + source.idColumn() + "=?"
                + (source.domainPredicate().isBlank() ? "" : " AND (" + source.domainPredicate() + ")");
        List<AuditEntry> rows = jdbc.query(sql, mapper(source), parsed.sourceKey());
        return rows.stream().filter(entry -> entry.auditId().equals(auditId)
                || parsed.domain() == AuditDomain.RUN_RECOVERY && entry.auditId().equalsIgnoreCase(auditId))
                .findFirst();
    }

    @Override
    public List<AuditEntry> exportSnapshot(AuditFilter filter, Instant upperBound,
                                           int maximumRows, int chunkSize) {
        if (maximumRows < 1 || chunkSize < 1 || chunkSize > maximumRows)
            throw new IllegalArgumentException("Invalid audit export bound");
        return Objects.requireNonNull(snapshotRead.execute(status -> {
            long total = 0;
            for (Source source : SOURCES) {
                if (filter.domain() != null && filter.domain() != source.domain()) continue;
                if (!supports(source, filter)) continue;
                long sourceCount = count(source, filter, upperBound);
                if (sourceCount > maximumRows - total) throw AuditQueryService.AuditQueryException.exportTooLarge();
                total += sourceCount;
            }
            List<AuditEntry> result = new ArrayList<>((int) total);
            AuditPosition after = null;
            while (result.size() < total) {
                int nextSize = (int) Math.min(chunkSize, total - result.size());
                List<AuditEntry> batch = findCandidates(filter, upperBound, after, nextSize);
                if (batch.isEmpty()) break;
                result.addAll(batch);
                AuditEntry last = batch.get(batch.size() - 1);
                after = new AuditPosition(last.createdAt(), last.auditId());
            }
            if (result.size() != total)
                throw new DataAccessResourceFailureException("Audit export snapshot did not match its bounded count");
            return List.copyOf(result);
        }), "Audit export transaction did not return a result");
    }

    @Override
    public void recordSensitiveRead(long actorUserId, String accessKind, String targetDigest,
                                    int resultCount, Instant occurredAt) {
        if (actorUserId <= 0 || !List.of("DETAIL", "EXPORT").contains(accessKind)
                || targetDigest == null || !targetDigest.matches("[0-9a-f]{64}")
                || resultCount < 0 || occurredAt == null) {
            throw new IllegalArgumentException("Invalid audit read fact");
        }
        jdbc.update("INSERT INTO platform_admin_audit_read(actor_user_id,access_kind,target_digest,result_count,created_at) "
                        + "VALUES(?,?,?,?,?)", actorUserId, accessKind, targetDigest, resultCount,
                Timestamp.from(occurredAt));
    }

    private List<AuditEntry> query(Source source, AuditFilter filter, Instant upperBound,
                                   AuditPosition after, int limit) {
        SqlQuery query = buildQuery(source, filter, upperBound, after, true);
        List<Object> args = new ArrayList<>(query.args());
        args.add(limit);
        return jdbc.query(query.sql() + " ORDER BY " + source.createdColumn() + " DESC," + source.auditIdExpression()
                        + " DESC LIMIT ?", mapper(source), args.toArray());
    }

    private long count(Source source, AuditFilter filter, Instant upperBound) {
        SqlQuery query = buildQuery(source, filter, upperBound, null, false);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + source.table() + query.where(), Long.class,
                query.args().toArray());
        return count == null ? 0 : count;
    }

    private SqlQuery buildQuery(Source source, AuditFilter filter, Instant upperBound,
                                AuditPosition after, boolean select) {
        StringBuilder sql = new StringBuilder(select ? selectSql(source) : "");
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE ").append(source.createdColumn()).append("<=?");
        args.add(Timestamp.from(upperBound));
        if (filter.createdFrom() != null) {
            where.append(" AND ").append(source.createdColumn()).append(">=?");
            args.add(Timestamp.from(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            where.append(" AND ").append(source.createdColumn()).append("<?");
            args.add(Timestamp.from(filter.createdTo()));
        }
        if (!source.domainPredicate().isBlank()) where.append(" AND (").append(source.domainPredicate()).append(')');
        if (filter.action() != null) {
            where.append(" AND (").append(source.actionExpression()).append(")=?");
            args.add(filter.action());
        }
        if (filter.actorUserId() != null) {
            where.append(" AND ").append(source.actorExpression()).append("=?");
            args.add(filter.actorUserId());
        }
        if (filter.targetType() != null) {
            where.append(" AND (").append(source.targetTypeExpression()).append(")=?");
            args.add(filter.targetType());
        }
        if (filter.targetId() != null) {
            where.append(" AND (").append(source.targetIdExpression()).append(")=?");
            args.add(filter.targetId());
        }
        if (filter.requestId() != null) {
            where.append(" AND ").append(source.requestExpression()).append("=?");
            args.add(filter.requestId());
        }
        if (filter.runId() != null) {
            where.append(" AND ").append(source.runIdExpression()).append("=?");
            args.add(filter.runId());
        }
        if (after != null) {
            where.append(" AND (").append(source.createdColumn()).append("<? OR (")
                    .append(source.createdColumn()).append("=? AND ")
                    .append(source.auditIdExpression()).append("<?))");
            args.add(Timestamp.from(after.createdAt()));
            args.add(Timestamp.from(after.createdAt()));
            args.add(after.auditId());
        }
        if (select) sql.append(where);
        return new SqlQuery(sql.toString(), where.toString(), List.copyOf(args));
    }

    private static String selectSql(Source source) {
        return "SELECT " + source.auditIdExpression() + " AS audit_id,'" + source.domain().name() + "' AS audit_domain,"
                + source.actionExpression() + " AS action," + source.actorExpression() + " AS actor_user_id,"
                + source.targetTypeExpression() + " AS target_type," + source.targetIdExpression() + " AS target_id,"
                + (source.requestExpression() == null ? "NULL" : source.requestExpression()) + " AS request_id,"
                + (source.runIdExpression() == null ? "NULL" : source.runIdExpression()) + " AS run_id,"
                + source.outcomeExpression() + " AS outcome," + source.createdColumn() + " AS created_at,"
                + "'" + source.completeness().name() + "' AS source_completeness,"
                + optionalColumn(source.detailColumnOne(), "detail_one") + ","
                + optionalColumn(source.detailColumnTwo(), "detail_two") + ","
                + optionalColumn(source.detailColumnThree(), "detail_three") + ","
                + optionalColumn(source.detailColumnFour(), "detail_four")
                + " FROM " + source.table();
    }

    private static String optionalColumn(String column, String alias) {
        return column == null ? "NULL AS " + alias : column + " AS " + alias;
    }

    private RowMapper<AuditEntry> mapper(Source source) {
        return (rs, rowNumber) -> mapEntry(source, rs);
    }

    private AuditEntry mapEntry(Source source, ResultSet rs) throws SQLException {
        AuditDomain domain = AuditDomain.valueOf(rs.getString("audit_domain"));
        String action = rs.getString("action");
        String targetType = rs.getString("target_type");
        String targetId = rs.getString("target_id");
        String requestId = rs.getString("request_id");
        String runId = rs.getString("run_id");
        String sourceValueOne = rs.getString("detail_one");
        String sourceValueTwo = rs.getString("detail_two");
        String sourceValueThree = rs.getString("detail_three");
        String sourceValueFour = rs.getString("detail_four");
        AuditOutcome outcome;
        try {
            outcome = AuditOutcome.valueOf(rs.getString("outcome"));
        } catch (RuntimeException unknown) {
            outcome = AuditOutcome.UNKNOWN;
        }
        return new AuditEntry(rs.getString("audit_id"), domain, action, nullableLong(rs, "actor_user_id"),
                safeTargetType(domain, targetType, action), safeTargetId(targetId), requestId,
                runId, outcome, rs.getTimestamp("created_at").toInstant(),
                safeChanges(domain, action, sourceValueOne, sourceValueTwo, sourceValueThree, sourceValueFour),
                domain == AuditDomain.RUN_RECOVERY ? "停止核查证据引用已记录" : null,
                AuditSourceCompleteness.valueOf(rs.getString("source_completeness")));
    }

    private List<AuditChange> safeChanges(AuditDomain domain, String action, String one, String two,
                                         String three, String four) {
        return switch (domain) {
            case USER -> jsonChanges(one, two, Map.of(
                    "status", "账号状态", "admin", "平台管理员角色", "mustChangePassword", "首次登录改密"));
            case DEFINITION -> jsonChanges(one, two, Map.of(
                    "draftRevision", "草稿修订", "versionNo", "发布版本", "enabled", "启用状态"));
            case CAPABILITY -> jsonChanges(one, two, Map.of("enabled", "启用状态"));
            case MCP -> jsonChanges(one, two, Map.of("enabled", "连接启用状态"));
            case ASSET -> assetChanges(action, one);
            case CHANNEL -> channelChanges(one);
            default -> List.of();
        };
    }

    private List<AuditChange> jsonChanges(String before, String after, Map<String, String> fields) {
        if (before == null || after == null) return List.of();
        try {
            JsonNode oldNode = json.readTree(before);
            JsonNode newNode = json.readTree(after);
            if (!oldNode.isObject() || !newNode.isObject()) return List.of();
            List<AuditChange> changes = new ArrayList<>();
            fields.forEach((key, label) -> {
                String oldValue = safeValue(key, oldNode.get(key));
                String newValue = safeValue(key, newNode.get(key));
                if (oldValue != null && newValue != null && !oldValue.equals(newValue))
                    changes.add(new AuditChange(label, oldValue, newValue));
            });
            return List.copyOf(changes);
        } catch (Exception invalid) {
            return List.of();
        }
    }

    private static String safeValue(String key, JsonNode value) {
        if (value == null || value.isNull()) return null;
        if ("status".equals(key)) {
            return switch (value.asText()) {
                case "ACTIVE" -> "启用";
                case "DISABLED" -> "停用";
                case "PENDING_ACTIVATION" -> "待激活";
                default -> null;
            };
        }
        if ("enabled".equals(key) || "admin".equals(key) || "mustChangePassword".equals(key)) {
            if (!value.isBoolean()) return null;
            return value.asBoolean() ? "是" : "否";
        }
        if ("draftRevision".equals(key) || "versionNo".equals(key)) {
            if (!value.isIntegralNumber() || value.longValue() < 0 || value.longValue() > 1_000_000_000L) return null;
            return "#" + value.longValue();
        }
        return null;
    }

    private static List<AuditChange> assetChanges(String action, String previousHash) {
        if (!"PUBLISHED".equals(action)) return List.of();
        return List.of(new AuditChange("素材版本", previousHash == null ? "无已发布版本" : "已有版本", "新版本已发布"));
    }

    private static List<AuditChange> channelChanges(String safeChanges) {
        if (safeChanges == null || safeChanges.isBlank()) return List.of();
        List<AuditChange> changes = new ArrayList<>();
        for (String part : safeChanges.split(",")) {
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) continue;
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            String label;
            String safeValue;
            switch (key) {
                case "enabled" -> {
                    if (!List.of("true", "false").contains(value)) continue;
                    label = "启用状态";
                    safeValue = Boolean.parseBoolean(value) ? "启用" : "停用";
                }
                case "defaultEmployeeId" -> {
                    if (!value.matches("[1-9][0-9]{0,18}")) continue;
                    label = "默认员工";
                    safeValue = "员工 #" + value;
                }
                case "sessionScope" -> {
                    if (!List.of("MAIN", "PER_PEER", "PER_ACCOUNT", "PER_GUILD").contains(value)) continue;
                    label = "会话隔离范围";
                    safeValue = value;
                }
                case "provider" -> {
                    if (!value.matches("[A-Za-z0-9._-]{1,32}")) continue;
                    label = "渠道提供方";
                    safeValue = value;
                }
                case "userId" -> {
                    if (!value.matches("[1-9][0-9]{0,18}")) continue;
                    label = "平台用户";
                    safeValue = "用户 #" + value;
                }
                case "state" -> {
                    if (!"REVOKED".equals(value)) continue;
                    label = "身份状态";
                    safeValue = "已撤销";
                }
                default -> { continue; }
            }
            changes.add(new AuditChange(label, null, safeValue));
        }
        return List.copyOf(changes);
    }

    private static String safeTargetType(AuditDomain domain, String targetType, String action) {
        if (domain == AuditDomain.AUTHENTICATION) return "AUTHENTICATION_ATTEMPT";
        if (domain == AuditDomain.RUN_RECOVERY) return "RUN";
        if (domain == AuditDomain.ASSET) return "CAPABILITY_ASSET";
        if (domain == AuditDomain.TOOL) return "CAPABILITY";
        if (domain == AuditDomain.CHANNEL && List.of("LINK_IDENTITY", "REVOKE_IDENTITY").contains(action))
            return "CHANNEL_IDENTITY";
        return switch (targetType) {
            case "DIGITAL_EMPLOYEE", "PLATFORM_USER", "CAPABILITY", "MCP_CONNECTION" -> targetType;
            case "CHANNEL_ACCOUNT", "CHANNEL_IDENTITY", "PLATFORM_USER_CAPABILITY_GRANT" -> targetType;
            default -> "UNKNOWN_TARGET";
        };
    }

    private static String safeTargetId(String targetId) {
        return targetId != null && targetId.matches("[A-Za-z0-9_.:@/-]{1,128}") ? targetId : null;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static boolean supports(Source source, AuditFilter filter) {
        if (filter.actorUserId() != null && "NULL".equals(source.actorExpression())) return false;
        if (filter.requestId() != null && source.requestExpression() == null) return false;
        return filter.runId() == null || source.runIdExpression() != null;
    }

    private static Source sourceFor(AuditDomain domain) {
        return SOURCES.stream().filter(source -> source.domain() == domain).findFirst().orElse(null);
    }

    private static String numericId(AuditDomain domain, String idColumn) {
        return "CONCAT('" + domain.name() + ":',LPAD(CAST(" + idColumn + " AS CHAR),20,'0'))";
    }

    private static ParsedId parseId(String auditId) {
        if (auditId == null) return null;
        int separator = auditId.indexOf(':');
        if (separator <= 0 || separator == auditId.length() - 1) return null;
        AuditDomain domain;
        try {
            domain = AuditDomain.valueOf(auditId.substring(0, separator));
        } catch (IllegalArgumentException error) {
            return null;
        }
        String key = auditId.substring(separator + 1);
        try {
            return switch (domain) {
                case USER, AUTHENTICATION, DEFINITION, CAPABILITY, MCP, ASSET, CHANNEL ->
                        key.matches("[0-9]{20}") ? new ParsedId(domain, Long.parseLong(key)) : null;
                case TOOL -> key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        ? new ParsedId(domain, key.toLowerCase(Locale.ROOT)) : null;
                case RUN_RECOVERY -> key.length() <= 256 && key.matches("(?i)[0-9a-f]+")
                        ? new ParsedId(domain, new String(HexFormat.of().parseHex(key), StandardCharsets.UTF_8)) : null;
                case MODEL_CONNECTION -> null;
            };
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private record Source(AuditDomain domain, String table, String idColumn, String createdColumn,
                          String auditIdExpression, String actionExpression, String actorExpression,
                          String targetTypeExpression, String targetIdExpression, String requestExpression,
                          String runIdExpression, String outcomeExpression, String detailColumnOne,
                          String detailColumnTwo, String detailColumnThree, String detailColumnFour,
                          AuditSourceCompleteness completeness, String domainPredicate) { }

    private record SqlQuery(String sql, String where, List<Object> args) { }
    private record ParsedId(AuditDomain domain, Object sourceKey) { }
}
