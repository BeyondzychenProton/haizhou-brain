package com.haizhuo.brain.infrastructure.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable work-item acceptance/result/review facts around AgentScope's native task execution. */
@Repository
public class JdbcDelegationWorkItemRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAYLOAD_CHARS = 64_000;
    private static final int MAX_INLINE_MATERIALS = 8;
    private static final int MAX_INLINE_MATERIAL_BYTES = 32_000;
    private final JdbcTemplate jdbc;
    private final JdbcRunEventAppender events;
    private final JdbcAgentResultRepository results;
    private final JdbcDelegationReservationLifecycle reservationLifecycle;

    public JdbcDelegationWorkItemRepository(JdbcTemplate jdbc, JdbcRunEventAppender events,
                                            JdbcAgentResultRepository results) {
        this(jdbc, events, results, new JdbcDelegationReservationLifecycle(jdbc, events));
    }

    @Autowired
    public JdbcDelegationWorkItemRepository(JdbcTemplate jdbc, JdbcRunEventAppender events,
                                            JdbcAgentResultRepository results,
                                            JdbcDelegationReservationLifecycle reservationLifecycle) {
        this.jdbc = jdbc;
        this.events = events;
        this.results = results;
        this.reservationLifecycle = reservationLifecycle;
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
                if (call.sourceKind() == DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT
                        && !spawnRoles.add(call.roleId()))
                    throw new IllegalStateException("a batch cannot spawn the same fixed role twice");
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
        for (Prepared acceptedInput : prepared) {
            Prepared item = persistInlineMaterials(runId, attemptId, fenceToken, acceptedInput, now);
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
            JdbcRunProgressNotifier.append(events, runId, attemptId, fenceToken,
                    "work-item:" + item.workItemRef() + ":" + item.revision(), now);
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
        JdbcRunProgressNotifier.append(events, runId, attemptId, fenceToken,
                "work-item-review:" + workItemRef + ":" + revision + ":" + status, now);
        return true;
    }

    @Transactional
    public Optional<String> completeInvocation(RunId runId, String attemptId, long fenceToken,
            DelegationAcceptanceProvider.CompletedInvocation completed) {
        requireCurrentRun(runId, attemptId, fenceToken);
        if (!attemptId.equals(completed.descriptor().attemptId())
                || !Objects.equals(fenceToken, completed.descriptor().fenceToken()))
            throw new SecurityException("child result provenance differs from its Run fence");
        List<CompletionTarget> rows = jdbc.query("SELECT invocation.invocation_id,invocation.work_item_ref,"
                        + "invocation.assignment_revision,invocation.source_kind,invocation.employee_id,"
                        + "invocation.definition_version_id,revision.deliverable_media_type,revision.contract_version,"
                        + "revision.required_fields_json,invocation.role_id,invocation.accepted_payload,invocation.state,"
                        + "invocation.native_session_id,revision.result_id,revision.result_sha256 "
                        + "FROM platform_run_delegation_invocation invocation "
                        + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=invocation.work_item_ref "
                        + "AND revision.assignment_revision=invocation.assignment_revision "
                        + "WHERE invocation.run_id=? AND invocation.attempt_id=? AND invocation.fence_token=? "
                        + "AND invocation.invocation_id=? FOR UPDATE",
                (rs, row) -> new CompletionTarget(new InvocationTarget(rs.getString(1), rs.getString(2), rs.getInt(3),
                        DelegationAcceptanceProvider.SourceKind.valueOf(rs.getString(4)), nullableLong(rs, 5),
                        nullableLong(rs, 6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(11)),
                        rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                        rs.getString(14), rs.getString(15)),
                runId.value(), attemptId, fenceToken, completed.invocationId());
        if (rows.size() != 1) return Optional.empty();
        CompletionTarget row = rows.get(0);
        if (!row.roleId().equals(completed.roleId()) || !Objects.equals(row.acceptedPayload(), completed.acceptedPayload()))
            throw new SecurityException("child result does not match its immutable accepted input");
        String nativeSession = completed.descriptor().nativeSessionId();
        if (row.nativeSessionId() != null && !row.nativeSessionId().equals(nativeSession))
            throw new SecurityException("child invocation changed native Session");
        if (row.resultId() != null) {
            if (!CanonicalJson.sha256Hex(completed.body().getBytes(StandardCharsets.UTF_8)).equals(row.resultHash()))
                throw new IllegalStateException("immutable invocation result conflicts");
            return Optional.of(row.resultId());
        }
        if (!"ACTIVE".equals(row.state())) return Optional.empty();
        jdbc.update("UPDATE platform_run_delegation_invocation SET native_session_id=? WHERE invocation_id=?",
                nativeSession, completed.invocationId());
        var target = row.target();
        return saveCompletedResult(runId, attemptId, target, new AgentDelegationResult(row.roleId(),
                target.employeeId(), target.definitionVersionId(), completed.body(), completed.descriptor()));
    }

    @Transactional
    public Optional<String> recordDelegationResult(ExecutionClaim claim, AgentDelegationResult result) {
        if (!lockAndValidate(claim)) return Optional.empty();
        List<InvocationTarget> targets = jdbc.query("SELECT invocation.invocation_id,invocation.work_item_ref,"
                        + "invocation.assignment_revision,invocation.source_kind,invocation.employee_id,"
                        + "invocation.definition_version_id,revision.deliverable_media_type,revision.contract_version,"
                        + "revision.required_fields_json,invocation.accepted_payload FROM platform_run_delegation_invocation invocation "
                        + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=invocation.work_item_ref "
                        + "AND revision.assignment_revision=invocation.assignment_revision "
                        + "WHERE invocation.run_id=? AND invocation.attempt_id=? AND invocation.fence_token=? "
                        + "AND invocation.role_id=? AND invocation.state='ACTIVE' FOR UPDATE",
                (rs, row) -> new InvocationTarget(rs.getString(1), rs.getString(2), rs.getInt(3),
                        DelegationAcceptanceProvider.SourceKind.valueOf(rs.getString(4)), nullableLong(rs, 5),
                        nullableLong(rs, 6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)),
                claim.run().id().value(), claim.attempt().attemptId(), claim.attempt().fenceToken(), result.roleId());
        if (targets.size() != 1) return Optional.empty();
        InvocationTarget target = targets.get(0);
        if (!Objects.equals(target.employeeId(), result.employeeId())
                || !Objects.equals(target.definitionVersionId(), result.definitionVersionId()))
            throw new SecurityException("child executor identity differs from its accepted work item");
        return saveCompletedResult(claim.run().id(), claim.attempt().attemptId(), target, result);
    }

    private Optional<String> saveCompletedResult(RunId runId, String attemptId, InvocationTarget target,
                                                AgentDelegationResult result) {
        String formatStatus = validateBody(target.mediaType(), target.requiredFieldsJson(), result.body(),
                reviewedResultFromAcceptedPayload(target.acceptedPayload()));
        Instant now = Instant.now();
        String resultId = results.saveDelegation(runId, attemptId, target.invocationId(), result.roleId(),
                result.employeeId(), result.definitionVersionId(), target.mediaType(), result.body(),
                result.descriptor(), now);
        String hash = CanonicalJson.sha256Hex(result.body().getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE platform_run_work_item_revision SET result_id=?,result_sha256=?,format_status=? "
                        + "WHERE work_item_ref=? AND assignment_revision=? AND result_id IS NULL",
                resultId, hash, formatStatus, target.workItemRef(), target.revision());
        jdbc.update("UPDATE platform_run_work_item SET state='RESULT_READY',updated_at=? WHERE work_item_ref=?",
                Timestamp.from(now), target.workItemRef());
        events.append(runId, "WORK_ITEM_RESULT_READY", "子 Agent 完整结果已保存并等待根 Agent 验收",
                EventVisibility.INTERNAL, DurableEventMetadata.execution(result.descriptor()).withResult(resultId),
                "work-item-result:" + target.workItemRef() + ":" + target.revision(), now);
        JdbcRunProgressNotifier.append(events, runId, attemptId, result.descriptor().fenceToken(),
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
        if (call.sourceKind() == DelegationAcceptanceProvider.SourceKind.PUBLISHED_EXPERT
                && existsForRole(runId, call.roleId()))
            throw new IllegalStateException("this Run already has a work item for the delegated role; use agent_send for revisions");
        String ref = "wi-" + UUID.randomUUID();
        int revision = 1;
        List<ResolvedMaterial> materials = validateReferences(runId, payload);
        return prepared(call, ref, revision, payload, true, payload.required(), null, materials);
    }

    private Prepared prepareFollowUp(RunId runId, DelegationAcceptanceProvider.DelegationCall call) {
        String workItemRef = call.label() != null && call.label().matches("wi-[0-9a-fA-F-]{36}")
                ? call.label() : null;
        if (workItemRef == null)
            throw new SecurityException("follow-up requires the accepted work-item label; native keys are not identity proof");
        List<WorkItemState> rows = jdbc.query("SELECT item.work_item_ref,item.role_id,item.source_kind,item.employee_id,"
                        + "item.definition_version_id,item.definition_hash,item.required,item.latest_assignment_revision,"
                        + "revision.deliverable_media_type,revision.contract_version,revision.required_fields_json,"
                        + "revision.review_status,revision.result_id,revision.result_sha256,invocation.state "
                        + "FROM platform_run_work_item item JOIN platform_run_work_item_revision revision "
                        + "ON revision.work_item_ref=item.work_item_ref AND revision.assignment_revision=item.latest_assignment_revision "
                        + "LEFT JOIN platform_run_delegation_invocation invocation ON invocation.work_item_ref=item.work_item_ref "
                        + "AND invocation.assignment_revision=item.latest_assignment_revision "
                        + "WHERE item.run_id=? AND item.work_item_ref=? FOR UPDATE",
                (rs, row) -> new WorkItemState(rs.getString(1), rs.getString(2),
                        DelegationAcceptanceProvider.SourceKind.valueOf(rs.getString(3)), nullableLong(rs,4), nullableLong(rs,5),
                        rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getString(9), rs.getString(10),
                        rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15)),
                runId.value(), workItemRef);
        if (rows.size() != 1) throw new SecurityException("follow-up does not reference a work item in this Run");
        WorkItemState state = rows.get(0);
        if (!"ACCEPTED".equals(state.reviewStatus())
                && !"REVISION_REQUESTED".equals(state.reviewStatus())
                && !"NOT_STARTED".equals(state.invocationState()))
            throw new IllegalStateException("review the exact previous result before sending a new assignment revision");
        ParsedPayload payload = parsePayload(call.payload());
        if (!state.mediaType().equals(payload.mediaType()) || !state.contractVersion().equals(payload.contractVersion())
                || !sameRequiredFields(payload.requiredFields(), state.requiredFieldsJson()))
            throw new IllegalArgumentException("a follow-up cannot change the frozen deliverable contract");
        if (state.resultId() != null && (payload.reviewedResultRef() == null
                || !state.resultId().equals(payload.reviewedResultRef().resultId())
                || !state.resultHash().equals(payload.reviewedResultRef().contentHash())))
            throw new SecurityException("follow-up must reference the exact previous result id and content hash");
        if (state.resultId() == null && payload.reviewedResultRef() != null)
            throw new SecurityException("follow-up cannot claim an unbound previous result");
        List<ResolvedMaterial> materials = validateReferences(runId, payload);
        return prepared(call, state.workItemRef(), state.latestRevision() + 1, payload, false,
                state.required(), state, materials);
    }

    private Prepared prepared(DelegationAcceptanceProvider.DelegationCall call, String ref, int revision,
                             ParsedPayload payload, boolean create, boolean required, WorkItemState old,
                             List<ResolvedMaterial> resolvedMaterials) {
        String role = old == null ? call.roleId() : old.roleId();
        var source = old == null ? call.sourceKind() : old.sourceKind();
        Long employee = old == null ? call.employeeId() : old.employeeId();
        Long definitionVersion = old == null ? call.definitionVersionId() : old.definitionVersionId();
        String definitionHash = old == null ? call.definitionHash() : old.definitionHash();
        if (old != null && !Objects.equals(call.roleId(), null) && !old.roleId().equals(call.roleId()))
            throw new SecurityException("follow-up role does not match its work item");
        List<Reference> inputRefs = canonicalReferences(payload.inputRefs(), payload.dependencies(),
                payload.reviewedResultRef(), resolvedMaterials);
        String payloadJson = json(payload.raw());
        String hash = CanonicalJson.sha256Hex(call.payload().getBytes(StandardCharsets.UTF_8));
        return new Prepared(call, role, source, employee, definitionVersion, definitionHash, ref, revision,
                required, payload.objective(), payload.mediaType(), payload.contractVersion(),
                json(payload.requiredFields()), json(referenceJson(inputRefs)),
                json(dependencyJson(payload.dependencies())),
                payloadJson, hash, normalizedPayload(ref, revision, payload.objective(), payload.mediaType(),
                        payload.contractVersion(), payload.requiredFields(), inputRefs, payload.dependencies(),
                        payload.reviewedResultRef(), resolvedMaterials, required), create,
                payload.raw(), inputRefs, payload.dependencies(), payload.inlineMaterials(),
                resolvedMaterials, payload.reviewedResultRef());
    }

    private Prepared persistInlineMaterials(RunId runId, String attemptId, long fenceToken,
                                            Prepared item, Instant now) {
        if (item.inlineMaterials().isEmpty()) return item;
        List<Reference> refs = new ArrayList<>(item.inputRefs());
        List<ResolvedMaterial> materials = new ArrayList<>(item.resolvedMaterials());
        for (InlineMaterial material : item.inlineMaterials()) {
            String resultId = results.saveRunMaterial(runId, attemptId,
                    "run-material:" + item.workItemRef() + ":" + item.revision() + ":" + material.name(),
                    material.mediaType(), material.body(),
                    materialMetadata(attemptId, fenceToken), now);
            String hash = CanonicalJson.sha256Hex(material.body().getBytes(StandardCharsets.UTF_8));
            refs.add(new Reference("RUN_MATERIAL", resultId, hash, "ROOT_SUPPLIED"));
            materials.add(new ResolvedMaterial(material.name(), "RUN_MATERIAL", resultId, hash,
                    material.mediaType(), material.body(), "ROOT_SUPPLIED", "INTERNAL"));
        }
        JsonNode snapshot = item.rawPayload().deepCopy();
        if (!(snapshot instanceof ObjectNode payloadObject))
            throw new IllegalStateException("accepted work-item snapshot is not a JSON object");
        List<Reference> canonicalRefs = canonicalReferences(refs, item.dependencies(),
                item.reviewedResultRef(), materials);
        payloadObject.set("resolvedInputRefs", JSON.valueToTree(referenceJson(canonicalRefs)));
        return new Prepared(item.call(), item.roleId(), item.sourceKind(), item.employeeId(),
                item.definitionVersionId(), item.definitionHash(), item.workItemRef(), item.revision(), item.required(),
                item.objective(), item.mediaType(), item.contractVersion(), item.requiredFieldsJson(),
                json(referenceJson(canonicalRefs)), item.dependenciesJson(), json(snapshot), item.inputHash(),
                normalizedPayload(item.workItemRef(), item.revision(), item.objective(), item.mediaType(),
                        item.contractVersion(), readRequiredFields(item.requiredFieldsJson()), canonicalRefs, item.dependencies(),
                        item.reviewedResultRef(), materials, item.required()), item.createWorkItem(),
                item.rawPayload(), canonicalRefs, item.dependencies(), List.of(), materials, item.reviewedResultRef());
    }

    private static List<String> readRequiredFields(String requiredFieldsJson) {
        try {
            List<String> fields = new ArrayList<>();
            for (JsonNode field : JSON.readTree(requiredFieldsJson)) fields.add(field.asText());
            return List.copyOf(fields);
        } catch (Exception invalid) {
            throw new IllegalStateException("frozen output contract is unreadable", invalid);
        }
    }

    private static List<Map<String, Object>> referenceJson(List<Reference> references) {
        return references.stream().map(reference -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", reference.kind());
            value.put("id", reference.resultId());
            value.put("contentHash", reference.sha256());
            value.put("source", reference.source());
            return value;
        }).toList();
    }

    private static String normalizedPayload(String ref, int revision, String objective, String mediaType,
                                            String contractVersion, List<String> requiredFields,
                                            List<Reference> inputRefs, List<Dependency> dependencies,
                                            ReviewedResultRef reviewedResultRef,
                                            List<ResolvedMaterial> materials, boolean required) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("workItemRef", ref);
        normalized.put("assignmentRevision", revision);
        normalized.put("objective", objective);
        normalized.put("required", required);
        normalized.put("deliverable", Map.of("mediaType", mediaType,
                "contractVersion", contractVersion, "requiredFields", requiredFields));
        normalized.put("inputRefs", referenceJson(inputRefs));
        normalized.put("dependsOnResults", dependencyJson(dependencies));
        normalized.put("materials", materials);
        if (reviewedResultRef != null) normalized.put("reviewedResultRef", reviewedResultRef.toJson());
        normalized.put("instructions", "仅依据给定资料完成目标；严格符合 deliverable 契约。不要执行外部动作或修改文件。返回完整最终结果。");
        return json(normalized);
    }

    private static List<Map<String, Object>> dependencyJson(List<Dependency> dependencies) {
        return dependencies.stream().map(dependency -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("condition", dependency.condition());
            value.put("required", dependency.required());
            if ("MATERIAL_READY".equals(dependency.condition())) {
                value.put("kind", dependency.kind());
                value.put("id", dependency.resultId());
                value.put("contentHash", dependency.sha256());
            } else {
                value.put("workItemRef", dependency.workItemRef());
                value.put("assignmentRevision", dependency.assignmentRevision());
                value.put("resultId", dependency.resultId());
                value.put("contentHash", dependency.sha256());
            }
            return value;
        }).toList();
    }

    private List<ResolvedMaterial> validateReferences(RunId runId, ParsedPayload payload) {
        List<ResolvedMaterial> resolved = new ArrayList<>();
        for (Reference ref : payload.inputRefs()) {
            ResolvedMaterial material = resolveReference(runId, ref);
            addResolvedMaterial(resolved, material);
        }
        if (payload.reviewedResultRef() != null) {
            ReviewedResultRef reviewed = payload.reviewedResultRef();
            addResolvedMaterial(resolved, resolveReference(runId, new Reference("SAME_RUN_RESULT",
                    reviewed.resultId(), reviewed.contentHash(), "SAME_RUN_REVIEW_TARGET")));
        }
        for (Dependency dependency : payload.dependencies()) {
            if ("MATERIAL_READY".equals(dependency.condition())) {
                addResolvedMaterial(resolved, resolveReference(runId, new Reference(dependency.kind(),
                        dependency.resultId(), dependency.sha256(), "MATERIAL_READY")));
                continue;
            }
            Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM platform_run_work_item item "
                            + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=item.work_item_ref "
                            + "AND revision.assignment_revision=? JOIN platform_agent_result result "
                            + "ON result.result_id=revision.result_id AND result.run_id=item.run_id "
                            + "WHERE item.run_id=? AND item.work_item_ref=? AND revision.result_id=? "
                            + "AND revision.result_sha256=? AND result.kind='DELEGATION_FINAL' "
                            + "AND revision.format_status='VALID' AND revision.review_status='ACCEPTED' "
                            + "AND revision.reviewed_result_id=revision.result_id",
                    Integer.class, dependency.assignmentRevision(), runId.value(), dependency.workItemRef(),
                    dependency.resultId(), dependency.sha256());
            if (found == null || found != 1)
                throw new IllegalStateException("a dependency result is not accepted at the exact revision");
            addResolvedMaterial(resolved, resolveReference(runId, new Reference(dependency.kind(),
                    dependency.resultId(), dependency.sha256(), "DELIVERY_ACCEPTED")));
        }
        return List.copyOf(resolved);
    }

    private static void addResolvedMaterial(List<ResolvedMaterial> materials, ResolvedMaterial candidate) {
        ResolvedMaterial existing = materials.stream()
                .filter(material -> material.resultId().equals(candidate.resultId())).findFirst().orElse(null);
        if (existing == null) {
            materials.add(candidate);
            return;
        }
        if (!existing.contentHash().equals(candidate.contentHash()) || !existing.kind().equals(candidate.kind()))
            throw new SecurityException("one result id resolved to conflicting material facts");
    }

    private ResolvedMaterial resolveReference(RunId runId, Reference ref) {
        if ("USER_RESULT".equals(ref.kind())) {
            List<ResolvedMaterial> found = jdbc.query("SELECT result.result_id,result.kind,result.media_type,result.body,"
                            + "result.body_sha256,result.visibility FROM platform_agent_run_result_reference reference "
                            + "JOIN platform_agent_result result ON result.result_id=reference.result_id "
                            + "JOIN platform_agent_run accepted ON accepted.run_id=reference.run_id "
                            + "JOIN platform_agent_run source ON source.run_id=reference.source_run_id "
                            + "WHERE reference.run_id=? AND reference.result_id=? AND reference.body_sha256=? "
                            + "AND source.user_id=accepted.user_id AND source.session_id=accepted.session_id "
                            + "AND source.state='SUCCEEDED' AND result.visibility='USER' "
                            + "AND result.body_sha256=reference.body_sha256",
                    (rs, row) -> new ResolvedMaterial(null, "USER_RESULT", rs.getString(1), rs.getString(5),
                            rs.getString(3), rs.getString(4), "AUTHORIZED_SESSION_RESULT", rs.getString(6)),
                    runId.value(), ref.resultId(), ref.sha256());
            if (found.size() != 1) throw new SecurityException("input result reference is missing, stale, or unauthorized");
            return found.get(0);
        }
        String expectedKind = switch (ref.kind()) {
            case "RUN_MATERIAL" -> "RUN_MATERIAL";
            case "DELEGATION_RESULT" -> "DELEGATION_FINAL";
            case "TEAM_RESULT" -> "TEAM_RESULT";
            case "SAME_RUN_RESULT" -> null;
            default -> throw new SecurityException("unsupported input reference kind");
        };
        String kindPredicate = expectedKind == null
                ? "result.kind IN ('RUN_MATERIAL','DELEGATION_FINAL','TEAM_RESULT')"
                : "result.kind=?";
        String sql = "SELECT result.result_id,result.kind,result.media_type,result.body,result.body_sha256,"
                        + "result.visibility,result.check_metadata_json FROM platform_agent_result result "
                + "WHERE result.run_id=? AND result.result_id=? AND " + kindPredicate
                + " AND result.body_sha256=? AND result.visibility='INTERNAL'";
        Object[] args = expectedKind == null
                ? new Object[]{runId.value(), ref.resultId(), ref.sha256()}
                : new Object[]{runId.value(), ref.resultId(), expectedKind, ref.sha256()};
        List<ResolvedMaterial> found = jdbc.query(sql,
                (rs, row) -> {
                    String resultKind = rs.getString(2);
                    String referenceKind = referenceKind(resultKind);
                    return new ResolvedMaterial(null, referenceKind, rs.getString(1), rs.getString(5),
                            rs.getString(3), rs.getString(4), materialSource(referenceKind, rs.getString(7)), rs.getString(6));
                }, args);
        if (found.size() != 1) throw new SecurityException("same-Run result reference is missing or has a different hash");
        return found.get(0);
    }

    private static String referenceKind(String resultKind) {
        return switch (resultKind) {
            case "RUN_MATERIAL" -> "RUN_MATERIAL";
            case "DELEGATION_FINAL" -> "DELEGATION_RESULT";
            case "TEAM_RESULT" -> "TEAM_RESULT";
            default -> throw new SecurityException("unsupported same-Run result kind");
        };
    }

    private static List<Reference> canonicalReferences(List<Reference> references, List<Dependency> dependencies,
                                                       ReviewedResultRef reviewedResultRef,
                                                       List<ResolvedMaterial> materials) {
        List<Reference> merged = new ArrayList<>(references);
        if (reviewedResultRef != null)
            addExactReference(merged, new Reference("SAME_RUN_RESULT", reviewedResultRef.resultId(),
                    reviewedResultRef.contentHash(), "SAME_RUN_REVIEW_TARGET"));
        for (Dependency dependency : dependencies) {
            String kind = "MATERIAL_READY".equals(dependency.condition()) ? dependency.kind() : "DELEGATION_RESULT";
            addExactReference(merged, new Reference(kind, dependency.resultId(), dependency.sha256(),
                    dependency.condition()));
        }
        List<Reference> result = new ArrayList<>(merged.size());
        for (Reference ref : merged) {
            ResolvedMaterial resolved = materials.stream()
                    .filter(material -> material.resultId().equals(ref.resultId())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("input reference was not resolved before snapshot"));
            if (!resolved.contentHash().equals(ref.sha256()))
                throw new SecurityException("input reference hash does not match the resolved result");
            if (!"SAME_RUN_RESULT".equals(ref.kind()) && !resolved.kind().equals(ref.kind()))
                throw new SecurityException("input reference kind does not match the resolved result");
            result.add(new Reference(resolved.kind(), ref.resultId(), ref.sha256(), ref.source()));
        }
        return List.copyOf(result);
    }

    private boolean existsForRole(RunId runId, String roleId) {
        return !jdbc.query("SELECT work_item_ref FROM platform_run_work_item WHERE run_id=? AND role_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId.value(), roleId).isEmpty();
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
                boolean started = reservationLifecycle.activate(runId, attemptId, fenceToken, invocationId);
                if (started) activated.set(true);
                return started;
            }
            @Override public void close() {
                reservationLifecycle.close(runId, attemptId, fenceToken, invocationId,
                        activated.getAndSet(false), acceptanceOwner);
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

    private static DurableEventMetadata materialMetadata(String attemptId, long fenceToken) {
        return new DurableEventMetadata(1, null, attemptId, fenceToken, "ROOT", null,
                Map.of("source", "ROOT_SUPPLIED", "visibility", "INTERNAL"), null, null);
    }

    private static String materialSource(String kind, String checkMetadataJson) {
        if (!"RUN_MATERIAL".equals(kind)) return "SAME_RUN_" + kind;
        try {
            String source = JSON.readTree(checkMetadataJson).path("source").asText(null);
            return "ROOT_SUPPLIED".equals(source) ? source : "ROOT_SUPPLIED";
        } catch (Exception ignored) {
            return "ROOT_SUPPLIED";
        }
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
            if (refs.isArray()) for (JsonNode ref : refs) inputRefs.add(parseReference(ref, "inputRefs"));
            ReviewedResultRef reviewedResultRef = null;
            JsonNode reviewed = root.path("reviewedResultRef");
            if (!reviewed.isMissingNode() && !reviewed.isNull()) {
                reviewedResultRef = new ReviewedResultRef(
                        required(reviewed.path("resultId").asText(null), "reviewedResultRef.resultId"),
                        digest(firstText(reviewed, "contentHash", "sha256")));
                addExactReference(inputRefs, new Reference("SAME_RUN_RESULT", reviewedResultRef.resultId(),
                        reviewedResultRef.contentHash(), "SAME_RUN_REVIEW_TARGET"));
            }

            List<InlineMaterial> inlineMaterials = new ArrayList<>();
            JsonNode inline = root.path("inlineMaterials");
            if (!inline.isMissingNode() && !inline.isArray())
                throw new IllegalArgumentException("inlineMaterials must be an array");
            int inlineBytes = 0;
            Set<String> materialNames = new HashSet<>();
            if (inline.isArray()) {
                if (inline.size() > MAX_INLINE_MATERIALS)
                    throw new IllegalArgumentException("too many inline materials");
                for (JsonNode material : inline) {
                    String name = required(material.path("name").asText(null), "inlineMaterials.name");
                    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") || !materialNames.add(name))
                        throw new IllegalArgumentException("inline material names must be unique and safe");
                    String materialType = required(material.path("mediaType").asText("text/plain"),
                            "inlineMaterials.mediaType");
                    if (!Set.of("text/plain", "text/markdown", "application/json").contains(materialType))
                        throw new IllegalArgumentException("unsupported inline material media type");
                    String body = material.path("body").asText(null);
                    if (body == null || body.isBlank()) throw new IllegalArgumentException("inlineMaterials.body is required");
                    inlineBytes += body.getBytes(StandardCharsets.UTF_8).length;
                    if (inlineBytes > MAX_INLINE_MATERIAL_BYTES)
                        throw new IllegalArgumentException("inline materials exceed their aggregate size limit");
                    inlineMaterials.add(new InlineMaterial(name, materialType, body));
                }
            }

            List<Dependency> dependencies = new ArrayList<>();
            if (root.has("dependsOn") && root.has("dependsOnResults"))
                throw new IllegalArgumentException("use only one dependency field");
            JsonNode deps = root.has("dependsOnResults") ? root.path("dependsOnResults") : root.path("dependsOn");
            if (!deps.isMissingNode() && !deps.isArray()) throw new IllegalArgumentException("dependsOn must be an array");
            if (deps.isArray()) for (JsonNode dep : deps) {
                String condition = required(dep.path("condition").asText("DELIVERY_ACCEPTED"),
                        "dependency.condition");
                if ("MATERIAL_READY".equals(condition)) {
                    String kind = required(dep.path("kind").asText("RUN_MATERIAL"), "dependency.kind");
                    if (!Set.of("RUN_MATERIAL", "DELEGATION_RESULT", "TEAM_RESULT").contains(kind))
                        throw new IllegalArgumentException("MATERIAL_READY requires an exact same-Run result kind");
                    dependencies.add(new Dependency(condition, kind, null, 0,
                            required(firstText(dep, "id", "resultId"), "dependency.id"),
                            digest(firstText(dep, "contentHash", "sha256")),
                            !dep.has("required") || dep.path("required").asBoolean(true)));
                } else if ("DELIVERY_ACCEPTED".equals(condition)) {
                    int revision = dep.path("assignmentRevision").asInt(0);
                    if (revision < 1) throw new IllegalArgumentException("dependency assignmentRevision must be positive");
                    dependencies.add(new Dependency(condition, "DELEGATION_RESULT",
                            required(dep.path("workItemRef").asText(null), "dependency.workItemRef"), revision,
                            required(firstText(dep, "resultId", "id"), "dependency.resultId"),
                            digest(firstText(dep, "sha256", "contentHash")),
                            !dep.has("required") || dep.path("required").asBoolean(true)));
                } else {
                    throw new IllegalArgumentException("unsupported dependency condition");
                }
            }
            if (inputRefs.size() > 32 || dependencies.size() > 32)
                throw new IllegalArgumentException("too many result references");
            return new ParsedPayload(root, objective, required, mediaType, contractVersion, fields,
                    List.copyOf(inputRefs), List.copyOf(dependencies), List.copyOf(inlineMaterials), reviewedResultRef,
                    List.of());
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("work-item payload is not valid structured JSON", malformed);
        }
    }

    private static Reference parseReference(JsonNode ref, String field) {
        String kind = ref.path("kind").asText("USER_RESULT");
        if ("RUN_RESULT".equals(kind)) kind = "DELEGATION_RESULT";
        if (!Set.of("USER_RESULT", "RUN_MATERIAL", "DELEGATION_RESULT", "TEAM_RESULT").contains(kind))
            throw new IllegalArgumentException("unsupported " + field + " kind");
        String id = required(firstText(ref, "id", "resultId"), field + ".id");
        String hash = digest(firstText(ref, "contentHash", "sha256"));
        String referenceSource = switch (kind) {
            case "USER_RESULT" -> "AUTHORIZED_SESSION_RESULT";
            case "RUN_MATERIAL" -> "ROOT_SUPPLIED";
            case "DELEGATION_RESULT" -> "SAME_RUN_DELEGATION";
            default -> "SAME_RUN_TEAM_RESULT";
        };
        return new Reference(kind, id, hash, referenceSource);
    }

    private static void addExactReference(List<Reference> references, Reference candidate) {
        for (int i = 0; i < references.size(); i++) {
            Reference existing = references.get(i);
            if (!existing.resultId().equals(candidate.resultId())) continue;
            if (!existing.sha256().equals(candidate.sha256()))
                throw new IllegalArgumentException("one result id cannot be referenced with conflicting hashes");
            if (existing.kind().equals(candidate.kind())) return;
            if ("SAME_RUN_RESULT".equals(existing.kind()) && !"SAME_RUN_RESULT".equals(candidate.kind())) {
                references.set(i, candidate);
                return;
            }
            if ("SAME_RUN_RESULT".equals(candidate.kind()))
                return;
            if (!existing.kind().equals(candidate.kind()))
                throw new IllegalArgumentException("one result id cannot be referenced with conflicting kinds or hashes");
        }
        references.add(candidate);
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText(null);
        return value == null || value.isBlank() ? node.path(second).asText(null) : value;
    }

    private static String validateBody(String mediaType, String fieldsJson, String body,
                                       ReviewedResultRef expectedReviewedResult) {
        if (body == null || body.isBlank()) return "INVALID";
        if (!"application/json".equals(mediaType)) return "VALID";
        try {
            JsonNode parsed = JSON.readTree(body);
            if (parsed == null || !parsed.isObject()) return "INVALID";
            JsonNode fields = JSON.readTree(fieldsJson);
            for (JsonNode field : fields) if (!parsed.has(field.asText())) return "INVALID";
            if (parsed.has("reviewedResultRef")) {
                if (expectedReviewedResult == null) return "INVALID";
                JsonNode reviewed = parsed.path("reviewedResultRef");
                String resultId = reviewed.path("resultId").asText(null);
                String contentHash = firstText(reviewed, "contentHash", "sha256");
                if (!expectedReviewedResult.resultId().equals(resultId)
                        || !expectedReviewedResult.contentHash().equals(contentHash)) return "INVALID";
            }
            return "VALID";
        } catch (Exception malformed) {
            return "INVALID";
        }
    }

    private static ReviewedResultRef reviewedResultFromAcceptedPayload(String acceptedPayload) {
        if (acceptedPayload == null || acceptedPayload.isBlank()) return null;
        try {
            JsonNode reviewed = JSON.readTree(acceptedPayload).path("reviewedResultRef");
            if (reviewed.isMissingNode() || reviewed.isNull()) return null;
            return new ReviewedResultRef(required(reviewed.path("resultId").asText(null),
                    "accepted reviewedResultRef.resultId"), digest(firstText(reviewed, "contentHash", "sha256")));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception malformed) {
            throw new IllegalStateException("accepted reviewed-result reference is unreadable", malformed);
        }
    }

    private static boolean sameRequiredFields(List<String> fields, String frozenJson) {
        try { return JSON.valueToTree(fields).equals(JSON.readTree(frozenJson)); }
        catch (Exception invalid) { throw new IllegalStateException("frozen output contract is unreadable", invalid); }
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
                                 List<Reference> inputRefs, List<Dependency> dependencies,
                                 List<InlineMaterial> inlineMaterials, ReviewedResultRef reviewedResultRef,
                                 List<ResolvedMaterial> resolvedMaterials) { }
    private record Reference(String kind, String resultId, String sha256, String source) { }
    private record Dependency(String condition, String kind, String workItemRef, int assignmentRevision,
                              String resultId, String sha256, boolean required) { }
    private record InlineMaterial(String name, String mediaType, String body) { }
    private record ResolvedMaterial(String name, String kind, String resultId, String contentHash,
                                    String mediaType, String body, String source, String visibility) { }
    private record ReviewedResultRef(String resultId, String contentHash) {
        private Map<String, String> toJson() { return Map.of("resultId", resultId, "contentHash", contentHash); }
    }
    private record Prepared(DelegationAcceptanceProvider.DelegationCall call, String roleId,
                            DelegationAcceptanceProvider.SourceKind sourceKind, Long employeeId,
                            Long definitionVersionId, String definitionHash, String workItemRef,
                            int revision, boolean required, String objective, String mediaType,
                            String contractVersion, String requiredFieldsJson, String inputRefsJson,
                            String dependenciesJson, String payloadJson, String inputHash,
                            String normalizedPayload, boolean createWorkItem, JsonNode rawPayload,
                            List<Reference> inputRefs, List<Dependency> dependencies,
                            List<InlineMaterial> inlineMaterials, List<ResolvedMaterial> resolvedMaterials,
                            ReviewedResultRef reviewedResultRef) { }
    private record ReviewTarget(String resultHash, String contractVersion, String formatStatus,
                                String reviewStatus, boolean required) { }
    private record WorkItemState(String workItemRef, String roleId, DelegationAcceptanceProvider.SourceKind sourceKind,
                                 Long employeeId, Long definitionVersionId, String definitionHash, boolean required,
                                 int latestRevision, String mediaType, String contractVersion, String requiredFieldsJson,
                                 String reviewStatus, String resultId, String resultHash, String invocationState) { }
    private record InvocationTarget(String invocationId, String workItemRef, int revision,
                                   DelegationAcceptanceProvider.SourceKind sourceKind, Long employeeId,
                                   Long definitionVersionId, String mediaType, String contractVersion,
                                   String requiredFieldsJson, String acceptedPayload) { }
    private record CompletionTarget(InvocationTarget target, String roleId, String acceptedPayload,
                                    String state, String nativeSessionId, String resultId, String resultHash) { }
}
