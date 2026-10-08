package com.haizhuo.brain.infrastructure.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.AgentDelegationResult;
import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.run.DurableEventMetadata;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.ExecutionClaim;
import com.haizhuo.brain.runtime.api.DelegationAcceptanceProvider;
import com.haizhuo.brain.runtime.api.DelegationBudgetProvider;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable work-item acceptance/result/review facts around AgentScope's native task execution. */
@Repository
public class JdbcDelegationWorkItemRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAYLOAD_CHARS = 64_000;
    private final JdbcTemplate jdbc;
    private final JdbcRunEventAppender events;
    private final JdbcAgentResultRepository results;

    public JdbcDelegationWorkItemRepository(JdbcTemplate jdbc, JdbcRunEventAppender events,
                                            JdbcAgentResultRepository results) {
        this.jdbc = jdbc;
        this.events = events;
        this.results = results;
    }

    @Transactional
    public List<DelegationAcceptanceProvider.AcceptedDelegation> acceptBatch(
            RunId runId, String attemptId, long fenceToken, int maxInvocations, int maxParallel,
            List<DelegationAcceptanceProvider.DelegationCall> calls) {
        if (calls == null || calls.isEmpty()) return List.of();
        if (maxInvocations < 1 || maxParallel < 1)
            throw new IllegalStateException("delegation is disabled by the frozen Run policy");
        requireCurrentRun(runId, attemptId, fenceToken);
        Set<String> toolIds = new HashSet<>();
        Set<String> spawnRoles = new HashSet<>();
        Map<String, DelegationAcceptanceProvider.AcceptedDelegation> accepted = new LinkedHashMap<>();
        List<Prepared> prepared = new ArrayList<>(calls.size());
        for (var call : calls) {
            if (!toolIds.add(call.toolUseId())) throw new IllegalArgumentException("duplicate toolUseId in delegation batch");
            if (call.payload().length() > MAX_PAYLOAD_CHARS) throw new IllegalArgumentException("work-item payload is too large");
            var receipt = findReceipt(runId, attemptId, fenceToken, call);
            if (receipt.isPresent()) {
                accepted.put(call.toolUseId(), receipt.get());
                continue;
            }
            if (call.operation() == DelegationAcceptanceProvider.Operation.SPAWN) {
                if (!spawnRoles.add(call.roleId())) throw new IllegalStateException("a batch cannot spawn the same role twice");
                prepared.add(prepareSpawn(runId, call));
            } else {
                prepared.add(prepareFollowUp(runId, call));
            }
        }
        if (prepared.size() > maxParallel) throw new IllegalStateException("delegation batch exceeds parallel budget");

        List<String> states = jdbc.query("SELECT state FROM platform_run_delegation_invocation WHERE run_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId.value());
        if (states.size() + prepared.size() > maxInvocations)
            throw new IllegalStateException("delegation invocation budget exhausted");
        long active = states.stream().filter(state -> "ACTIVE".equals(state) || "ACCEPTED".equals(state)).count();
        if (active + prepared.size() > maxParallel)
            throw new IllegalStateException("parallel delegation budget exhausted");

        Instant now = Instant.now();
        for (Prepared item : prepared) {
            if (item.createWorkItem()) {
                jdbc.update("INSERT INTO platform_run_work_item(work_item_ref,run_id,role_id,source_kind,employee_id,"
                                + "definition_version_id,definition_hash,required,latest_assignment_revision,state,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,'ASSIGNED',?,?)",
                        item.workItemRef(), runId.value(), item.roleId(), item.sourceKind().name(), item.employeeId(),
                        item.definitionVersionId(), item.definitionHash(), item.required(), item.revision(),
                        Timestamp.from(now), Timestamp.from(now));
            } else {
                jdbc.update("UPDATE platform_run_work_item SET latest_assignment_revision=?,state='ASSIGNED',updated_at=? "
                                + "WHERE work_item_ref=? AND run_id=?",
                        item.revision(), Timestamp.from(now), item.workItemRef(), runId.value());
            }
            jdbc.update("INSERT INTO platform_run_work_item_revision(work_item_ref,assignment_revision,payload_json,"
                            + "payload_sha256,objective,deliverable_media_type,contract_version,required_fields_json,"
                            + "input_refs_json,dependencies_json,format_status,review_status,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,'PENDING','PENDING',?)",
                    item.workItemRef(), item.revision(), item.payloadJson(), item.inputHash(), item.objective(),
                    item.mediaType(), item.contractVersion(), item.requiredFieldsJson(), item.inputRefsJson(),
                    item.dependenciesJson(), Timestamp.from(now));
            String invocationId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO platform_run_delegation_invocation(invocation_id,run_id,attempt_id,fence_token,"
                            + "role_id,state,started_at,tool_use_id,work_item_ref,assignment_revision,source_kind,employee_id,"
                            + "definition_version_id,definition_hash,input_sha256,request_sha256,accepted_payload) "
                            + "VALUES(?,?,?,?,?,'ACCEPTED',?,?,?,?,?,?,?,?,?,?,?)",
                    invocationId, runId.value(), attemptId, fenceToken, item.roleId(), Timestamp.from(now),
                    item.call().toolUseId(), item.workItemRef(), item.revision(), item.sourceKind().name(),
                    item.employeeId(), item.definitionVersionId(), item.definitionHash(), item.inputHash(),
                    requestHash(item.call()), item.normalizedPayload());
            events.append(runId, item.createWorkItem() ? "WORK_ITEM_ACCEPTED" : "WORK_ITEM_REVISED",
                    "只读协作工作项已受理", EventVisibility.INTERNAL,
                    eventMetadata(attemptId, fenceToken), "work-item:" + item.workItemRef() + ":" + item.revision(), now);
            accepted.put(item.call().toolUseId(), new DelegationAcceptanceProvider.AcceptedDelegation(
                    reservation(invocationId, runId, attemptId, fenceToken), item.roleId(), item.workItemRef(),
                    item.revision(), item.normalizedPayload(), item.workItemRef(), item.sourceKind(),
                    item.employeeId(), item.definitionVersionId(), item.definitionHash()));
        }
        return calls.stream().map(call -> accepted.get(call.toolUseId())).toList();
    }

    private Optional<DelegationAcceptanceProvider.AcceptedDelegation> findReceipt(
            RunId runId, String attemptId, long fenceToken, DelegationAcceptanceProvider.DelegationCall call) {
        return jdbc.query("SELECT invocation.invocation_id,invocation.fence_token,invocation.request_sha256,"
                        + "invocation.role_id,invocation.work_item_ref,invocation.assignment_revision,invocation.source_kind,"
                        + "invocation.employee_id,invocation.definition_version_id,invocation.definition_hash,"
                        + "invocation.accepted_payload FROM platform_run_delegation_invocation invocation "
                        + "WHERE invocation.run_id=? AND invocation.attempt_id=? AND invocation.tool_use_id=? FOR UPDATE",
                (rs, row) -> {
                    if (rs.getString(3) == null || rs.getString(11) == null)
                        throw new IllegalStateException("legacy delegation receipt requires recovery verification");
                    if (rs.getLong(2) != fenceToken || !requestHash(call).equals(rs.getString(3)))
                        throw new IllegalStateException("delegation toolUseId idempotency conflict");
                    String ref = rs.getString(5);
                    int revision = rs.getInt(6);
                    return new DelegationAcceptanceProvider.AcceptedDelegation(
                            reservation(rs.getString(1), runId, attemptId, fenceToken, false), rs.getString(4), ref, revision,
                            rs.getString(11), ref,
                            DelegationAcceptanceProvider.SourceKind.valueOf(rs.getString(7)), nullableLong(rs, 8),
                            nullableLong(rs, 9), rs.getString(10));
                }, runId.value(), attemptId, call.toolUseId()).stream().findFirst();
    }

    private static String requestHash(DelegationAcceptanceProvider.DelegationCall call) {
        return CanonicalJson.sha256Hex(json(call).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Optional<DelegationAcceptanceProvider.WorkItemResult> readResult(
            RunId runId, String attemptId, long fenceToken, String workItemRef, int revision) {
        requireCurrentRun(runId, attemptId, fenceToken);
        return jdbc.query("SELECT revision.result_id,result.body,result.body_sha256,revision.contract_version,"
                        + "revision.deliverable_media_type,revision.format_status,revision.review_status "
                        + "FROM platform_run_work_item item JOIN platform_run_work_item_revision revision "
                        + "ON revision.work_item_ref=item.work_item_ref LEFT JOIN platform_agent_result result "
                        + "ON result.result_id=revision.result_id AND result.run_id=item.run_id "
                        + "WHERE item.run_id=? AND item.work_item_ref=? AND revision.assignment_revision=? "
                        + "AND revision.result_id IS NOT NULL",
                (rs, row) -> new DelegationAcceptanceProvider.WorkItemResult(workItemRef, revision,
                        rs.getString("result_id"), rs.getString("body"), rs.getString("body_sha256"),
                        rs.getString("contract_version"), rs.getString("deliverable_media_type"),
                        rs.getString("format_status"), rs.getString("review_status")),
                runId.value(), workItemRef, revision).stream().findFirst();
    }

    @Transactional
    public boolean reviewResult(RunId runId, String attemptId, long fenceToken, String workItemRef,
                                int revision, String resultId, String bodySha256, String contractVersion,
                                DelegationAcceptanceProvider.ReviewDecision decision, String reason) {
        Objects.requireNonNull(decision);
        requireCurrentRun(runId, attemptId, fenceToken);
        if (reason != null && reason.length() > 2000) throw new IllegalArgumentException("review reason is too long");
        List<ReviewTarget> target = jdbc.query("SELECT revision.result_sha256,revision.contract_version,"
                        + "revision.format_status,revision.review_status,item.required "
                        + "FROM platform_run_work_item item JOIN platform_run_work_item_revision revision "
                        + "ON revision.work_item_ref=item.work_item_ref WHERE item.run_id=? AND item.work_item_ref=? "
                        + "AND revision.assignment_revision=? AND revision.result_id=? FOR UPDATE",
                (rs, row) -> new ReviewTarget(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBoolean(5)), runId.value(), workItemRef, revision, resultId);
        if (target.size() != 1) return false;
        ReviewTarget current = target.get(0);
        if (!Objects.equals(current.resultHash(), bodySha256)
                || !Objects.equals(current.contractVersion(), contractVersion)) return false;
        if (decision == DelegationAcceptanceProvider.ReviewDecision.ACCEPT
                && !"VALID".equals(current.formatStatus())) return false;
        String status = switch (decision) {
            case ACCEPT -> "ACCEPTED";
            case REQUEST_REVISION -> "REVISION_REQUESTED";
            case REJECT -> "REJECTED";
        };
        if (status.equals(current.reviewStatus())) return true;
        Instant now = Instant.now();
        jdbc.update("UPDATE platform_run_work_item_revision SET review_status=?,reviewed_result_id=?,review_reason=?,reviewed_at=? "
                        + "WHERE work_item_ref=? AND assignment_revision=? AND result_id=? AND result_sha256=? AND contract_version=?",
                status, resultId, reason, Timestamp.from(now), workItemRef, revision, resultId, bodySha256, contractVersion);
        jdbc.update("UPDATE platform_run_work_item SET state=?,updated_at=? WHERE work_item_ref=? AND run_id=?",
                status, Timestamp.from(now), workItemRef, runId.value());
        events.append(runId, "WORK_ITEM_" + status, "根 Agent 已记录工作项验收决定", EventVisibility.INTERNAL,
                eventMetadata(attemptId, fenceToken), "work-item-review:" + workItemRef + ":" + revision + ":" + status, now);
        return true;
    }

    @Transactional
    public Optional<String> recordDelegationResult(ExecutionClaim claim, AgentDelegationResult result) {
        if (!lockAndValidate(claim)) return Optional.empty();
        List<InvocationTarget> targets = jdbc.query("SELECT invocation.invocation_id,invocation.work_item_ref,"
                        + "invocation.assignment_revision,invocation.source_kind,invocation.employee_id,"
                        + "invocation.definition_version_id,revision.deliverable_media_type,revision.contract_version,"
                        + "revision.required_fields_json FROM platform_run_delegation_invocation invocation "
                        + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=invocation.work_item_ref "
                        + "AND revision.assignment_revision=invocation.assignment_revision "
                        + "WHERE invocation.run_id=? AND invocation.attempt_id=? AND invocation.fence_token=? "
                        + "AND invocation.role_id=? AND invocation.state='ACTIVE' FOR UPDATE",
                (rs, row) -> new InvocationTarget(rs.getString(1), rs.getString(2), rs.getInt(3),
                        DelegationAcceptanceProvider.SourceKind.valueOf(rs.getString(4)), nullableLong(rs, 5),
                        nullableLong(rs, 6), rs.getString(7), rs.getString(8), rs.getString(9)),
                claim.run().id().value(), claim.attempt().attemptId(), claim.attempt().fenceToken(), result.roleId());
        if (targets.size() != 1) return Optional.empty();
        InvocationTarget target = targets.get(0);
        if (!Objects.equals(target.employeeId(), result.employeeId())
                || !Objects.equals(target.definitionVersionId(), result.definitionVersionId()))
            throw new SecurityException("child executor identity differs from its accepted work item");
        String formatStatus = validateBody(target.mediaType(), target.requiredFieldsJson(), result.body());
        Instant now = Instant.now();
        String resultId = results.saveDelegation(claim, target.invocationId(), result.roleId(),
                result.employeeId(), result.definitionVersionId(), target.mediaType(), result.body(),
                result.descriptor(), now);
        String hash = CanonicalJson.sha256Hex(result.body().getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE platform_run_work_item_revision SET result_id=?,result_sha256=?,format_status=? "
                        + "WHERE work_item_ref=? AND assignment_revision=? AND result_id IS NULL",
                resultId, hash, formatStatus, target.workItemRef(), target.revision());
        jdbc.update("UPDATE platform_run_work_item SET state='RESULT_READY',updated_at=? WHERE work_item_ref=?",
                Timestamp.from(now), target.workItemRef());
        events.append(claim.run().id(), "WORK_ITEM_RESULT_READY", "子 Agent 完整结果已保存并等待根 Agent 验收",
                EventVisibility.INTERNAL, DurableEventMetadata.execution(result.descriptor()).withResult(resultId),
                "work-item-result:" + target.workItemRef() + ":" + target.revision(), now);
        jdbc.update("UPDATE platform_run_delegation_invocation SET state='FINISHED',finished_at=? "
                        + "WHERE invocation_id=? AND state='ACTIVE'", Timestamp.from(now), target.invocationId());
        return Optional.of(resultId);
    }

    @Transactional
    public boolean requiredWorkItemsAccepted(RunId runId) {
        List<String> pending = jdbc.query("SELECT item.work_item_ref FROM platform_run_work_item item "
                        + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=item.work_item_ref "
                        + "AND revision.assignment_revision=item.latest_assignment_revision "
                        + "WHERE item.run_id=? AND item.required=1 AND (revision.result_id IS NULL "
                        + "OR revision.format_status<>'VALID' OR revision.review_status<>'ACCEPTED' "
                        + "OR revision.reviewed_result_id<>revision.result_id) FOR UPDATE",
                (rs, row) -> rs.getString(1), runId.value());
        return pending.isEmpty();
    }

    private Prepared prepareSpawn(RunId runId, DelegationAcceptanceProvider.DelegationCall call) {
        ParsedPayload payload = parsePayload(call.payload());
        if (call.sourceKind() == DelegationAcceptanceProvider.SourceKind.RUNTIME_GENERATED
                && (call.definitionHash() == null || !call.definitionHash().matches("[0-9a-f]{64}")))
            throw new SecurityException("runtime-generated expert definition hash is required");
        if (call.sourceKind() != DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT
                && (call.employeeId() != null || call.definitionVersionId() != null))
            throw new SecurityException("non-published expert cannot claim a published employee identity");
        if (call.sourceKind() == DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT
                && (call.employeeId() == null || call.definitionVersionId() == null))
            throw new SecurityException("published expert identity is incomplete");
        if (existsForRole(runId, call.roleId()))
            throw new IllegalStateException("this Run already has a work item for the delegated role; use agent_send for revisions");
        String ref = "wi-" + UUID.randomUUID();
        int revision = 1;
        validateReferences(runId, payload);
        return prepared(call, ref, revision, payload, true, payload.required());
    }

    private Prepared prepareFollowUp(RunId runId, DelegationAcceptanceProvider.DelegationCall call) {
        String workItemRef = call.label() != null && call.label().matches("wi-[0-9a-fA-F-]{36}")
                ? call.label() : null;
        String roleId = workItemRef == null ? roleFromKey(call.agentKey()) : null;
        List<WorkItemState> rows = jdbc.query("SELECT item.work_item_ref,item.role_id,item.source_kind,item.employee_id,"
                        + "item.definition_version_id,item.definition_hash,item.required,item.latest_assignment_revision,"
                        + "revision.deliverable_media_type,revision.contract_version,revision.required_fields_json,"
                        + "revision.review_status,revision.result_id,invocation.state "
                        + "FROM platform_run_work_item item JOIN platform_run_work_item_revision revision "
                        + "ON revision.work_item_ref=item.work_item_ref AND revision.assignment_revision=item.latest_assignment_revision "
                        + "LEFT JOIN platform_run_delegation_invocation invocation ON invocation.work_item_ref=item.work_item_ref "
                        + "AND invocation.assignment_revision=item.latest_assignment_revision "
                        + "WHERE item.run_id=? AND ((? IS NOT NULL AND item.work_item_ref=?) "
                        + "OR (? IS NULL AND item.role_id=?)) ORDER BY invocation.started_at DESC LIMIT 1 FOR UPDATE",
                (rs, row) -> new WorkItemState(rs.getString(1), rs.getString(2),
                        DelegationAcceptanceProvider.SourceKind.valueOf(rs.getString(3)), nullableLong(rs,4), nullableLong(rs,5),
                        rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14)),
                runId.value(), workItemRef, workItemRef, workItemRef, roleId);
        if (rows.size() != 1) throw new SecurityException("follow-up does not reference a work item in this Run");
        WorkItemState state = rows.get(0);
        if (!"ACCEPTED".equals(state.reviewStatus())
                && !"REVISION_REQUESTED".equals(state.reviewStatus())
                && !"NOT_STARTED".equals(state.invocationState()))
            throw new IllegalStateException("review the exact previous result before sending a new assignment revision");
        ParsedPayload payload = parsePayload(call.payload());
        if (!state.mediaType().equals(payload.mediaType()) || !state.contractVersion().equals(payload.contractVersion()))
            throw new IllegalArgumentException("a follow-up cannot change the frozen deliverable contract");
        validateReferences(runId, payload);
        return prepared(call, state.workItemRef(), state.latestRevision() + 1, payload, false, state.required(), state);
    }

    private Prepared prepared(DelegationAcceptanceProvider.DelegationCall call, String ref, int revision,
                             ParsedPayload payload, boolean create, boolean required) {
        return prepared(call, ref, revision, payload, create, required, null);
    }

    private Prepared prepared(DelegationAcceptanceProvider.DelegationCall call, String ref, int revision,
                             ParsedPayload payload, boolean create, boolean required, WorkItemState old) {
        String role = old == null ? call.roleId() : old.roleId();
        var source = old == null ? call.sourceKind() : old.sourceKind();
        Long employee = old == null ? call.employeeId() : old.employeeId();
        Long definitionVersion = old == null ? call.definitionVersionId() : old.definitionVersionId();
        String definitionHash = old == null ? call.definitionHash() : old.definitionHash();
        if (old != null && !Objects.equals(call.roleId(), null) && !old.roleId().equals(call.roleId()))
            throw new SecurityException("follow-up role does not match its work item");
        String payloadJson = json(payload.raw());
        String hash = CanonicalJson.sha256Hex(call.payload().getBytes(StandardCharsets.UTF_8));
        return new Prepared(call, role, source, employee, definitionVersion, definitionHash, ref, revision,
                required, payload.objective(), payload.mediaType(), payload.contractVersion(),
                json(payload.requiredFields()), json(payload.inputRefs()), json(payload.dependencies()),
                payloadJson, hash, normalizedPayload(ref, revision, payload, required), create);
    }

    private static String normalizedPayload(String ref, int revision, ParsedPayload payload, boolean required) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("workItemRef", ref);
        normalized.put("assignmentRevision", revision);
        normalized.put("objective", payload.objective());
        normalized.put("required", required);
        normalized.put("deliverable", Map.of("mediaType", payload.mediaType(),
                "contractVersion", payload.contractVersion(), "requiredFields", payload.requiredFields()));
        normalized.put("inputRefs", payload.inputRefs());
        normalized.put("dependsOn", payload.dependencies());
        normalized.put("instructions", "仅依据给定资料完成目标；严格符合 deliverable 契约。不要执行外部动作或修改文件。返回完整最终结果。");
        return json(normalized);
    }

    private void validateReferences(RunId runId, ParsedPayload payload) {
        for (Reference ref : payload.inputRefs()) {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_result_reference reference "
                            + "JOIN platform_agent_result result ON result.result_id=reference.result_id "
                            + "JOIN platform_agent_run accepted ON accepted.run_id=reference.run_id "
                            + "JOIN platform_agent_run source ON source.run_id=reference.source_run_id "
                            + "WHERE reference.run_id=? AND reference.result_id=? AND reference.body_sha256=? "
                            + "AND source.user_id=accepted.user_id AND source.session_id=accepted.session_id "
                            + "AND source.state='SUCCEEDED' AND result.visibility='USER' AND result.body_sha256=reference.body_sha256",
                    Integer.class, runId.value(), ref.resultId(), ref.sha256());
            if (found == null || found != 1) throw new SecurityException("input result reference is missing, stale, or unauthorized");
        }
        for (Dependency dependency : payload.dependencies()) {
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_work_item item "
                            + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=item.work_item_ref "
                            + "AND revision.assignment_revision=? WHERE item.run_id=? AND item.work_item_ref=? "
                            + "AND revision.result_id=? AND revision.result_sha256=? AND revision.format_status='VALID' "
                            + "AND revision.review_status='ACCEPTED' AND revision.reviewed_result_id=revision.result_id",
                    Integer.class, dependency.assignmentRevision(), runId.value(), dependency.workItemRef(),
                    dependency.resultId(), dependency.sha256());
            if (found == null || found != 1) throw new IllegalStateException("a dependency result is not accepted at the exact revision");
        }
    }

    private boolean existsForRole(RunId runId, String roleId) {
        return !jdbc.query("SELECT work_item_ref FROM platform_run_work_item WHERE run_id=? AND role_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId.value(), roleId).isEmpty();
    }

    private static String roleFromKey(String agentKey) {
        if (agentKey != null) {
            var matcher = java.util.regex.Pattern.compile("^agent:([a-z0-9][a-z0-9-]{0,63}):[0-9a-fA-F-]{32,36}$")
                    .matcher(agentKey.strip());
            if (matcher.matches()) return matcher.group(1);
        }
        if (agentKey == null) return null;
        throw new SecurityException("agent_key is not a recognized native AgentScope key");
    }

    private DelegationBudgetProvider.Reservation reservation(String invocationId, RunId runId,
                                                              String attemptId, long fenceToken) {
        return reservation(invocationId, runId, attemptId, fenceToken, true);
    }

    private DelegationBudgetProvider.Reservation reservation(String invocationId, RunId runId,
                                                              String attemptId, long fenceToken, boolean acceptanceOwner) {
        var activated = new java.util.concurrent.atomic.AtomicBoolean();
        return new DelegationBudgetProvider.Reservation() {
            @Override public String invocationId() { return invocationId; }
            @Override public boolean activate() {
                Instant now = Instant.now();
                boolean started = jdbc.update("UPDATE platform_run_delegation_invocation SET state='ACTIVE',started_at=? "
                                + "WHERE invocation_id=? AND run_id=? AND attempt_id=? AND fence_token=? AND state='ACCEPTED' "
                                + "AND EXISTS (SELECT 1 FROM platform_run_execution_attempt attempt "
                                + "JOIN platform_agent_run run ON run.run_id=attempt.run_id "
                                + "WHERE attempt.run_id=? AND run.state='RUNNING' AND run.runtime_profile='TEAM_READONLY' "
                                + "AND attempt.attempt_id=? AND attempt.fence_token=? AND attempt.state='RUNNING' "
                                + "AND attempt.lease_expires_at>?)",
                        Timestamp.from(now), invocationId, runId.value(), attemptId, fenceToken,
                        runId.value(), attemptId, fenceToken, Timestamp.from(now)) == 1;
                if (started) activated.set(true);
                return started;
            }
            @Override public void close() {
                Instant now = Instant.now();
                if (activated.getAndSet(false)) {
                    jdbc.update("UPDATE platform_run_delegation_invocation SET state='FINISHED',finished_at=? "
                                    + "WHERE invocation_id=? AND state='ACTIVE'",
                            Timestamp.from(now), invocationId);
                } else if (acceptanceOwner) {
                    jdbc.update("UPDATE platform_run_delegation_invocation SET state='NOT_STARTED',finished_at=? "
                                    + "WHERE invocation_id=? AND state='ACCEPTED'",
                            Timestamp.from(now), invocationId);
                }
            }
        };
    }

    private void requireCurrentRun(RunId runId, String attemptId, long fenceToken) {
        events.lockRun(runId);
        List<String> running = jdbc.query("SELECT run.run_id FROM platform_agent_run run "
                        + "JOIN platform_run_execution_attempt attempt ON attempt.run_id=run.run_id "
                        + "WHERE run.run_id=? AND run.state='RUNNING' AND run.runtime_profile='TEAM_READONLY' "
                        + "AND attempt.attempt_id=? AND attempt.fence_token=? AND attempt.state='RUNNING' "
                        + "AND attempt.lease_expires_at>? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId.value(), attemptId, fenceToken, Timestamp.from(Instant.now()));
        if (running.size() != 1) throw new IllegalStateException("delegation Run fence is stale or profile is closed");
    }

    private boolean lockAndValidate(ExecutionClaim claim) {
        Instant now = Instant.now();
        if (events.lockRun(claim.run().id()) == null) return false;
        return !jdbc.query("SELECT attempt.attempt_id FROM platform_run_execution_attempt attempt "
                        + "JOIN platform_agent_run run ON run.run_id=attempt.run_id "
                        + "WHERE run.run_id=? AND run.state='RUNNING' AND run.runtime_profile='TEAM_READONLY' "
                        + "AND attempt.attempt_id=? AND attempt.lease_token=? AND attempt.fence_token=? "
                        + "AND attempt.state='RUNNING' AND attempt.lease_expires_at>? FOR UPDATE",
                (rs, row) -> rs.getString(1), claim.run().id().value(), claim.attempt().attemptId(),
                claim.attempt().leaseToken(), claim.attempt().fenceToken(), Timestamp.from(now)).isEmpty();
    }

    private static DurableEventMetadata eventMetadata(String attemptId, long fenceToken) {
        return new DurableEventMetadata(1, null, attemptId, fenceToken, "ROOT", null, Map.of(), null, null);
    }

    private static ParsedPayload parsePayload(String source) {
        try {
            JsonNode root = JSON.readTree(source);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("work-item payload must be a JSON object");
            String objective = required(root.path("objective").asText(null), "objective");
            if (objective.length() > 12_000) throw new IllegalArgumentException("objective is too long");
            boolean required = !root.has("required") || root.path("required").asBoolean(true);
            JsonNode deliverable = root.path("deliverable");
            String mediaType = required(deliverable.path("mediaType").asText(null), "deliverable.mediaType");
            if (!Set.of("text/plain", "text/markdown", "application/json").contains(mediaType))
                throw new IllegalArgumentException("unsupported deliverable media type");
            String contractVersion = required(deliverable.path("contractVersion").asText(null), "deliverable.contractVersion");
            if (contractVersion.length() > 64) throw new IllegalArgumentException("contract version is too long");
            List<String> fields = new ArrayList<>();
            JsonNode requiredFields = deliverable.path("requiredFields");
            if (!requiredFields.isMissingNode() && !requiredFields.isArray())
                throw new IllegalArgumentException("deliverable.requiredFields must be an array");
            if (requiredFields.isArray()) {
                for (JsonNode field : requiredFields) {
                    String name = required(field.asText(null), "required field");
                    if (name.length() > 128 || fields.contains(name)) throw new IllegalArgumentException("invalid required field");
                    fields.add(name);
                }
            }
            if (fields.size() > 64) throw new IllegalArgumentException("too many required output fields");
            List<Reference> inputRefs = new ArrayList<>();
            JsonNode refs = root.path("inputRefs");
            if (!refs.isMissingNode() && !refs.isArray()) throw new IllegalArgumentException("inputRefs must be an array");
            if (refs.isArray()) for (JsonNode ref : refs) inputRefs.add(new Reference(
                    required(ref.path("resultId").asText(null), "inputRefs.resultId"),
                    digest(ref.path("sha256").asText(null))));
            List<Dependency> dependencies = new ArrayList<>();
            JsonNode deps = root.path("dependsOn");
            if (!deps.isMissingNode() && !deps.isArray()) throw new IllegalArgumentException("dependsOn must be an array");
            if (deps.isArray()) for (JsonNode dep : deps) {
                int revision = dep.path("assignmentRevision").asInt(0);
                if (revision < 1) throw new IllegalArgumentException("dependency assignmentRevision must be positive");
                dependencies.add(new Dependency(required(dep.path("workItemRef").asText(null), "dependency.workItemRef"),
                        revision, required(dep.path("resultId").asText(null), "dependency.resultId"),
                        digest(dep.path("sha256").asText(null))));
            }
            if (inputRefs.size() > 32 || dependencies.size() > 32) throw new IllegalArgumentException("too many result references");
            return new ParsedPayload(root, objective, required, mediaType, contractVersion, fields, inputRefs, dependencies);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("work-item payload is not valid structured JSON", malformed);
        }
    }

    private static String validateBody(String mediaType, String fieldsJson, String body) {
        if (body == null || body.isBlank()) return "INVALID";
        if (!"application/json".equals(mediaType)) return "VALID";
        try {
            JsonNode parsed = JSON.readTree(body);
            if (parsed == null || !parsed.isObject()) return "INVALID";
            JsonNode fields = JSON.readTree(fieldsJson);
            for (JsonNode field : fields) if (!parsed.has(field.asText())) return "INVALID";
            return "VALID";
        } catch (Exception malformed) {
            return "INVALID";
        }
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("could not encode work-item data", error); }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.strip();
    }

    private static String digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 must be lowercase hex");
        return value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record ParsedPayload(JsonNode raw, String objective, boolean required, String mediaType,
                                 String contractVersion, List<String> requiredFields,
                                 List<Reference> inputRefs, List<Dependency> dependencies) { }
    private record Reference(String resultId, String sha256) { }
    private record Dependency(String workItemRef, int assignmentRevision, String resultId, String sha256) { }
    private record Prepared(DelegationAcceptanceProvider.DelegationCall call, String roleId,
                            DelegationAcceptanceProvider.SourceKind sourceKind, Long employeeId,
                            Long definitionVersionId, String definitionHash, String workItemRef,
                            int revision, boolean required, String objective, String mediaType,
                            String contractVersion, String requiredFieldsJson, String inputRefsJson,
                            String dependenciesJson, String payloadJson, String inputHash,
                            String normalizedPayload, boolean createWorkItem) { }
    private record ReviewTarget(String resultHash, String contractVersion, String formatStatus,
                                String reviewStatus, boolean required) { }
    private record WorkItemState(String workItemRef, String roleId, DelegationAcceptanceProvider.SourceKind sourceKind,
                                 Long employeeId, Long definitionVersionId, String definitionHash, boolean required,
                                 int latestRevision, String mediaType, String contractVersion, String requiredFieldsJson,
                                 String reviewStatus, String resultId, String invocationState) { }
    private record InvocationTarget(String invocationId, String workItemRef, int revision,
                                   DelegationAcceptanceProvider.SourceKind sourceKind, Long employeeId,
                                   Long definitionVersionId, String mediaType, String contractVersion,
                                   String requiredFieldsJson) { }
}
