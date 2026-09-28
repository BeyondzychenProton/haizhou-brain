package com.haizhuo.brain.infrastructure.identity;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.ActivationToken;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.security.identity.PlatformUser;
import com.haizhuo.brain.security.identity.PlatformUserAudit;
import com.haizhuo.brain.security.identity.PlatformUserStatus;
import com.haizhuo.brain.security.identity.port.PlatformIdentityRepository;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 阻塞式 JDBC 适配器。WebFlux 调用方需先把服务调用调度到 boundedElastic 再进入本类。 */
@Repository
public class JdbcPlatformIdentityRepository implements PlatformIdentityRepository {
    private static final RowMapper<PlatformUser> USER_MAPPER = (rs, row) -> new PlatformUser(
            new UserId(rs.getLong("id")),
            rs.getString("mobile_normalized"),
            rs.getString("password_hash"),
            PlatformUserStatus.valueOf(rs.getString("status")),
            rs.getLong("auth_version"),
            rs.getBoolean("must_change_password"));

    private static final RowMapper<ActivationToken> ACTIVATION_MAPPER = (rs, row) -> new ActivationToken(
            rs.getLong("id"), new UserId(rs.getLong("user_id")), rs.getString("selector"), rs.getString("token_hash"),
            rs.getTimestamp("expires_at").toInstant(), instantOrNull(rs.getTimestamp("used_at")),
            instantOrNull(rs.getTimestamp("revoked_at")));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public JdbcPlatformIdentityRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    @Override
    public Optional<PlatformUser> findByMobile(String mobileNormalized) {
        return first(namedJdbc.query("SELECT * FROM platform_user WHERE mobile_normalized=:mobile", parameters().addValue("mobile", mobileNormalized), USER_MAPPER));
    }

    @Override
    public Optional<PlatformUser> findById(UserId userId) {
        return first(namedJdbc.query("SELECT * FROM platform_user WHERE id=:id", parameters().addValue("id", userId.value()), USER_MAPPER));
    }

    @Override
    public Optional<PlatformUser> findByIdForUpdate(UserId userId) {
        return first(namedJdbc.query("SELECT * FROM platform_user WHERE id=:id FOR UPDATE", parameters().addValue("id", userId.value()), USER_MAPPER));
    }

    @Override
    public void lockAdministration() {
        jdbc.queryForObject("SELECT lock_key FROM platform_administration_lock WHERE lock_key='ADMINISTRATION' FOR UPDATE", String.class);
    }

    @Override
    public long countAdministratorRoles() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM platform_user_role WHERE role_code='PLATFORM_ADMIN'", Long.class);
    }

    @Override
    public long countActiveAdministrators() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM platform_user u JOIN platform_user_role r ON r.user_id=u.id "
                + "WHERE u.status='ACTIVE' AND r.role_code='PLATFORM_ADMIN'", Long.class);
    }

    @Override
    public Set<PlatformRole> findRoles(UserId userId) {
        return findRoles(List.of(userId)).getOrDefault(userId, Set.of());
    }

    @Override
    public Map<UserId, Set<PlatformRole>> findRoles(Collection<UserId> userIds) {
        List<UserId> distinct = userIds.stream().distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        Map<UserId, Set<PlatformRole>> roles = distinct.stream()
                .collect(Collectors.toMap(user -> user, user -> EnumSet.noneOf(PlatformRole.class)));
        namedJdbc.query("SELECT user_id,role_code FROM platform_user_role WHERE user_id IN (:ids)",
                        parameters().addValue("ids", distinct.stream().map(UserId::value).toList()),
                        (rs, row) -> Map.entry(rs.getLong("user_id"), rs.getString("role_code")))
                .forEach(entry -> roles.get(new UserId(entry.getKey())).add(PlatformRole.valueOf(entry.getValue())));
        return roles.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> Collections.unmodifiableSet(entry.getValue())));
    }

    @Override
    public List<PlatformUser> searchUsers(String keyword, PlatformUserStatus status, int limit, int offset) {
        return namedJdbc.query("SELECT * FROM platform_user " + userFilter()
                        + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                userFilterParameters(keyword, status).addValue("limit", limit).addValue("offset", offset), USER_MAPPER);
    }

    @Override
    public long countUsers(String keyword, PlatformUserStatus status) {
        Long total = namedJdbc.queryForObject("SELECT COUNT(*) FROM platform_user " + userFilter(),
                userFilterParameters(keyword, status), Long.class);
        return total == null ? 0L : total;
    }

    /** 关键词与状态都是可选过滤项，为空时退化成匹配全部的条件。 */
    private static String userFilter() {
        return "WHERE (:keyword IS NULL OR mobile_normalized LIKE CONCAT('%', :keyword, '%')) "
                + "AND (:status IS NULL OR status = :status)";
    }

    private static MapSqlParameterSource userFilterParameters(String keyword, PlatformUserStatus status) {
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();
        // null 参数必须显式给出 JDBC 类型，否则驱动推断不出来会直接报错。
        return parameters()
                .addValue("keyword", trimmed, Types.VARCHAR)
                .addValue("status", status == null ? null : status.name(), Types.VARCHAR);
    }

    @Override
    public PlatformUser createUser(String mobileNormalized, String passwordHash, PlatformUserStatus status,
                                   boolean mustChangePassword, UserId createdBy, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource values = parameters()
                .addValue("mobile", mobileNormalized)
                .addValue("passwordHash", passwordHash)
                .addValue("status", status.name())
                .addValue("mustChange", mustChangePassword)
                .addValue("createdBy", createdBy == null ? null : createdBy.value(), Types.BIGINT)
                .addValue("now", now);
        namedJdbc.update("INSERT INTO platform_user (mobile_normalized,password_hash,status,auth_version,must_change_password,created_by,created_at,updated_at,row_version) "
                        + "VALUES (:mobile,:passwordHash,:status,1,:mustChange,:createdBy,:now,:now,0)", values, keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("创建用户时未生成主键");
        return new PlatformUser(new UserId(id.longValue()), mobileNormalized, passwordHash, status, 1, mustChangePassword);
    }

    @Override
    public boolean updatePassword(UserId userId, long expectedAuthVersion, String passwordHash,
                                  PlatformUserStatus status, boolean mustChangePassword, Instant now) {
        int changed = namedJdbc.update("UPDATE platform_user SET password_hash=:passwordHash,status=:status,must_change_password=:mustChange,"
                        + "auth_version=auth_version+1,updated_at=:now,row_version=row_version+1 "
                        + "WHERE id=:id AND auth_version=:expectedVersion", parameters()
                .addValue("id", userId.value()).addValue("expectedVersion", expectedAuthVersion)
                .addValue("passwordHash", passwordHash).addValue("status", status.name())
                .addValue("mustChange", mustChangePassword).addValue("now", now));
        return changed == 1;
    }

    @Override
    public boolean updateStatus(UserId userId, long expectedAuthVersion, PlatformUserStatus status, Instant now) {
        int changed = namedJdbc.update("UPDATE platform_user SET status=:status,auth_version=auth_version+1,updated_at=:now,row_version=row_version+1 "
                        + "WHERE id=:id AND auth_version=:expectedVersion", parameters()
                .addValue("id", userId.value()).addValue("expectedVersion", expectedAuthVersion)
                .addValue("status", status.name()).addValue("now", now));
        return changed == 1;
    }

    @Override
    public void grantRole(UserId userId, PlatformRole role, UserId grantedBy, Instant now) {
        namedJdbc.update("INSERT INTO platform_user_role (user_id,role_code,granted_by,granted_at) VALUES (:id,:role,:grantedBy,:now) "
                        + "ON DUPLICATE KEY UPDATE granted_by=VALUES(granted_by),granted_at=VALUES(granted_at)", parameters()
                .addValue("id", userId.value()).addValue("role", role.name())
                .addValue("grantedBy", grantedBy == null ? null : grantedBy.value(), Types.BIGINT).addValue("now", now));
    }

    @Override
    public void revokeRole(UserId userId, PlatformRole role) {
        namedJdbc.update("DELETE FROM platform_user_role WHERE user_id=:id AND role_code=:role",
                parameters().addValue("id", userId.value()).addValue("role", role.name()));
    }

    @Override
    public void revokeUnusedActivationTokens(UserId userId, Instant now) {
        namedJdbc.update("UPDATE platform_activation_token SET revoked_at=:now WHERE user_id=:id AND used_at IS NULL AND revoked_at IS NULL",
                parameters().addValue("id", userId.value()).addValue("now", now));
    }

    @Override
    public ActivationToken createActivationToken(UserId userId, String selector, String tokenHash,
                                                 Instant expiresAt, UserId createdBy, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        namedJdbc.update("INSERT INTO platform_activation_token (user_id,selector,token_hash,expires_at,used_at,revoked_at,created_by,created_at) "
                        + "VALUES (:userId,:selector,:tokenHash,:expiresAt,NULL,NULL,:createdBy,:now)", parameters()
                .addValue("userId", userId.value()).addValue("selector", selector).addValue("tokenHash", tokenHash)
                .addValue("expiresAt", expiresAt).addValue("createdBy", createdBy == null ? null : createdBy.value(), Types.BIGINT)
                .addValue("now", now), keyHolder, new String[]{"id"});
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("创建激活凭据时未生成主键");
        return new ActivationToken(id.longValue(), userId, selector, tokenHash, expiresAt, null, null);
    }

    @Override
    public Optional<ActivationToken> findActivationBySelectorForUpdate(String selector) {
        return first(namedJdbc.query("SELECT * FROM platform_activation_token WHERE selector=:selector FOR UPDATE",
                parameters().addValue("selector", selector), ACTIVATION_MAPPER));
    }

    @Override
    public boolean consumeActivationToken(long tokenId, UserId userId, String passwordHash, Instant now) {
        int consumed = namedJdbc.update("UPDATE platform_activation_token SET used_at=:now WHERE id=:id AND user_id=:userId "
                        + "AND used_at IS NULL AND revoked_at IS NULL AND expires_at>:now", parameters()
                .addValue("id", tokenId).addValue("userId", userId.value()).addValue("now", now));
        if (consumed != 1) return false;
        int activated = namedJdbc.update("UPDATE platform_user SET password_hash=:passwordHash,status='ACTIVE',must_change_password=FALSE,"
                        + "auth_version=auth_version+1,updated_at=:now,row_version=row_version+1 "
                        + "WHERE id=:userId AND status='PENDING_ACTIVATION'", parameters()
                .addValue("userId", userId.value()).addValue("passwordHash", passwordHash).addValue("now", now));
        return activated == 1;
    }

    @Override
    public void appendAudit(PlatformUserAudit audit) {
        namedJdbc.update("INSERT INTO platform_user_audit (actor_user_id,target_user_id,event_type,previous_state,new_state,reason,occurred_at) "
                        + "VALUES (:actor,:target,:eventType,:previous,:next,:reason,:occurredAt)", parameters()
                .addValue("actor", audit.actorUserId() == null ? null : audit.actorUserId().value(), Types.BIGINT)
                .addValue("target", audit.targetUserId().value()).addValue("eventType", audit.eventType())
                .addValue("previous", audit.previousState()).addValue("next", audit.newState())
                .addValue("reason", audit.reason()).addValue("occurredAt", audit.occurredAt()));
    }

    @Override
    public void appendAuthenticationAudit(String eventType, String subjectDigest, String sourceDigest,
                                          String failureCategory, Instant occurredAt) {
        namedJdbc.update("INSERT INTO platform_authentication_audit (event_type,subject_digest,source_digest,failure_category,occurred_at) "
                        + "VALUES (:eventType,:subjectDigest,:sourceDigest,:failureCategory,:occurredAt)", parameters()
                .addValue("eventType", eventType).addValue("subjectDigest", subjectDigest)
                .addValue("sourceDigest", sourceDigest).addValue("failureCategory", failureCategory)
                .addValue("occurredAt", occurredAt));
    }

    private static MapSqlParameterSource parameters() {
        return new MapSqlParameterSource();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    private static Instant instantOrNull(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
