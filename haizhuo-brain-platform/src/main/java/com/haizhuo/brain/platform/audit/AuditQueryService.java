package com.haizhuo.brain.platform.audit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 校验审计查询契约，执行一次全局 keyset 合并，并且只返回安全投影。 */
public final class AuditQueryService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int EXPORT_MAX_ROWS = 10_000;
    private static final int EXPORT_CHUNK_SIZE = 500;
    private static final Duration EXPORT_MAX_RANGE = Duration.ofDays(31);
    private static final Pattern ACTION = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:@/-]{1,128}");
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Comparator<AuditEntry> ORDER = Comparator
            .comparing(AuditEntry::createdAt).reversed()
            .thenComparing(AuditEntry::auditId, Comparator.reverseOrder());

    private final AuditQueryStore store;
    private final Clock clock;

    public AuditQueryService(AuditQueryStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Page list(long actorUserId, String domain, String action, Long actorFilter,
                     String targetType, String targetId, String requestId, String runId,
                     String createdFrom, String createdTo, String rawCursor, Integer requestedLimit) {
        requireActor(actorUserId);
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) throw invalid("limit must be between 1 and 100");
        AuditFilter filter = filter(domain, action, actorFilter, targetType, targetId,
                requestId, runId, createdFrom, createdTo, false);
        String filterHash = fingerprint(filter);
        Cursor cursor = decodeCursor(rawCursor, actorUserId, filterHash);
        Instant upperBound = cursor == null ? clock.instant() : cursor.upperBound();
        AuditPosition after = cursor == null ? null : new AuditPosition(cursor.beforeCreatedAt(), cursor.beforeAuditId());

        List<AuditEntry> candidates = store.findCandidates(filter, upperBound, after, limit + 1).stream()
                .sorted(ORDER).limit(limit + 1L).toList();
        boolean hasMore = candidates.size() > limit;
        List<AuditEntry> items = candidates.stream().limit(limit).toList();
        String nextCursor = hasMore && !items.isEmpty()
                ? encodeCursor(actorUserId, filterHash, upperBound, items.get(items.size() - 1)) : null;
        return new Page(items.stream().map(AuditQueryService::summary).toList(), nextCursor, hasMore);
    }

    public Detail detail(long actorUserId, String auditId) {
        requireActor(actorUserId);
        validateAuditId(auditId);
        AuditEntry entry = store.findById(auditId).orElseThrow(AuditQueryService::notFound);
        store.recordSensitiveRead(actorUserId, "DETAIL", digest("DETAIL\u0000" + auditId), 1, clock.instant());
        List<AuditCorrelationId> correlationIds = new ArrayList<>();
        if (safeCorrelation(entry.requestId(), false)) correlationIds.add(new AuditCorrelationId("requestId", entry.requestId()));
        if (safeCorrelation(entry.runId(), true)) correlationIds.add(new AuditCorrelationId("runId", entry.runId()));
        return new Detail(summary(entry), entry.safeChanges(), correlationIds,
                entry.evidenceReferenceLabel(), entry.sourceCompleteness());
    }

    public Export export(long actorUserId, String domain, String action, Long actorFilter,
                         String targetType, String targetId, String requestId, String runId,
                         String createdFrom, String createdTo) {
        requireActor(actorUserId);
        AuditFilter filter = filter(domain, action, actorFilter, targetType, targetId,
                requestId, runId, createdFrom, createdTo, true);
        Instant upperBound = clock.instant();
        List<AuditEntry> entries = store.exportSnapshot(filter, upperBound, EXPORT_MAX_ROWS, EXPORT_CHUNK_SIZE);
        store.recordSensitiveRead(actorUserId, "EXPORT", digest("EXPORT\u0000" + fingerprint(filter)),
                entries.size(), clock.instant());
        return new Export(entries.stream().map(AuditQueryService::summary).toList(), entries.size(),
                EXPORT_MAX_ROWS, true, upperBound);
    }

    private AuditFilter filter(String domainText, String actionText, Long actorFilter,
                               String targetType, String targetId, String requestId, String runId,
                               String createdFromText, String createdToText, boolean export) {
        AuditDomain domain = null;
        if (domainText != null && !domainText.isBlank()) {
            try {
                domain = AuditDomain.valueOf(domainText.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw invalid("domain is not supported");
            }
        }
        String action = normalizeOptional(actionText, "action", 64);
        if (action != null) {
            action = action.toUpperCase(Locale.ROOT);
            if (!ACTION.matcher(action).matches()) throw invalid("action is not supported");
        }
        if (actorFilter != null && actorFilter <= 0) throw invalid("actorUserId must be positive");
        String normalizedTargetType = normalizeOptional(targetType, "targetType", 64);
        if (normalizedTargetType != null) {
            normalizedTargetType = normalizedTargetType.toUpperCase(Locale.ROOT);
            if (!ACTION.matcher(normalizedTargetType).matches()) throw invalid("targetType is not supported");
        }
        String normalizedTargetId = normalizeOptional(targetId, "targetId", 128);
        if (normalizedTargetId != null && !IDENTIFIER.matcher(normalizedTargetId).matches())
            throw invalid("targetId is not supported");
        String normalizedRequestId = normalizeOptional(requestId, "requestId", 128);
        if (normalizedRequestId != null && containsLineBreak(normalizedRequestId))
            throw invalid("requestId is not supported");
        String normalizedRunId = normalizeOptional(runId, "runId", 64);
        if (normalizedRunId != null && !RUN_ID.matcher(normalizedRunId).matches())
            throw invalid("runId is not supported");

        Instant from = parseTime(createdFromText, "createdFrom");
        Instant to = parseTime(createdToText, "createdTo");
        if (from != null && to != null && !from.isBefore(to)) throw invalid("createdFrom must be before createdTo");
        if (export) {
            if (from == null || to == null) throw invalid("export requires createdFrom and createdTo");
            if (Duration.between(from, to).compareTo(EXPORT_MAX_RANGE) > 0)
                throw invalid("export range cannot exceed 31 days");
        }
        return new AuditFilter(domain, action, actorFilter, normalizedTargetType, normalizedTargetId,
                normalizedRequestId, normalizedRunId, from, to);
    }

    private Cursor decodeCursor(String raw, long actorUserId, String expectedFilterHash) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.length() > 2048) throw invalid("cursor is invalid");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)))) {
            int version = input.readInt();
            long scope = input.readLong();
            String filterHash = input.readUTF();
            Instant upperBound = Instant.ofEpochMilli(input.readLong());
            Instant beforeCreatedAt = Instant.ofEpochMilli(input.readLong());
            String beforeAuditId = input.readUTF();
            if (input.available() != 0 || version != 1 || scope != actorUserId
                    || !constantTimeEquals(filterHash, expectedFilterHash)
                    || beforeCreatedAt.isAfter(upperBound)
                    || upperBound.isAfter(clock.instant().plusSeconds(60))) {
                throw invalid("cursor does not match this administrator or filter");
            }
            validateAuditId(beforeAuditId);
            return new Cursor(upperBound, beforeCreatedAt, beforeAuditId);
        } catch (AuditQueryException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("cursor is invalid");
        }
    }

    private static String encodeCursor(long actorUserId, String filterHash, Instant upperBound, AuditEntry last) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                output.writeLong(actorUserId);
                output.writeUTF(filterHash);
                output.writeLong(upperBound.toEpochMilli());
                output.writeLong(last.createdAt().toEpochMilli());
                output.writeUTF(last.auditId());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException("Unable to encode audit cursor", error);
        }
    }

    private static String fingerprint(AuditFilter filter) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(filter.domain() == null ? "" : filter.domain().name());
                output.writeUTF(Objects.toString(filter.action(), ""));
                output.writeLong(filter.actorUserId() == null ? 0 : filter.actorUserId());
                output.writeUTF(Objects.toString(filter.targetType(), ""));
                output.writeUTF(Objects.toString(filter.targetId(), ""));
                output.writeUTF(Objects.toString(filter.requestId(), ""));
                output.writeUTF(Objects.toString(filter.runId(), ""));
                output.writeLong(filter.createdFrom() == null ? Long.MIN_VALUE : filter.createdFrom().toEpochMilli());
                output.writeLong(filter.createdTo() == null ? Long.MAX_VALUE : filter.createdTo().toEpochMilli());
            }
            return digest(bytes.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException("Unable to fingerprint audit filters", error);
        }
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static void validateAuditId(String auditId) {
        if (auditId == null || auditId.length() > 300) throw invalid("auditId is invalid");
        int separator = auditId.indexOf(':');
        if (separator <= 0 || separator == auditId.length() - 1) throw invalid("auditId is invalid");
        AuditDomain domain;
        try {
            domain = AuditDomain.valueOf(auditId.substring(0, separator));
        } catch (IllegalArgumentException error) {
            throw invalid("auditId is invalid");
        }
        String key = auditId.substring(separator + 1);
        if (domain == AuditDomain.MODEL_CONNECTION) throw invalid("auditId is invalid");
        if (domain == AuditDomain.RUN_RECOVERY) {
            if (key.length() > 256 || key.length() % 2 != 0 || !key.matches("[0-9A-Fa-f]+"))
                throw invalid("auditId is invalid");
        } else if (domain == AuditDomain.TOOL) {
            if (!key.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw invalid("auditId is invalid");
        } else if (!key.matches("[0-9]{20}")) {
            throw invalid("auditId is invalid");
        }
    }

    private static AuditSummary summary(AuditEntry entry) {
        return new AuditSummary(entry.auditId(), entry.domain(), entry.action(), entry.actorUserId(),
                entry.targetType(), entry.targetId(), safeCorrelationValue(entry.requestId(), false),
                safeCorrelationValue(entry.runId(), true), entry.outcome(), safeSummary(entry), entry.createdAt());
    }

    private static String safeSummary(AuditEntry entry) {
        return switch (entry.action()) {
            case "USER_CREATED" -> "平台账号已创建";
            case "ACTIVATION_REISSUED" -> "激活凭据已重发";
            case "ADMIN_ROLE_GRANTED" -> "平台管理员角色已授予";
            case "ADMIN_ROLE_REVOKED" -> "平台管理员角色已撤销";
            case "USER_STATUS_CHANGED" -> "平台账号状态已变更";
            case "ACCOUNT_ACTIVATED" -> "账号已激活";
            case "INITIAL_ADMIN_CREATED" -> "已初始化平台管理员";
            case "PASSWORD_CHANGED" -> "账号密码已变更";
            case "LOGIN_SUCCEEDED" -> "登录成功";
            case "LOGIN_FAILED" -> "登录失败；认证主体与来源已隐藏";
            case "ACTIVATION_SUCCEEDED" -> "账号激活成功";
            case "ACTIVATION_FAILED" -> "账号激活失败；凭据与来源已隐藏";
            case "EMPLOYEE_CREATED" -> "数字员工已创建";
            case "EMPLOYEE_PROFILE_UPDATED" -> "数字员工资料已更新";
            case "EMPLOYEE_STATUS_CHANGED" -> "数字员工状态已变更";
            case "DRAFT_SAVED" -> entry.domain() == AuditDomain.ASSET ? "能力素材草稿已保存" : "员工定义草稿已保存";
            case "DEFINITION_PUBLISHED" -> "员工定义版本已发布";
            case "CAPABILITY_STATUS_CHANGED" -> "能力状态已变更";
            case "USER_CAPABILITY_GRANT_CHANGED" -> "用户能力授权已变更";
            case "MCP_CONNECTION_CREATED" -> "MCP连接已创建；连接地址已隐藏";
            case "MCP_CONNECTION_STATUS_CHANGED" -> "MCP连接状态已变更；连接地址已隐藏";
            case "MCP_TOOL_APPROVED" -> "MCP工具已审核；远端工具正文已隐藏";
            case "PUBLISHED" -> "能力素材版本已发布";
            case "TOOL_INVOCATION" -> "工具执行记录：" + outcomeLabel(entry.outcome());
            case "RUN_RECOVERY_TERMINATED" -> "平台运行状态已标记为终止；此记录不证明旧进程或外部动作已停止";
            case "CREATE_ACCOUNT" -> "渠道账号已创建";
            case "UPDATE_ACCOUNT" -> "渠道账号已更新";
            case "LINK_IDENTITY" -> "渠道身份已绑定";
            case "REVOKE_IDENTITY" -> "渠道身份已撤销";
            default -> "管理审计记录；源动作未公开";
        };
    }

    private static String outcomeLabel(AuditOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> "成功";
            case DENIED -> "拒绝";
            case FAILED -> "失败";
            case UNKNOWN -> "未知";
        };
    }

    private static String safeCorrelationValue(String value, boolean runId) {
        return safeCorrelation(value, runId) ? value : null;
    }

    private static boolean safeCorrelation(String value, boolean runId) {
        if (value == null || value.isBlank()) return false;
        String pattern = runId ? "[A-Za-z0-9_.:-]{1,64}" : "[A-Za-z0-9_.-]{1,64}";
        if (!value.matches(pattern)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return !lower.contains("token") && !lower.contains("secret") && !lower.contains("password")
                && !lower.contains("bearer") && !lower.matches("[0-9a-f]{40,}");
    }

    private static String normalizeOptional(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength || containsLineBreak(normalized)) throw invalid(field + " is invalid");
        return normalized;
    }

    private static Instant parseTime(String raw, String field) {
        String value = normalizeOptional(raw, field, 64);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException error) {
            throw invalid(field + " must be ISO-8601 with an offset");
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    private static void requireActor(long actorUserId) {
        if (actorUserId <= 0) throw invalid("authenticated administrator is required");
    }

    private static AuditQueryException invalid(String message) {
        return new AuditQueryException(Error.INVALID_QUERY, message);
    }

    private static AuditQueryException notFound() {
        return new AuditQueryException(Error.NOT_FOUND, "Audit record was not found");
    }

    public enum Error { INVALID_QUERY, NOT_FOUND, EXPORT_TOO_LARGE }

    public static final class AuditQueryException extends RuntimeException {
        private final Error error;

        public AuditQueryException(Error error, String message) {
            super(message);
            this.error = Objects.requireNonNull(error);
        }

        public Error error() { return error; }

        public static AuditQueryException exportTooLarge() {
            return new AuditQueryException(Error.EXPORT_TOO_LARGE, "Export exceeds the 10,000 row limit");
        }
    }

    public record AuditSummary(String auditId, AuditDomain domain, String action, Long actorUserId,
                              String targetType, String targetId, String requestId, String runId,
                              AuditOutcome outcome, String safeSummary, Instant createdAt) { }

    public record Page(List<AuditSummary> items, String nextCursor, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }

    public record Detail(AuditSummary summary, List<AuditChange> safeChanges,
                         List<AuditCorrelationId> correlationIds, String evidenceReferenceLabel,
                         AuditSourceCompleteness sourceCompleteness) {
        public Detail {
            safeChanges = List.copyOf(safeChanges);
            correlationIds = List.copyOf(correlationIds);
        }
    }

    public record Export(List<AuditSummary> items, int count, int maximumRows,
                         boolean complete, Instant upperBound) {
        public Export { items = List.copyOf(items); }
    }

    private record Cursor(Instant upperBound, Instant beforeCreatedAt, String beforeAuditId) { }
}
