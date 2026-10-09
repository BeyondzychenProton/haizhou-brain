package com.haizhuo.brain.infrastructure.employee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.EmployeeAdminCommandExecutor;
import com.haizhuo.brain.platform.employee.EmployeeAdministrationStore;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeConfiguration;
import com.haizhuo.brain.platform.employee.EmployeeRuntimeProfileSettings;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 基于 MySQL 实现数字员工的管理员查询与生命周期命令。 */
@Repository
@Profile("!test")
public class JdbcEmployeeAdministrationStore implements EmployeeAdministrationStore {
    private static final long TENANT_ID = 1L;
    private static final RowMapper<EmployeeSummary> EMPLOYEE_MAPPER = JdbcEmployeeAdministrationStore::mapEmployee;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final EmployeeAdminCommandExecutor commands;
    private final EmployeeRuntimeProfileSettings profileSettings;

    public JdbcEmployeeAdministrationStore(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                           ObjectMapper json, EmployeeAdminCommandExecutor commands,
                                           EmployeeRuntimeProfileSettings profileSettings) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.commands = commands;
        this.profileSettings = profileSettings;
    }

    @Override
    public Optional<EmployeeSummary> findEmployee(long employeeId) {
        return jdbc.query("SELECT e.id,e.employee_code,e.display_name,e.enabled,e.current_published_version_id,e.row_version,e.created_at "
                        + "FROM digital_employee e WHERE e.tenant_id=? AND e.id=?",
                EMPLOYEE_MAPPER, TENANT_ID, employeeId).stream().findFirst();
    }

    @Override
    public EmployeePage listEmployees(String query, Boolean enabled, Boolean published, String cursor, int limit) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("query", query == null ? "" : query.toLowerCase(java.util.Locale.ROOT));
        filters.put("enabled", enabled);
        filters.put("published", published);
        String filterHash = CanonicalJson.sha256(filters);
        Cursor after = decodeCursor(cursor, "employees", filterHash);
        StringBuilder sql = new StringBuilder("SELECT e.id,e.employee_code,e.display_name,e.enabled,e.current_published_version_id,e.row_version,e.created_at "
                + "FROM digital_employee e WHERE e.tenant_id=?");
        List<Object> args = new ArrayList<>();
        args.add(TENANT_ID);
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(e.employee_code) LIKE ? OR LOWER(e.display_name) LIKE ?)");
            String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        if (enabled != null) {
            sql.append(" AND e.enabled=?");
            args.add(enabled);
        }
        if (published != null) {
            sql.append(" AND (e.current_published_version_id IS NOT NULL)=?");
            args.add(published);
        }
        if (after != null) {
            sql.append(" AND (e.created_at<? OR (e.created_at=? AND e.id<?))");
            args.add(Timestamp.from(after.at()));
            args.add(Timestamp.from(after.at()));
            args.add(after.id());
        }
        sql.append(" ORDER BY e.created_at DESC,e.id DESC LIMIT ?");
        args.add(limit + 1);
        List<EmployeeSummary> rows = jdbc.query(sql.toString(), EMPLOYEE_MAPPER, args.toArray());
        boolean hasMore = rows.size() > limit;
        List<EmployeeSummary> items = rows.stream().limit(limit).toList();
        String next = hasMore && !items.isEmpty() ? encodeCursor("employees", filterHash,
                items.get(items.size() - 1).createdAt(), items.get(items.size() - 1).employeeId()) : null;
        return new EmployeePage(items, next, hasMore);
    }

    @Override
    public CreatedEmployee createEmployee(String employeeCode, String displayName, long actorId,
                                         String requestId, String requestHash, String reason) {
        var command = new EmployeeAdminCommandExecutor.Command(actorId, "CREATE", null, requestId, requestHash);
        try {
            return commands.execute(command, CreatedEmployee.class, () -> Objects.requireNonNull(tx.execute(status -> {
                Integer duplicate = jdbc.query("SELECT COUNT(*) FROM digital_employee WHERE tenant_id=? AND employee_code=?",
                        rs -> rs.next() ? rs.getInt(1) : 0, TENANT_ID, employeeCode);
                if (duplicate != null && duplicate > 0) throw new IllegalStateException("EMPLOYEE_CODE_CONFLICT");
                List<Long> allocated = jdbc.query("SELECT next_id FROM platform_id_allocator WHERE scope_key='digital_employee' FOR UPDATE",
                        (rs, row) -> rs.getLong(1));
                if (allocated.isEmpty()) throw new IllegalStateException("EMPLOYEE_ID_ALLOCATOR_UNAVAILABLE");
                long employeeId = allocated.get(0);
                jdbc.update("UPDATE platform_id_allocator SET next_id=? WHERE scope_key='digital_employee'", Math.addExact(employeeId, 1));
                Instant now = Instant.now();
                jdbc.update("INSERT INTO digital_employee(id,tenant_id,employee_code,display_name,enabled,current_published_version_id,row_version,created_at) "
                                + "VALUES(?,?,?,?,FALSE,NULL,0,?)",
                        employeeId, TENANT_ID, employeeCode, displayName, Timestamp.from(now));
                String provider = "openai";
                String model = "qwen3.7-max";
                List<String[]> defaults = jdbc.query("SELECT model_provider,model_name FROM agent_definition_draft ORDER BY employee_id LIMIT 1",
                        (rs, row) -> new String[]{rs.getString(1), rs.getString(2)});
                if (!defaults.isEmpty()) {
                    provider = defaults.get(0)[0];
                    model = defaults.get(0)[1];
                }
                String instructions = "请补充此员工的工作指令，并在发布前完成能力配置。";
                EmployeeRuntimeConfiguration configuration = EmployeeRuntimeConfiguration.legacyStable();
                jdbc.update("INSERT INTO agent_definition_draft(employee_id,draft_revision,instructions,model_provider,model_name,configuration_json,updated_by,updated_at) "
                                + "VALUES(?,1,?,?,?,?,?,?)",
                        employeeId, instructions, provider, model, writeJson(configuration), actorId, Timestamp.from(now));
                EmployeeSummary employee = findEmployee(employeeId).orElseThrow();
                AgentDefinitionDraft draft = new AgentDefinitionDraft(employeeId, 1, instructions, provider, model,
                        List.of(), configuration, now);
                appendAudit(actorId, "EMPLOYEE_CREATED", employeeId, requestId, reason,
                        "{}", writeJson(Map.of("employeeId", employeeId, "employeeCode", employeeCode,
                                "displayName", displayName, "enabled", false, "published", false)), now);
                return new CreatedEmployee(employee, draft);
            }), "Employee create transaction did not return a result"));
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("EMPLOYEE_CODE_CONFLICT", duplicate);
        }
    }

    @Override
    public EmployeeSummary updateEmployee(long employeeId, long expectedRowVersion, String displayName,
                                          long actorId, String requestId, String requestHash, String reason) {
        var command = new EmployeeAdminCommandExecutor.Command(actorId, "UPDATE_DISPLAY_NAME", employeeId, requestId, requestHash);
        return commands.execute(command, EmployeeSummary.class, () -> Objects.requireNonNull(tx.execute(status -> {
            EmployeeSummary before = lockEmployee(employeeId);
            if (before.rowVersion() != expectedRowVersion) throw new IllegalStateException("EMPLOYEE_ROW_VERSION_STALE");
            int changed = jdbc.update("UPDATE digital_employee SET display_name=?,row_version=row_version+1 WHERE tenant_id=? AND id=? AND row_version=?",
                    displayName, TENANT_ID, employeeId, expectedRowVersion);
            if (changed != 1) throw new IllegalStateException("EMPLOYEE_ROW_VERSION_STALE");
            EmployeeSummary after = findEmployee(employeeId).orElseThrow();
            Instant now = Instant.now();
            appendAudit(actorId, "EMPLOYEE_PROFILE_UPDATED", employeeId, requestId, reason,
                    summaryJson(before), summaryJson(after), now);
            return after;
        }), "Employee update transaction did not return a result"));
    }

    @Override
    public EmployeeSummary setEmployeeEnabled(long employeeId, long expectedRowVersion, boolean enabled,
                                              long actorId, String requestId, String requestHash, String reason) {
        var command = new EmployeeAdminCommandExecutor.Command(actorId, "SET_STATUS", employeeId, requestId, requestHash);
        return commands.execute(command, EmployeeSummary.class, () -> Objects.requireNonNull(tx.execute(status -> {
            EmployeeSummary before;
            if (enabled) {
                before = lockEmployeeForEnablement(employeeId, expectedRowVersion);
            } else {
                before = lockEmployee(employeeId);
                if (before.rowVersion() != expectedRowVersion)
                    throw new IllegalStateException("EMPLOYEE_ROW_VERSION_STALE");
            }
            int changed = jdbc.update("UPDATE digital_employee SET enabled=?,row_version=row_version+1 WHERE tenant_id=? AND id=? AND row_version=?",
                    enabled, TENANT_ID, employeeId, expectedRowVersion);
            if (changed != 1) throw new IllegalStateException("EMPLOYEE_ROW_VERSION_STALE");
            EmployeeSummary after = findEmployee(employeeId).orElseThrow();
            Instant now = Instant.now();
            appendAudit(actorId, "EMPLOYEE_STATUS_CHANGED", employeeId, requestId, reason,
                    summaryJson(before), summaryJson(after), now);
            return after;
        }), "Employee status transaction did not return a result"));
    }

    /**
     * 持有行锁时重新检查所有可变运行依赖。加锁顺序为员工 ID 升序，再按能力编码升序。
     * 员工发布/状态写入者只锁一条员工记录，能力状态写入者只锁一条能力记录，
     * 因此现有写入路径不会以相反顺序获取这些资源锁。
     */
    private EmployeeSummary lockEmployeeForEnablement(long employeeId, long expectedRowVersion) {
        EmployeeSummary observed = findEmployee(employeeId).orElseThrow(() -> new IllegalArgumentException("Digital employee not found"));
        Long versionId = observed.currentPublishedVersionId();
        if (versionId == null) throw new IllegalStateException("EMPLOYEE_NOT_PUBLISHED");

        List<Long> memberIds = jdbc.query("SELECT member_employee_id FROM agent_definition_version_member "
                        + "WHERE parent_definition_version_id=? ORDER BY member_employee_id",
                (rs, row) -> rs.getLong(1), versionId);
        List<Long> employeeIds = new ArrayList<>(memberIds);
        employeeIds.add(employeeId);
        List<Long> orderedEmployeeIds = employeeIds.stream().distinct().sorted().toList();
        for (long id : orderedEmployeeIds) {
            List<Long> locked = jdbc.query("SELECT id FROM digital_employee WHERE tenant_id=? AND id=? FOR UPDATE",
                    (rs, row) -> rs.getLong(1), TENANT_ID, id);
            if (locked.isEmpty()) throw new IllegalStateException("DEPENDENCY_UNAVAILABLE");
        }

        EmployeeSummary current = findEmployee(employeeId).orElseThrow();
        if (current.rowVersion() != expectedRowVersion)
            throw new IllegalStateException("EMPLOYEE_ROW_VERSION_STALE");
        if (!Objects.equals(versionId, current.currentPublishedVersionId()))
            throw new IllegalStateException("EMPLOYEE_PUBLISHED_VERSION_CHANGED");

        List<String> capabilityCodes = jdbc.query("SELECT capability_code FROM agent_definition_version_capability "
                        + "WHERE definition_version_id=? ORDER BY capability_code",
                (rs, row) -> rs.getString(1), versionId);
        Map<String, String> capabilityStatuses = new LinkedHashMap<>();
        for (String code : capabilityCodes.stream().distinct().sorted().toList()) {
            List<String> rows = jdbc.query("SELECT status FROM capability_definition WHERE capability_code=? FOR UPDATE",
                    (rs, row) -> rs.getString(1), code);
            capabilityStatuses.put(code, rows.isEmpty() ? null : rows.get(0));
        }

        EmployeeVersionDetail version = findVersion(current.employeeId(), versionId)
                .orElseThrow(() -> new IllegalStateException("EMPLOYEE_NOT_PUBLISHED"));
        if (!profileSettings.enabledProfiles().contains(version.summary().profile()))
            throw new IllegalStateException("PROFILE_DISABLED");
        if (!version.runtimeBundleAvailable()) throw new IllegalStateException("RUNTIME_BUNDLE_MISSING");
        if (capabilityStatuses.values().stream().anyMatch(status -> !"ACTIVE".equals(status)))
            throw new IllegalStateException("DEPENDENCY_UNAVAILABLE");
        if (version.members().stream().anyMatch(member -> !member.employeeEnabled()
                || member.profile() != RuntimeProfile.LEGACY_STABLE && member.profile() != RuntimeProfile.SINGLE_SKILLED))
            throw new IllegalStateException("DEPENDENCY_UNAVAILABLE");
        return current;
    }

    @Override
    public EmployeeVersionPage listVersions(long employeeId, String cursor, int limit) {
        if (findEmployee(employeeId).isEmpty()) throw new IllegalArgumentException("Digital employee not found");
        String filterHash = CanonicalJson.sha256(Map.of("employeeId", employeeId));
        Cursor after = decodeCursor(cursor, "versions", filterHash);
        StringBuilder sql = new StringBuilder("SELECT v.id,v.employee_id,v.version_no,v.instructions,v.model_provider,v.model_name,v.content_hash,v.configuration_json,v.published_by,v.published_at,e.current_published_version_id "
                + "FROM digital_employee e JOIN agent_definition_version v ON v.employee_id=e.id "
                + "WHERE e.tenant_id=? AND e.id=?");
        List<Object> args = new ArrayList<>(List.of(TENANT_ID, employeeId));
        if (after != null) {
            sql.append(" AND (v.published_at<? OR (v.published_at=? AND v.id<?))");
            args.add(Timestamp.from(after.at()));
            args.add(Timestamp.from(after.at()));
            args.add(after.id());
        }
        sql.append(" ORDER BY v.published_at DESC,v.id DESC LIMIT ?");
        args.add(limit + 1);
        List<VersionRow> rows = jdbc.query(sql.toString(), (rs, row) -> versionRow(rs), args.toArray());
        boolean hasMore = rows.size() > limit;
        List<EmployeeVersionSummary> items = rows.stream().limit(limit).map(this::summary).toList();
        String next = hasMore && !rows.isEmpty() ? encodeCursor("versions", filterHash,
                rows.get(limit - 1).publishedAt(), rows.get(limit - 1).id()) : null;
        return new EmployeeVersionPage(items, next, hasMore);
    }

    @Override
    public Optional<EmployeeVersionDetail> findVersion(long employeeId, long versionId) {
        List<VersionRow> rows = jdbc.query("SELECT v.id,v.employee_id,v.version_no,v.instructions,v.model_provider,v.model_name,v.content_hash,v.configuration_json,v.published_by,v.published_at,e.current_published_version_id "
                        + "FROM digital_employee e JOIN agent_definition_version v ON v.employee_id=e.id "
                        + "WHERE e.tenant_id=? AND e.id=? AND v.id=?",
                (rs, row) -> versionRow(rs), TENANT_ID, employeeId, versionId);
        if (rows.isEmpty()) return Optional.empty();
        VersionRow row = rows.get(0);
        List<CapabilityDetail> capabilities = jdbc.query("SELECT b.capability_code,b.capability_revision,r.display_name,d.capability_type,r.content_hash,d.status "
                        + "FROM agent_definition_version_capability b JOIN capability_definition d ON d.capability_code=b.capability_code "
                        + "JOIN capability_revision r ON r.capability_code=b.capability_code AND r.revision=b.capability_revision "
                        + "WHERE b.definition_version_id=? ORDER BY b.position_no",
                (rs, position) -> new CapabilityDetail(rs.getString("capability_code"), rs.getString("capability_revision"),
                        rs.getString("display_name"), rs.getString("capability_type"), rs.getString("content_hash"),
                        "ACTIVE".equals(rs.getString("status"))), versionId);
        List<MemberDetail> members = jdbc.query("SELECT m.member_role_id,m.member_employee_id,member.employee_code,member.display_name,member.enabled "
                        + "member_enabled,v.id member_version_id,v.version_no,v.content_hash,v.configuration_json,m.member_steps "
                        + "FROM agent_definition_version_member m JOIN digital_employee member ON member.id=m.member_employee_id "
                        + "JOIN agent_definition_version v ON v.id=m.member_definition_version_id AND v.employee_id=m.member_employee_id "
                        + "WHERE m.parent_definition_version_id=? ORDER BY m.position_no",
                (rs, position) -> new MemberDetail(rs.getString("member_role_id"), rs.getLong("member_employee_id"),
                        rs.getString("employee_code"), rs.getString("display_name"), rs.getLong("member_version_id"),
                        rs.getInt("version_no"), rs.getString("content_hash"), profile(rs.getString("configuration_json")),
                        rs.getInt("member_steps"), rs.getBoolean("member_enabled")), versionId);
        Integer bundles = jdbc.query("SELECT COUNT(*) FROM agent_definition_runtime_bundle WHERE definition_version_id=?",
                rs -> rs.next() ? rs.getInt(1) : 0, versionId);
        return Optional.of(new EmployeeVersionDetail(summary(row), row.instructions(), row.modelProvider(), row.modelName(),
                configuration(row.configurationJson()), capabilities, members, bundles != null && bundles > 0));
    }

    private EmployeeSummary lockEmployee(long employeeId) {
        List<EmployeeSummary> rows = jdbc.query("SELECT e.id,e.employee_code,e.display_name,e.enabled,e.current_published_version_id,e.row_version,e.created_at "
                        + "FROM digital_employee e WHERE e.tenant_id=? AND e.id=? FOR UPDATE",
                EMPLOYEE_MAPPER, TENANT_ID, employeeId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Digital employee not found");
        return rows.get(0);
    }

    private void appendAudit(long actorId, String event, long employeeId, String requestId, String reason,
                             String before, String after, Instant at) {
        jdbc.update("INSERT INTO agent_definition_management_audit(actor_user_id,event_type,target_type,target_id,request_id,reason,previous_summary,new_summary,occurred_at) "
                        + "VALUES(?,?,'DIGITAL_EMPLOYEE',?,?,?,?,?,?)",
                actorId, event, String.valueOf(employeeId), requestId, reason.trim(), before, after, Timestamp.from(at));
    }

    private String summaryJson(EmployeeSummary item) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("employeeId", item.employeeId());
        values.put("employeeCode", item.employeeCode());
        values.put("displayName", item.displayName());
        values.put("enabled", item.enabled());
        values.put("published", item.published());
        values.put("currentPublishedVersionId", item.currentPublishedVersionId());
        values.put("rowVersion", item.rowVersion());
        return writeJson(values);
    }

    private static EmployeeSummary mapEmployee(ResultSet rs, int row) throws SQLException {
        Long current = rs.getObject("current_published_version_id", Long.class);
        return new EmployeeSummary(rs.getLong("id"), rs.getString("employee_code"), rs.getString("display_name"),
                rs.getBoolean("enabled"), current != null, current, rs.getLong("row_version"),
                rs.getTimestamp("created_at").toInstant());
    }

    private VersionRow versionRow(ResultSet rs) throws SQLException {
        return new VersionRow(rs.getLong("id"), rs.getLong("employee_id"), rs.getInt("version_no"),
                rs.getString("instructions"), rs.getString("model_provider"), rs.getString("model_name"),
                rs.getString("content_hash"), rs.getString("configuration_json"), rs.getLong("published_by"),
                rs.getTimestamp("published_at").toInstant(), rs.getObject("current_published_version_id", Long.class));
    }

    private EmployeeVersionSummary summary(VersionRow row) {
        return new EmployeeVersionSummary(row.id(), row.employeeId(), row.versionNo(), row.contentHash(),
                profile(row.configurationJson()), row.publishedBy(), row.publishedAt(), Objects.equals(row.id(), row.currentVersionId()));
    }

    private RuntimeProfile profile(String raw) { return configuration(raw).profile(); }

    private EmployeeRuntimeConfiguration configuration(String raw) {
        if (raw == null || raw.isBlank()) return EmployeeRuntimeConfiguration.legacyStable();
        try { return json.readValue(raw, EmployeeRuntimeConfiguration.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid employee runtime configuration JSON", e); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize employee administration data", e); }
    }

    private String encodeCursor(String kind, String filterHash, Instant at, long id) {
        try {
            byte[] data = json.writeValueAsBytes(Map.of("kind", kind, "filter", filterHash, "at", at.toString(), "id", id));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        } catch (Exception e) { throw new IllegalStateException("Could not encode employee cursor", e); }
    }

    private Cursor decodeCursor(String raw, String kind, String filterHash) {
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode node = json.readTree(Base64.getUrlDecoder().decode(raw));
            if (!kind.equals(node.path("kind").asText()) || !filterHash.equals(node.path("filter").asText()))
                throw new IllegalArgumentException("Cursor does not match this list query");
            long id = node.path("id").asLong(0);
            Instant at = Instant.parse(node.path("at").asText());
            if (id <= 0) throw new IllegalArgumentException("Cursor is invalid");
            return new Cursor(at, id);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cursor is invalid", e);
        }
    }

    private record Cursor(Instant at, long id) {}
    private record VersionRow(long id, long employeeId, int versionNo, String instructions, String modelProvider,
                              String modelName, String contentHash, String configurationJson, long publishedBy,
                              Instant publishedAt, Long currentVersionId) {}
}
