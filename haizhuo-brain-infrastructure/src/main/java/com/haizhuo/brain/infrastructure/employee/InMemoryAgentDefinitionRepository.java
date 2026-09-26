package com.haizhuo.brain.infrastructure.employee;

import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.platform.capability.EffectiveCapabilitySet;
import com.haizhuo.brain.platform.employee.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** 测试环境内存仓储；保留与数据库实现相同的版本、授权和快照边界。 */
@Repository
@Profile("test")
public class InMemoryAgentDefinitionRepository implements AgentDefinitionRepository {
    public static final long EMPLOYEE_ID = 1L, TENANT_ID = 1L, USER_ID = 1001L;
    private static final String INSTRUCTIONS = "你是会议室预定员工。本轮只处理固定会议室 A-201，时间按 Asia/Shanghai 解释。只接受明确的未来日期、开始时间、结束时间和正整数参会人数；条件完整时必须先查询，且仅当查询明确可用才允许预定。预定结果以工具返回为准，不可虚构成功。";
    private final Map<String, CapabilityCatalogEntry> catalog = new LinkedHashMap<>();
    private final Map<Long, AgentDefinitionDraft> drafts = new HashMap<>();
    private final Map<Long, List<PublishedEmployee>> versions = new HashMap<>();
    private final Map<String, Boolean> grants = new HashMap<>();
    private final Map<String, EffectiveCapabilitySet> snapshots = new HashMap<>();
    private final Map<String, String> publishRequests = new HashMap<>();
    private final List<ToolInvocationAudit> audits = new ArrayList<>();
    private long nextVersionId = 2;

    public InMemoryAgentDefinitionRepository() {
        CapabilityCatalogEntry search = capability("meeting_room.search", "查询会议室", "查询 A-201 空闲情况", "meeting_room_search", "meeting_room.availability.read");
        CapabilityCatalogEntry reserve = capability("meeting_room.reserve", "预定会议室", "预定 A-201；必须先查询", "meeting_room_reserve", "meeting_room.booking.create");
        catalog.put(key(search.capabilityCode(), search.revision()), search);
        catalog.put(key(reserve.capabilityCode(), reserve.revision()), reserve);
        Instant now = Instant.now();
        DigitalEmployee employee = new DigitalEmployee(EMPLOYEE_ID, new TenantId(TENANT_ID), "meeting-room", "会议室预定员工", true);
        AgentDefinitionVersion version = new AgentDefinitionVersion(1, EMPLOYEE_ID, 1, INSTRUCTIONS, "openai", "qwen3.7-max", now,
                hash(INSTRUCTIONS + "|openai|qwen3.7-max|meeting_room.reserve@1|meeting_room.search@1"));
        List<CapabilityBinding> bindings = List.of(
                new CapabilityBinding(1, CapabilityBinding.CapabilityType.TOOL, search.capabilityCode(), search.revision()),
                new CapabilityBinding(1, CapabilityBinding.CapabilityType.TOOL, reserve.capabilityCode(), reserve.revision()));
        versions.put(EMPLOYEE_ID, new ArrayList<>(List.of(new PublishedEmployee(employee, version, bindings))));
        drafts.put(EMPLOYEE_ID, new AgentDefinitionDraft(EMPLOYEE_ID, 1, INSTRUCTIONS, "openai", "qwen3.7-max",
                List.of(new CapabilitySelection(search.capabilityCode(), search.revision()), new CapabilitySelection(reserve.capabilityCode(), reserve.revision())), now));
        grants.put(grant(USER_ID, search.capabilityCode()), true);
        grants.put(grant(USER_ID, reserve.capabilityCode()), true);
    }

    @Override public synchronized Optional<PublishedEmployee> findPublished(TenantId tenant, long employeeId) {
        return versions.getOrDefault(employeeId, List.of()).stream().filter(v -> v.employee().enabled() && tenant.equals(v.employee().tenantId()))
                .max(Comparator.comparingInt(v -> v.definition().version()));
    }
    @Override public synchronized Optional<AgentDefinitionDraft> findDraft(long id) { return Optional.ofNullable(drafts.get(id)); }
    @Override public synchronized List<CapabilityCatalogEntry> listCapabilities() { return catalog.values().stream().sorted(Comparator.comparing(CapabilityCatalogEntry::capabilityCode)).toList(); }
    @Override public synchronized Optional<CapabilityCatalogEntry> findCapability(String code, String revision) { return Optional.ofNullable(catalog.get(key(code, revision))); }

    @Override public synchronized AgentDefinitionDraft saveDraft(long id, int expected, String instructions, String provider,
            String model, List<CapabilitySelection> selections, long actor) {
        AgentDefinitionDraft current = requireDraft(id);
        if (current.draftRevision() != expected) throw new IllegalStateException("Draft revision is stale");
        AgentDefinitionDraft saved = new AgentDefinitionDraft(id, expected + 1, instructions, provider, model, selections, Instant.now());
        drafts.put(id, saved);
        return saved;
    }

    @Override public synchronized PublishedEmployee publish(long id, int expected, long actor, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) throw new IllegalArgumentException("Invalid publish request id");
        String priorId = publishRequests.get(id + ":" + requestId);
        if (priorId != null) return versions.get(id).stream().filter(v -> v.definition().id() == Long.parseLong(priorId)).findFirst().orElseThrow();
        AgentDefinitionDraft draft = requireDraft(id);
        if (draft.draftRevision() != expected) throw new IllegalStateException("Draft revision is stale");
        List<PublishedEmployee> history = versions.get(id);
        PublishedEmployee prior = history.get(history.size() - 1);
        long versionId = nextVersionId++;
        int versionNo = prior.definition().version() + 1;
        String bindings = draft.capabilities().stream().map(c -> c.capabilityCode() + "@" + c.revision()).sorted().reduce((a,b) -> a + "|" + b).orElse("");
        AgentDefinitionVersion definition = new AgentDefinitionVersion(versionId, id, versionNo, draft.instructions(), draft.modelProvider(),
                draft.modelName(), Instant.now(), hash(draft.instructions() + "|" + draft.modelProvider() + "|" + draft.modelName() + "|" + bindings));
        List<CapabilityBinding> selected = draft.capabilities().stream().map(c -> new CapabilityBinding(versionId, CapabilityBinding.CapabilityType.TOOL,
                c.capabilityCode(), c.revision())).toList();
        PublishedEmployee result = new PublishedEmployee(prior.employee(), definition, selected);
        history.add(result);
        publishRequests.put(id + ":" + requestId, Long.toString(versionId));
        return result;
    }

    @Override public synchronized void setCapabilityEnabled(String code, boolean enabled, long actor, String reason) {
        List<String> keys = catalog.entrySet().stream().filter(e -> e.getValue().capabilityCode().equals(code)).map(Map.Entry::getKey).toList();
        if (keys.isEmpty()) throw new IllegalArgumentException("Capability does not exist");
        for (String item : keys) { CapabilityCatalogEntry old = catalog.get(item); catalog.put(item,
                new CapabilityCatalogEntry(old.capabilityCode(), old.type(), old.revision(), old.displayName(), old.description(), old.toolName(), old.implementationKey(), old.businessAction(), old.inputSchema(), enabled)); }
    }
    @Override public synchronized boolean isCapabilityEnabled(String code, String revision) { return findCapability(code, revision).map(CapabilityCatalogEntry::enabled).orElse(false); }
    @Override public synchronized boolean hasUserCapabilityGrant(long userId, String code) { return grants.getOrDefault(grant(userId, code), false); }
    @Override public synchronized void setUserCapabilityGrant(long userId, String code, boolean enabled, long actor) {
        if (catalog.values().stream().noneMatch(c -> c.capabilityCode().equals(code))) throw new IllegalArgumentException("Capability does not exist");
        grants.put(grant(userId, code), enabled);
    }
    @Override public synchronized void saveEffectiveCapabilitySet(EffectiveCapabilitySet set) { snapshots.put(set.runId(), set); }
    @Override public synchronized Optional<EffectiveCapabilitySet> findEffectiveCapabilitySet(String runId) { return Optional.ofNullable(snapshots.get(runId)); }
    @Override public synchronized void recordToolInvocation(ToolInvocationAudit audit) { audits.add(audit); }
    public synchronized List<ToolInvocationAudit> recordedAudits() { return List.copyOf(audits); }

    private AgentDefinitionDraft requireDraft(long id) { AgentDefinitionDraft result = drafts.get(id); if (result == null) throw new IllegalArgumentException("Agent draft not found"); return result; }
    private static CapabilityCatalogEntry capability(String code, String name, String description, String tool, String action) {
        Map<String, Object> props = Map.of("startAt", Map.of("type", "string", "description", "开始时间，ISO-8601"),
                "endAt", Map.of("type", "string", "description", "结束时间，ISO-8601"), "attendees", Map.of("type", "integer", "description", "参会人数"));
        Map<String, Object> schema = Map.of("type", "object", "properties", props, "required", List.of("startAt", "endAt", "attendees"), "additionalProperties", false);
        return new CapabilityCatalogEntry(code, CapabilityBinding.CapabilityType.TOOL, "1", name, description, tool, "meeting-room-v1", action, schema, true);
    }
    private static String key(String code, String rev) { return code + "@" + rev; }
    private static String grant(long user, String code) { return user + ":" + code; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
