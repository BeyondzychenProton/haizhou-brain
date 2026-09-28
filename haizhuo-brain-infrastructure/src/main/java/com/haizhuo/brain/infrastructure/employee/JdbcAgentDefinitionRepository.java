package com.haizhuo.brain.infrastructure.employee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.employee.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 已发布 Agent 定义、授权、运行时快照与工具审计的 MySQL 实现。 */
@Repository
@Profile("!test")
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcAgentDefinitionRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
    }

    @Override public Optional<PublishedEmployee> findPublished(TenantId tenantId, long employeeId) {
        List<DefinitionRow> rows = jdbc.query("SELECT e.id employee_id,e.tenant_id,e.employee_code,e.display_name,e.enabled,v.id version_id,v.version_no,v.instructions,v.model_provider,v.model_name,v.content_hash,v.published_at FROM digital_employee e JOIN agent_definition_version v ON v.id=e.current_published_version_id WHERE e.tenant_id=? AND e.id=? AND e.enabled=TRUE",
                (rs, n) -> definitionRow(rs), tenantId.value(), employeeId);
        if (rows.isEmpty()) return Optional.empty();
        DefinitionRow row = rows.get(0);
        List<CapabilityBinding> bindings = jdbc.query("SELECT b.capability_code,b.capability_revision,d.capability_type FROM agent_definition_version_capability b JOIN capability_definition d ON d.capability_code=b.capability_code WHERE b.definition_version_id=? ORDER BY b.position_no",
                (rs, n) -> new CapabilityBinding(row.versionId(), CapabilityBinding.CapabilityType.valueOf(rs.getString("capability_type")),
                        rs.getString("capability_code"), rs.getString("capability_revision")), row.versionId());
        DigitalEmployee employee = new DigitalEmployee(row.employeeId(), new TenantId(row.tenantId()), row.employeeCode(), row.displayName(), row.employeeEnabled());
        AgentDefinitionVersion version = new AgentDefinitionVersion(row.versionId(), row.employeeId(), row.versionNo(), row.instructions(), row.modelProvider(), row.modelName(), row.publishedAt(), row.contentHash());
        return Optional.of(new PublishedEmployee(employee, version, bindings));
    }

    @Override public Optional<AgentDefinitionDraft> findDraft(long employeeId) {
        List<DraftRow> rows = jdbc.query("SELECT draft_revision,instructions,model_provider,model_name,updated_at FROM agent_definition_draft WHERE employee_id=?",
                (rs, n) -> new DraftRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), employeeId);
        if (rows.isEmpty()) return Optional.empty();
        DraftRow row = rows.get(0);
        List<CapabilitySelection> selected = jdbc.query("SELECT capability_code,capability_revision FROM agent_definition_draft_capability WHERE employee_id=? ORDER BY position_no",
                (rs, n) -> new CapabilitySelection(rs.getString(1), rs.getString(2)), employeeId);
        return Optional.of(new AgentDefinitionDraft(employeeId, row.revision(), row.instructions(), row.provider(), row.model(), selected, row.updatedAt()));
    }

    @Override public List<CapabilityCatalogEntry> listCapabilities() {
        return jdbc.query("SELECT r.capability_revision_id,d.capability_code,d.capability_type,d.status,r.revision,r.display_name,r.description,r.tool_name,r.implementation_key,r.business_action,r.input_schema_json,r.requires_confirmation FROM capability_definition d JOIN capability_revision r ON r.capability_code=d.capability_code ORDER BY d.capability_code,r.revision",
                (rs, n) -> mapCapability(rs));
    }

    @Override public Optional<CapabilityCatalogEntry> findCapability(String code, String revision) {
        List<CapabilityCatalogEntry> rows = jdbc.query("SELECT r.capability_revision_id,d.capability_code,d.capability_type,d.status,r.revision,r.display_name,r.description,r.tool_name,r.implementation_key,r.business_action,r.input_schema_json,r.requires_confirmation FROM capability_definition d JOIN capability_revision r ON r.capability_code=d.capability_code WHERE d.capability_code=? AND r.revision=?",
                (rs, n) -> mapCapability(rs), code, revision);
        return rows.stream().findFirst();
    }

    @Override public Optional<CapabilityCatalogEntry> findCapabilityByRevisionId(long capabilityRevisionId) {
        List<CapabilityCatalogEntry> rows = jdbc.query("SELECT r.capability_revision_id,d.capability_code,d.capability_type,d.status,r.revision,r.display_name,r.description,r.tool_name,r.implementation_key,r.business_action,r.input_schema_json,r.requires_confirmation FROM capability_definition d JOIN capability_revision r ON r.capability_code=d.capability_code WHERE r.capability_revision_id=?",
                (rs, n) -> mapCapability(rs), capabilityRevisionId);
        return rows.stream().findFirst();
    }

    @Override public AgentDefinitionDraft saveDraft(long employeeId, int expectedDraftRevision, String instructions,
            String modelProvider, String modelName, List<CapabilitySelection> capabilities, AgentDefinitionManagementAudit audit) {
        return tx.execute(status -> {
            List<Integer> current = jdbc.query("SELECT draft_revision FROM agent_definition_draft WHERE employee_id=? FOR UPDATE",
                    (rs, n) -> rs.getInt(1), employeeId);
            if (current.isEmpty()) throw new IllegalArgumentException("Agent draft not found");
            if (current.get(0) != expectedDraftRevision) throw new IllegalStateException("Draft revision is stale");
            AgentDefinitionDraft previous = findDraft(employeeId).orElseThrow(() -> new IllegalArgumentException("Agent draft not found"));
            int next = expectedDraftRevision + 1;
            Instant now = Instant.now();
            jdbc.update("UPDATE agent_definition_draft SET draft_revision=?,instructions=?,model_provider=?,model_name=?,updated_by=?,updated_at=? WHERE employee_id=?",
                    next, instructions, modelProvider, modelName, audit.actorUserId(), Timestamp.from(now), employeeId);
            jdbc.update("DELETE FROM agent_definition_draft_capability WHERE employee_id=?", employeeId);
            int position = 0;
            for (CapabilitySelection selection : capabilities) {
                jdbc.update("INSERT INTO agent_definition_draft_capability(employee_id,capability_code,capability_revision,position_no) VALUES(?,?,?,?)",
                        employeeId, selection.capabilityCode(), selection.revision(), ++position);
            }
            AgentDefinitionDraft saved = new AgentDefinitionDraft(employeeId, next, instructions, modelProvider, modelName, capabilities, now);
            appendManagementAudit(audit.completed(draftSummary(previous), draftSummary(saved), now));
            return saved;
        });
    }

    @Override public List<DigitalEmployee> listEnabled(TenantId tenantId) {
        return jdbc.query("SELECT id,tenant_id,employee_code,display_name,enabled FROM digital_employee WHERE tenant_id=? AND enabled=TRUE AND current_published_version_id IS NOT NULL ORDER BY display_name",
                (rs, n) -> new DigitalEmployee(rs.getLong("id"), new TenantId(rs.getLong("tenant_id")), rs.getString("employee_code"), rs.getString("display_name"), rs.getBoolean("enabled")), tenantId.value());
    }

    @Override public PublishedEmployee publish(long employeeId, int expectedDraftRevision, AgentDefinitionManagementAudit audit) {
        String requestId = audit.requestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) throw new IllegalArgumentException("Invalid publish request id");
        PublishedEmployee published = tx.execute(status -> {
            List<Long> employees = jdbc.query("SELECT id FROM digital_employee WHERE id=? FOR UPDATE", (rs, n) -> rs.getLong(1), employeeId);
            if (employees.isEmpty()) throw new IllegalArgumentException("Digital employee not found");
            // 中文注释：先锁定员工，再判断幂等重放；并发相同 requestId 不会越过该锁后重复插入版本。
            List<Long> replay = jdbc.query("SELECT id FROM agent_definition_version WHERE employee_id=? AND publish_request_id=?",
                    (rs, n) -> rs.getLong(1), employeeId, requestId);
            if (!replay.isEmpty()) return findByVersionId(employeeId, replay.get(0));
            String previousSummary = findPublished(employeeId).map(this::publishedSummary).orElse("{}");
            // 中文注释：与草稿保存共用同一行锁，确保发布读到的指令和能力绑定来自同一草稿修订。
            List<Integer> lockedDraftRevision = jdbc.query("SELECT draft_revision FROM agent_definition_draft WHERE employee_id=? FOR UPDATE",
                    (rs, n) -> rs.getInt(1), employeeId);
            if (lockedDraftRevision.isEmpty()) throw new IllegalArgumentException("Agent draft not found");
            List<AgentDefinitionDraft> drafts = findDraft(employeeId).stream().toList();
            if (drafts.isEmpty()) throw new IllegalArgumentException("Agent draft not found");
            AgentDefinitionDraft draft = drafts.get(0);
            if (draft.draftRevision() != expectedDraftRevision) throw new IllegalStateException("Draft revision is stale");
            Integer last = jdbc.query("SELECT COALESCE(MAX(version_no),0) FROM agent_definition_version WHERE employee_id=?", rs -> rs.next() ? rs.getInt(1) : 0, employeeId);
            int versionNo = (last == null ? 0 : last) + 1;
            String contentHash = definitionHash(draft);
            Instant now = Instant.now();
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO agent_definition_version(employee_id,version_no,instructions,model_provider,model_name,content_hash,publish_request_id,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?)", new String[]{"id"});
                statement.setLong(1, employeeId); statement.setInt(2, versionNo); statement.setString(3, draft.instructions());
                statement.setString(4, draft.modelProvider()); statement.setString(5, draft.modelName()); statement.setString(6, contentHash);
                statement.setString(7, requestId); statement.setLong(8, audit.actorUserId()); statement.setTimestamp(9, Timestamp.from(now));
                return statement;
            }, key);
            Number generated = key.getKey();
            if (generated == null) throw new IllegalStateException("Database did not return a definition version id");
            long id = generated.longValue();
            int position = 0;
            for (CapabilitySelection capability : draft.capabilities()) {
                jdbc.update("INSERT INTO agent_definition_version_capability(definition_version_id,capability_code,capability_revision,position_no) VALUES(?,?,?,?)",
                        id, capability.capabilityCode(), capability.revision(), ++position);
            }
            jdbc.update("UPDATE digital_employee SET current_published_version_id=?,row_version=row_version+1 WHERE id=?", id, employeeId);
            PublishedEmployee result = findByVersionId(employeeId, id);
            appendManagementAudit(audit.completed(previousSummary, publishedSummary(result), now));
            return result;
        });
        if (published == null) throw new IllegalStateException("Unable to publish Agent definition");
        return published;
    }

    @Override public void setCapabilityEnabled(String code, boolean enabled, AgentDefinitionManagementAudit audit) {
        tx.executeWithoutResult(status -> {
            List<String> existing = jdbc.query("SELECT status FROM capability_definition WHERE capability_code=? FOR UPDATE",
                    (rs, n) -> rs.getString(1), code);
            if (existing.isEmpty()) throw new IllegalArgumentException("Capability does not exist");
            Instant now = Instant.now();
            jdbc.update("UPDATE capability_definition SET status=?,updated_by=?,updated_at=?,status_reason=? WHERE capability_code=?",
                    enabled ? "ACTIVE" : "DISABLED", audit.actorUserId(), Timestamp.from(now), audit.reason(), code);
            appendManagementAudit(audit.completed(capabilityStatusSummary(existing.get(0)), capabilityStatusSummary(enabled), now));
        });
    }

    @Override public boolean isCapabilityEnabled(String code, String revision) {
        Integer count = jdbc.query("SELECT COUNT(*) FROM capability_definition d JOIN capability_revision r ON r.capability_code=d.capability_code WHERE d.capability_code=? AND r.revision=? AND d.status='ACTIVE'",
                rs -> rs.next() ? rs.getInt(1) : 0, code, revision);
        return count != null && count > 0;
    }

    @Override public boolean hasUserCapabilityGrant(long userId, String code) {
        Integer count = jdbc.query("SELECT COUNT(*) FROM agent_user_capability_grant WHERE user_id=? AND capability_code=? AND enabled=TRUE",
                rs -> rs.next() ? rs.getInt(1) : 0, userId, code);
        return count != null && count > 0;
    }

    @Override public void setUserCapabilityGrant(long userId, String code, boolean enabled, AgentDefinitionManagementAudit audit) {
        tx.executeWithoutResult(status -> {
            List<Boolean> existing = jdbc.query("SELECT enabled FROM agent_user_capability_grant WHERE user_id=? AND capability_code=? FOR UPDATE",
                    (rs, n) -> rs.getBoolean(1), userId, code);
            Instant now = Instant.now();
            jdbc.update("INSERT INTO agent_user_capability_grant(user_id,capability_code,enabled,updated_by,updated_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),updated_by=VALUES(updated_by),updated_at=VALUES(updated_at)",
                    userId, code, enabled, audit.actorUserId(), Timestamp.from(now));
            appendManagementAudit(audit.completed(grantSummary(existing.isEmpty() ? null : existing.get(0), code),
                    grantSummary(enabled, code), now));
        });
    }

    @Override public void recordToolInvocation(ToolInvocationAudit audit) {
        jdbc.update("INSERT INTO tool_invocation_audit(invocation_id,run_id,capability_code,capability_revision,user_id,business_action,arguments_hash,decision,result_status,result_summary,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                audit.invocationId(), audit.runId(), audit.capabilityCode(), audit.capabilityRevision(), audit.userId(), audit.businessAction(),
                audit.argumentsHash(), audit.decision(), audit.resultStatus(), audit.resultSummary(), Timestamp.from(audit.createdAt()));
    }

    private void appendManagementAudit(AgentDefinitionManagementAudit audit) {
        jdbc.update("INSERT INTO agent_definition_management_audit(actor_user_id,event_type,target_type,target_id,request_id,reason,previous_summary,new_summary,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",
                audit.actorUserId(), audit.eventType(), audit.targetType(), audit.targetId(), audit.requestId(), audit.reason(),
                audit.previousSummary(), audit.newSummary(), Timestamp.from(audit.occurredAt()));
    }

    private String draftSummary(AgentDefinitionDraft draft) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("draftRevision", draft.draftRevision());
        summary.put("modelProvider", draft.modelProvider());
        summary.put("modelName", draft.modelName());
        summary.put("capabilities", draft.capabilities().stream()
                .map(item -> item.capabilityCode() + "@" + item.revision()).toList());
        summary.put("contentHash", definitionHash(draft));
        return writeJson(summary);
    }

    private String publishedSummary(PublishedEmployee published) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("definitionVersionId", published.definition().id());
        summary.put("versionNo", published.definition().version());
        summary.put("contentHash", published.definition().contentHash());
        return writeJson(summary);
    }

    private String capabilityStatusSummary(String status) {
        return capabilityStatusSummary("ACTIVE".equals(status));
    }

    private String capabilityStatusSummary(boolean enabled) {
        return writeJson(Map.of("enabled", enabled));
    }

    private String grantSummary(Boolean enabled, String capabilityCode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("capabilityCode", capabilityCode);
        summary.put("enabled", enabled);
        return writeJson(summary);
    }

    private PublishedEmployee findByVersionId(long employeeId, long versionId) {
        List<DefinitionRow> rows = jdbc.query("SELECT e.id employee_id,e.tenant_id,e.employee_code,e.display_name,e.enabled,v.id version_id,v.version_no,v.instructions,v.model_provider,v.model_name,v.content_hash,v.published_at FROM digital_employee e JOIN agent_definition_version v ON v.employee_id=e.id WHERE e.id=? AND v.id=?",
                (rs, n) -> definitionRow(rs), employeeId, versionId);
        if (rows.isEmpty()) throw new IllegalStateException("Published definition was not found after commit");
        DefinitionRow row = rows.get(0);
        List<CapabilityBinding> bindings = jdbc.query("SELECT b.capability_code,b.capability_revision,d.capability_type FROM agent_definition_version_capability b JOIN capability_definition d ON d.capability_code=b.capability_code WHERE b.definition_version_id=? ORDER BY b.position_no",
                (rs, n) -> new CapabilityBinding(versionId, CapabilityBinding.CapabilityType.valueOf(rs.getString(3)), rs.getString(1), rs.getString(2)), versionId);
        return new PublishedEmployee(new DigitalEmployee(row.employeeId(), new TenantId(row.tenantId()), row.employeeCode(), row.displayName(), row.employeeEnabled()),
                new AgentDefinitionVersion(versionId, employeeId, row.versionNo(), row.instructions(), row.modelProvider(), row.modelName(), row.publishedAt(), row.contentHash()), bindings);
    }

    private CapabilityCatalogEntry mapCapability(ResultSet rs) throws SQLException {
        Map<String, Object> schema;
        try { schema = json.readValue(rs.getString("input_schema_json"), JSON_MAP); }
        catch (Exception e) { throw new IllegalStateException("Invalid capability input schema JSON", e); }
        return new CapabilityCatalogEntry(rs.getLong("capability_revision_id"), rs.getString("capability_code"),
                CapabilityBinding.CapabilityType.valueOf(rs.getString("capability_type")),
                rs.getString("revision"), rs.getString("display_name"), rs.getString("description"), rs.getString("tool_name"),
                rs.getString("implementation_key"), rs.getString("business_action"), schema,
                "ACTIVE".equals(rs.getString("status")), rs.getBoolean("requires_confirmation"));
    }
    private DefinitionRow definitionRow(ResultSet rs) throws SQLException {
        return new DefinitionRow(rs.getLong("employee_id"), rs.getLong("tenant_id"), rs.getString("employee_code"), rs.getString("display_name"),
                rs.getBoolean("enabled"), rs.getLong("version_id"), rs.getInt("version_no"), rs.getString("instructions"),
                rs.getString("model_provider"), rs.getString("model_name"), rs.getString("content_hash"), rs.getTimestamp("published_at").toInstant());
    }
    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Could not serialize runtime capability snapshot", e); }
    }
    private String definitionHash(AgentDefinitionDraft draft) {
        String caps = draft.capabilities().stream().map(c -> c.capabilityCode() + "@" + c.revision()).sorted().reduce((a,b) -> a + "|" + b).orElse("");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((draft.instructions() + "|" + draft.modelProvider() + "|" + draft.modelName() + "|" + caps).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
    private record DraftRow(int revision, String instructions, String provider, String model, Instant updatedAt) {}
    private record DefinitionRow(long employeeId, long tenantId, String employeeCode, String displayName, boolean employeeEnabled,
            long versionId, int versionNo, String instructions, String modelProvider, String modelName, String contentHash, Instant publishedAt) {}
}
