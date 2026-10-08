package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.AgentRun;
import com.haizhuo.brain.platform.run.HarnessRunSpec;
import com.haizhuo.brain.platform.run.RunState;
import com.haizhuo.brain.platform.run.EventVisibility;
import com.haizhuo.brain.platform.run.RunEvent;
import com.haizhuo.brain.platform.run.SessionEvent;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.run.RunExecutionMode;
import com.haizhuo.brain.platform.run.RunExecutionTarget;
import com.haizhuo.brain.platform.run.RunResultReference;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import com.haizhuo.brain.platform.run.RunGuidance;
import com.haizhuo.brain.platform.run.SessionEventPage;
import com.haizhuo.brain.platform.run.SessionSnapshot;
import com.haizhuo.brain.infrastructure.run.JdbcRunEventAppender;
import com.haizhuo.brain.infrastructure.run.JdbcEventMetadata;
import com.haizhuo.brain.platform.session.AgentSession;
import com.haizhuo.brain.platform.session.SessionRoleSlot;
import com.haizhuo.brain.runtime.api.model.RuntimeProfile;
import com.haizhuo.brain.runtime.api.RunControlInbox;
import com.haizhuo.brain.runtime.api.RunGuidanceMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 阻塞式 JDBC 适配器。调用方必须保证它不在 WebFlux 事件循环上执行。 */
@Repository
public class JdbcSessionRunStore implements SessionRunStore, RunControlInbox {
    private final JdbcTemplate jdbc;
    private final JdbcRunEventAppender events;

    public JdbcSessionRunStore(JdbcTemplate jdbc, JdbcSessionEventProjector sessionEvents) {
        this(jdbc,new JdbcRunEventAppender(jdbc,sessionEvents));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcSessionRunStore(JdbcTemplate jdbc, JdbcRunEventAppender events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    public AgentSession createSession(AgentSession session) {
        jdbc.update("INSERT INTO platform_agent_session(session_id,user_id,employee_id,status,created_at,last_active_at,row_version,definition_version_id,legacy_runtime) VALUES(?,?,?,?,?,?,?,?,?)",
                session.id().value(), session.userId().value(), session.employeeId(), session.status().name(),
                Timestamp.from(session.createdAt()), Timestamp.from(session.lastActiveAt()), session.rowVersion(), session.definitionVersionId(), session.legacyRuntime());
        return session;
    }

    @Override
    public Optional<AgentSession> findSession(SessionId sessionId, UserId owner) {
        return jdbc.query("SELECT session_id,user_id,employee_id,status,created_at,last_active_at,row_version,definition_version_id,legacy_runtime FROM platform_agent_session WHERE session_id=? AND user_id=?",
                (rs, row) -> new AgentSession(new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"),
                        AgentSession.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_active_at")), rs.getLong("row_version"), rs.getObject("definition_version_id", Long.class), rs.getBoolean("legacy_runtime")),
                sessionId.value(), owner.value()).stream().findFirst();
    }

    @Override @Transactional
    public SessionRoleSlot createRoleSlot(SessionRoleSlot proposed) {
        var sessions = jdbc.query("SELECT status FROM platform_agent_session WHERE session_id=? AND user_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), proposed.sessionId().value(), proposed.userId().value());
        if (sessions.isEmpty()) throw new IllegalArgumentException("Owned Session was not found");
        if (!"ACTIVE".equals(sessions.get(0))) throw new IllegalStateException("Session is closed");
        Optional<SessionRoleSlot> existing = findRoleSlot(proposed.sessionId(), proposed.userId(), proposed.roleId());
        if (existing.isPresent()) {
            SessionRoleSlot slot = existing.get();
            if (slot.employeeId() != proposed.employeeId()
                    || slot.definitionVersionId() != proposed.definitionVersionId())
                throw new IllegalStateException("Session role slot conflicts with the frozen role definition");
            return slot;
        }
        jdbc.update("INSERT INTO platform_session_role_slot(slot_id,session_id,user_id,role_id,employee_id,"
                        + "definition_version_id,harness_session_key,workspace_runtime_key,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                proposed.id(), proposed.sessionId().value(), proposed.userId().value(), proposed.roleId(),
                proposed.employeeId(), proposed.definitionVersionId(), proposed.harnessSessionKey(),
                proposed.workspaceRuntimeKey(), Timestamp.from(proposed.createdAt()));
        return proposed;
    }

    @Override
    public Optional<SessionRoleSlot> findRoleSlot(SessionId sessionId, UserId owner, String roleId) {
        return jdbc.query("SELECT slot.* FROM platform_session_role_slot slot JOIN platform_agent_session session "
                        + "ON session.session_id=slot.session_id WHERE slot.session_id=? AND session.user_id=? AND slot.role_id=?",
                (rs, row) -> mapRoleSlot(rs), sessionId.value(), owner.value(), roleId).stream().findFirst();
    }

    @Override
    public Optional<RunExecutionTarget> findExecutionTarget(RunId runId, UserId owner) {
        return jdbc.query("SELECT target.execution_mode,target.role_id,target.employee_id,target.definition_version_id,"
                        + "target.role_slot_id FROM platform_agent_run_execution_target target JOIN platform_agent_run run "
                        + "ON run.run_id=target.run_id WHERE target.run_id=? AND run.user_id=?",
                (rs, row) -> new RunExecutionTarget(RunExecutionMode.valueOf(rs.getString("execution_mode")),
                        rs.getString("role_id"), rs.getLong("employee_id"), rs.getLong("definition_version_id"),
                        rs.getString("role_slot_id")), runId.value(), owner.value()).stream().findFirst();
    }

    @Override public List<AgentSession> findSessions(UserId owner, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT session_id,user_id,employee_id,status,created_at,last_active_at,row_version,definition_version_id,legacy_runtime FROM platform_agent_session WHERE user_id=? ORDER BY last_active_at DESC LIMIT ?",
                (rs, row) -> new AgentSession(new SessionId(rs.getString("session_id")), new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), AgentSession.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_active_at")), rs.getLong("row_version"), rs.getObject("definition_version_id", Long.class), rs.getBoolean("legacy_runtime")), owner.value(), safeLimit);
    }

    @Override @Transactional
    public AgentSession pinDefinitionVersion(SessionId sessionId, UserId owner, long versionId) {
        if (versionId <= 0) throw new IllegalArgumentException("versionId must be positive");
        jdbc.update("UPDATE platform_agent_session SET definition_version_id=?,row_version=row_version+1 "
                        + "WHERE session_id=? AND user_id=? AND status='ACTIVE' AND legacy_runtime=TRUE "
                        + "AND definition_version_id IS NULL", versionId, sessionId.value(), owner.value());
        AgentSession session = findSession(sessionId, owner)
                .orElseThrow(() -> new IllegalArgumentException("Session was not found"));
        if (session.status() != AgentSession.Status.ACTIVE || session.definitionVersionId() == null)
            throw new IllegalStateException("Active session version could not be fixed");
        return session;
    }

    @Override
    public boolean hasRuntimeHistory(SessionId sessionId, UserId owner, RunId currentRun) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run WHERE session_id=? "
                        + "AND user_id=? AND run_id<>? AND started_at IS NOT NULL", Integer.class,
                sessionId.value(), owner.value(), currentRun.value());
        return count != null && count > 0;
    }

    @Override
    public boolean hasRuntimeHistory(SessionId sessionId, UserId owner, RunId currentRun, String roleId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run run "
                        + "LEFT JOIN platform_agent_run_execution_target target ON target.run_id=run.run_id "
                        + "WHERE run.session_id=? AND run.user_id=? AND run.run_id<>? AND run.started_at IS NOT NULL "
                        + "AND COALESCE(target.role_id,'coordinator')=?", Integer.class,
                sessionId.value(), owner.value(), currentRun.value(), roleId);
        return count != null && count > 0;
    }

    @Override @Transactional
    public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec) {
        return createRun(run, input, runSpec, RunExecutionTarget.coordinator(run.employeeId(), run.definitionVersionId()));
    }

    @Override @Transactional
    public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec, RunExecutionTarget target) {
        return createRun(run, input, runSpec, target, List.of());
    }

    @Override @Transactional
    public AgentRun createRun(AgentRun run, String input, HarnessRunSpec runSpec, RunExecutionTarget target,
                              List<RunResultReference> references) {
        List<RunResultReference> frozenReferences = List.copyOf(references == null ? List.of() : references);
        Optional<AgentRun> replay = findByRequest(run.userId(), run.clientRequestId());
        if (replay.isPresent()) {
            AgentRun existing = replay.get();
            if (!existing.sessionId().equals(run.sessionId()) || !existing.inputDigest().equals(run.inputDigest())) throw new IllegalStateException("Client request id conflicts with an existing run");
            RunExecutionTarget existingTarget = findExecutionTarget(existing.id(), existing.userId())
                    .orElseGet(() -> RunExecutionTarget.coordinator(existing.employeeId(), existing.definitionVersionId()));
            if (!existingTarget.equals(target)) throw new IllegalStateException("Client request id conflicts with a different Run executor");
            return existing;
        }
        var sessions = jdbc.query("SELECT employee_id,definition_version_id FROM platform_agent_session WHERE session_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE", (rs, row) -> new Long[] { rs.getLong(1), rs.getObject(2, Long.class) }, run.sessionId().value(), run.userId().value());
        if (sessions.isEmpty()) throw new IllegalArgumentException("Active session was not found");
        if (sessions.get(0)[0] != run.employeeId()) throw new IllegalStateException("Run employee does not match session employee");
        Long fixedVersion = sessions.get(0)[1];
        if (fixedVersion != null && fixedVersion != run.definitionVersionId())
            throw new IllegalStateException("Run version does not match fixed session version");
        java.util.Set<String> uniqueReferenceIds = new java.util.HashSet<>();
        int referenceOrder = 0;
        for (RunResultReference reference : frozenReferences) {
            if (!uniqueReferenceIds.add(reference.resultId()))
                throw new IllegalArgumentException("Duplicate Run result reference");
            Integer visible = jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_result result "
                            + "JOIN platform_agent_run source ON source.run_id=result.run_id "
                            + "WHERE result.result_id=? AND result.run_id=? AND source.session_id=? "
                            + "AND source.user_id=? AND source.state='SUCCEEDED' AND result.visibility='USER' "
                            + "AND result.body IS NOT NULL AND result.body_sha256=?",
                    Integer.class, reference.resultId(), reference.sourceRunId().value(), run.sessionId().value(),
                    run.userId().value(), reference.bodySha256());
            if (visible == null || visible != 1)
                throw new IllegalArgumentException("Run result reference is missing, changed, or not visible in this Session");
            referenceOrder++;
        }
        if (!runSpec.runId().equals(run.id()) || runSpec.definitionVersionId() != target.definitionVersionId())
            throw new IllegalStateException("Run specification does not match frozen executor");
        if (RunExecutionTarget.COORDINATOR_ROLE.equals(target.roleId())) {
            if (target.roleSlotId() != null || target.employeeId() != run.employeeId()
                    || target.definitionVersionId() != run.definitionVersionId())
                throw new IllegalStateException("Coordinator target does not match the Session owner");
        } else {
            SessionRoleSlot slot = jdbc.query("SELECT slot.* FROM platform_session_role_slot slot JOIN platform_agent_session session "
                            + "ON session.session_id=slot.session_id WHERE slot.slot_id=? AND slot.session_id=? "
                            + "AND session.user_id=? AND slot.role_id=? FOR UPDATE",
                    (rs, row) -> mapRoleSlot(rs), target.roleSlotId(), run.sessionId().value(), run.userId().value(), target.roleId())
                    .stream().findFirst().orElseThrow(() -> new IllegalStateException("Run role slot is missing or not owned by this Session"));
            if (slot.employeeId() != target.employeeId() || slot.definitionVersionId() != target.definitionVersionId())
                throw new IllegalStateException("Run executor does not match its frozen role slot");
        }
        try {
            jdbc.update("INSERT INTO platform_agent_run(run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,runtime_profile,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    run.id().value(), run.sessionId().value(), run.userId().value(), run.employeeId(), run.definitionVersionId(),
                    run.clientRequestId(), run.inputDigest(), run.state().name(), run.runtimeProfile().name(), Timestamp.from(run.createdAt()));
        } catch (DuplicateKeyException error) { throw new IllegalStateException("Client request id conflicts with an existing run", error); }
        jdbc.update("INSERT INTO platform_agent_run_execution_target(run_id,execution_mode,role_id,employee_id,"
                        + "definition_version_id,role_slot_id,created_at) VALUES(?,?,?,?,?,?,?)",
                run.id().value(), target.mode().name(), target.roleId(), target.employeeId(),
                target.definitionVersionId(), target.roleSlotId(), Timestamp.from(run.createdAt()));
        jdbc.update("INSERT INTO platform_agent_run_spec(run_id,definition_version_id,definition_bundle_id,definition_bundle_hash,model_visible_tool_names_json,tool_view_hash,effective_capability_hash,channel_type,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                runSpec.runId().value(), runSpec.definitionVersionId(), runSpec.definitionBundleId(), runSpec.definitionBundleHash(),
                runSpec.modelVisibleToolNamesJson(), runSpec.toolViewHash(), runSpec.effectiveCapabilityHash(), runSpec.channelType(), Timestamp.from(runSpec.createdAt()));
        referenceOrder = 0;
        for (RunResultReference reference : frozenReferences) {
            jdbc.update("INSERT INTO platform_agent_run_result_reference(run_id,reference_order,result_id,source_run_id,body_sha256,created_at) "
                            + "VALUES(?,?,?,?,?,?)", run.id().value(), referenceOrder++, reference.resultId(),
                    reference.sourceRunId().value(), reference.bodySha256(), Timestamp.from(run.createdAt()));
        }
        events.append(run.id(),"USER_INPUT",input,run.createdAt());
        jdbc.update("UPDATE platform_agent_session SET last_active_at=?,row_version=row_version+1 WHERE session_id=? AND user_id=?", Timestamp.from(run.createdAt()), run.sessionId().value(), run.userId().value());
        return run;
    }

    @Override
    public Optional<AgentRun> findRun(RunId runId, UserId owner) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,runtime_profile,created_at,started_at,finished_at FROM platform_agent_run WHERE run_id=? AND user_id=?",
                (rs, row) -> mapRun(rs), runId.value(), owner.value()).stream().findFirst();
    }

    @Override public java.util.List<RunEvent> findEvents(RunId runId, UserId owner, int afterSequence, int limit) {
        return jdbc.query("SELECT event.*,COALESCE(result.body,event.content) visible_content FROM platform_agent_run_event event "
                        + "JOIN platform_agent_run run ON run.run_id=event.run_id LEFT JOIN platform_agent_result result ON result.result_id=event.result_id AND result.run_id=event.run_id AND result.visibility='USER' "
                        + "WHERE event.run_id=? AND run.user_id=? AND event.visibility='USER' AND event.sequence_no>? ORDER BY event.sequence_no LIMIT ?",
                (rs,row)->new RunEvent(runId,rs.getInt("sequence_no"),rs.getString("event_type"),rs.getString("visible_content"),instant(rs.getTimestamp("created_at")),
                        EventVisibility.USER,JdbcEventMetadata.read(rs)),runId.value(),owner.value(),Math.max(0,afterSequence),Math.max(1,Math.min(200,limit)));
    }

    @Override public java.util.List<SessionEvent> findSessionEvents(SessionId sessionId, UserId owner, long afterCursor, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT event.*,COALESCE(result.body,event.content) visible_content FROM platform_agent_session_event event "
                        + "JOIN platform_agent_session session ON session.session_id=event.session_id "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=event.result_id AND result.run_id=event.run_id AND result.visibility=event.visibility "
                        + "WHERE event.session_id=? AND session.user_id=? AND event.session_cursor>? "
                        + "ORDER BY event.session_cursor LIMIT ?",
                (rs, row) -> new SessionEvent(new SessionId(rs.getString("session_id")), rs.getLong("session_cursor"),
                        new RunId(rs.getString("run_id")), nullableInt(rs, "run_sequence"), rs.getString("event_type"),
                        EventVisibility.valueOf(rs.getString("visibility")), rs.getString("visible_content"),
                        instant(rs.getTimestamp("created_at")),JdbcEventMetadata.read(rs)), sessionId.value(), owner.value(), afterCursor, safeLimit);
    }

    @Override public long oldestSessionCursor(SessionId sessionId, UserId owner) {
        Long floor = jdbc.query("SELECT COALESCE((SELECT MIN(session_cursor) FROM platform_agent_session_event WHERE session_id=session.session_id),"
                        + "CASE WHEN session.next_event_cursor>1 THEN session.next_event_cursor ELSE 0 END) "
                        + "FROM platform_agent_session session WHERE session.session_id=? AND session.user_id=?",
                (rs, row) -> rs.getLong(1),
                sessionId.value(), owner.value()).stream().findFirst().orElse(0L);
        return floor == null ? 0L : floor;
    }

    @Override @Transactional public int trimSessionEvents(SessionId sessionId, UserId owner, int keepLatest) {
        if (keepLatest <= 0) {
            return 0;
        }
        // 先确认属主，避免越权裁剪他人会话的历史。
        Long max = jdbc.query("SELECT next_event_cursor-1 FROM platform_agent_session WHERE session_id=? AND user_id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), sessionId.value(), owner.value()).stream().findFirst().orElse(0L);
        // 分两步取边界再删除：不依赖 MySQL 与 H2 一致支持的自引用删除子查询。
        // 并发写入只会抬高边界下界，已算好的 boundary 仍落在更旧的一侧，因此只可能少删、不会误删。
        long boundary = (max == null ? 0L : max) - keepLatest;
        if (boundary <= 0) {
            return 0;
        }
        return jdbc.update("DELETE FROM platform_agent_session_event WHERE session_id=? AND session_cursor<=?",
                sessionId.value(), boundary);
    }

    @Override public List<AgentRun> findRuns(SessionId sessionId, UserId owner, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,runtime_profile,created_at,started_at,finished_at FROM platform_agent_run WHERE session_id=? AND user_id=? ORDER BY created_at DESC,run_id DESC LIMIT ?",
                (rs, row) -> mapRun(rs), sessionId.value(), owner.value(), safeLimit);
    }

    @Override public List<SessionTimelineItem> findTimeline(SessionId sessionId, UserId owner, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("SELECT event.run_id,event.sequence_no,event.event_type,COALESCE(result.body,event.content) content,event.created_at,"
                        + "COALESCE(target.role_id,'coordinator') executor_role_id FROM platform_agent_run_event event "
                        + "JOIN platform_agent_run run ON run.run_id=event.run_id LEFT JOIN platform_agent_result result ON result.result_id=event.result_id AND result.run_id=event.run_id AND result.visibility='USER' "
                        + "LEFT JOIN platform_agent_run_execution_target target ON target.run_id=run.run_id "
                        + "WHERE run.session_id=? AND run.user_id=? AND event.visibility='USER' ORDER BY event.session_cursor DESC LIMIT ?",
                (rs, row) -> new SessionTimelineItem(new RunId(rs.getString("run_id")), rs.getInt("sequence_no"),
                        rs.getString("event_type"), rs.getString("content"), instant(rs.getTimestamp("created_at")),
                        rs.getString("executor_role_id")), sessionId.value(), owner.value(), safeLimit).stream()
                .sorted(java.util.Comparator.comparing(SessionTimelineItem::createdAt)
                        .thenComparing(item -> item.runId().value()).thenComparingInt(SessionTimelineItem::sequenceNo)).toList();
    }

    @Override public int queuePosition(RunId runId, UserId owner) {
        return jdbc.query("SELECT 1 + (SELECT COUNT(*) FROM platform_agent_run earlier WHERE earlier.session_id=run.session_id AND earlier.state='QUEUED' AND (earlier.created_at<run.created_at OR (earlier.created_at=run.created_at AND earlier.run_id<run.run_id))) FROM platform_agent_run run WHERE run.run_id=? AND run.user_id=? AND run.state='QUEUED'",
                (rs, row) -> rs.getInt(1), runId.value(), owner.value()).stream().findFirst().orElse(0);
    }

    @Override @Transactional public AgentRun cancel(RunId runId, UserId owner) {
        lockOwnedRun(runId,owner);
        AgentRun run = findRun(runId, owner).orElseThrow(() -> new IllegalArgumentException("Run was not found"));
        Instant now = Instant.now();
        if (run.state() == RunState.QUEUED) {
            // §47.1：排队的 run 尚未开始，直接取消。
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='QUEUED'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "排队消息已取消", now);
        } else if (run.state() == RunState.RUNNING) {
            // §47.2：持久化的取消标记；运行时会在下一个安全检查点确认。
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLING',cancel_requested_at=? WHERE run_id=? AND state='RUNNING'", Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCEL_REQUESTED", "已请求在下一个安全检查点取消", now);
        } else if (run.state() == RunState.WAITING_TOOL) {
            // §47.3：尚未开始的工具工作被取消；执行中的工具是一次无法假称回滚的副作用，
            // 因此如实地拒绝该取消请求。
            Integer executing = jdbc.queryForObject("SELECT COUNT(*) FROM platform_tool_execution WHERE run_id=? AND state='EXECUTING'", Integer.class, runId.value());
            if (executing != null && executing > 0)
                throw new IllegalStateException("工具正在执行中，暂时无法取消，请稍后重试");
            jdbc.update("UPDATE platform_tool_execution SET state='CANCELLED',updated_at=? WHERE run_id=? AND state IN ('REQUESTED','APPROVED')", Timestamp.from(now), runId.value());
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='WAITING_TOOL'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "等待工具结果的运行已取消", now);
        } else if (run.state() == RunState.WAITING_CONFIRMATION) {
            // §47.4：关闭未决审批、取消执行、取消该 run。
            // 可移植的子查询写法（MySQL 的 UPDATE...JOIN 在 H2 测试中不受支持）。
            jdbc.update("UPDATE platform_tool_approval SET decision='REJECTED',reason='运行已取消',decided_at=? "
                    + "WHERE decision='PENDING' AND tool_execution_id IN "
                    + "(SELECT tool_execution_id FROM platform_tool_execution WHERE run_id=?)", Timestamp.from(now), runId.value());
            jdbc.update("UPDATE platform_tool_execution SET state='CANCELLED',updated_at=? WHERE run_id=? AND state IN ('APPROVAL_REQUIRED','REQUESTED','APPROVED')", Timestamp.from(now), runId.value());
            if (jdbc.update("UPDATE platform_agent_run SET state='CANCELLED',finished_at=?,cancel_requested_at=? WHERE run_id=? AND state='WAITING_CONFIRMATION'", Timestamp.from(now), Timestamp.from(now), runId.value()) == 1)
                appendEvent(runId, "RUN_CANCELLED", "等待人工确认的运行已取消", now);
        } else if (run.state() != RunState.CANCELLING) {
            throw new IllegalStateException("Terminal Run cannot be cancelled");
        }
        return findRun(runId, owner).orElseThrow();
    }

    @Override @Transactional public RunGuidance addGuidance(RunId runId, UserId owner, String source, String content) {
        lockOwnedRun(runId,owner);
        AgentRun run = findRun(runId, owner).orElseThrow(() -> new IllegalArgumentException("Run was not found"));
        if (run.state() != RunState.RUNNING) throw new IllegalStateException("Guidance is accepted only while a Run is running");
        Instant now = Instant.now(); String id = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO platform_agent_run_guidance(guidance_id,run_id,author_user_id,source,content,status,created_at) VALUES(?,?,?,?,?,'PENDING',?)", id, runId.value(), owner.value(), source, content, Timestamp.from(now));
        appendEvent(runId, "RUN_GUIDANCE_RECEIVED", "已收到运行中引导", now);
        return new RunGuidance(id, runId, owner, source, content, RunGuidance.Status.PENDING, now, null);
    }

    @Override @Transactional public List<RunGuidanceMessage> consumeGuidance(RunId runId) {
        if (events.lockRun(runId) == null) return List.of();
        Instant now = Instant.now();
        List<RunGuidanceMessage> messages = jdbc.query("SELECT guidance_id,source,content,created_at FROM platform_agent_run_guidance WHERE run_id=? AND status='PENDING' ORDER BY created_at,guidance_id FOR UPDATE", (rs,row) -> new RunGuidanceMessage(rs.getString("guidance_id"), runId, rs.getString("source"), rs.getString("content"), instant(rs.getTimestamp("created_at"))), runId.value());
        for (RunGuidanceMessage message : messages) {
            jdbc.update("UPDATE platform_agent_run_guidance SET status='CONSUMED',consumed_at=? WHERE guidance_id=? AND status='PENDING'", Timestamp.from(now), message.guidanceId());
            appendEvent(runId, "RUN_GUIDANCE_CONSUMED", "运行中引导已在下一轮推理前生效", now);
        }
        return List.copyOf(messages);
    }

    @Override public boolean isCancellationRequested(RunId runId) {
        return jdbc.query("SELECT 1 FROM platform_agent_run WHERE run_id=? AND state='CANCELLING'", (rs,row) -> 1, runId.value()).stream().findFirst().isPresent();
    }

    /** run 事件与会话投影必须在同一事务内写入，否则会话游标会出现空洞。 */
    private void appendEvent(RunId id, String type, String text, Instant now) {
        events.append(id,type,text,now);
    }

    private void lockOwnedRun(RunId runId, UserId owner) {
        if (findRun(runId,owner).isEmpty() || events.lockRun(runId) == null) throw new IllegalArgumentException("Run was not found");
    }

    @Override @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SessionEventPage scanSessionEventPage(SessionId sid, UserId owner, long after, int limit) {
        long requested = Math.max(0,after); long floor = oldestSessionCursor(sid,owner);
        boolean expired = floor > requested + 1;
        if (expired) return new SessionEventPage(List.of(),floor,true,requested);
        List<SessionEvent> raw = findSessionEvents(sid,owner,requested,limit);
        long next = raw.stream().mapToLong(SessionEvent::sessionCursor).max().orElse(requested);
        return new SessionEventPage(raw.stream().filter(e->e.visibility()==EventVisibility.USER).toList(),floor,false,next);
    }

    @Override @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public SessionSnapshot snapshot(SessionId sid, UserId owner, int limit) {
        Long cursor = jdbc.query("SELECT next_event_cursor-1 FROM platform_agent_session WHERE session_id=? AND user_id=?",
                (rs,n)->rs.getLong(1),sid.value(),owner.value()).stream().findFirst().orElseThrow(()->new IllegalArgumentException("Session was not found"));
        List<SessionEvent> content = jdbc.query("SELECT event.*,run.session_id,event.sequence_no run_sequence,COALESCE(result.body,event.content) visible_content "
                        + "FROM platform_agent_run_event event JOIN platform_agent_run run ON run.run_id=event.run_id "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=event.result_id AND result.run_id=event.run_id AND result.visibility='USER' "
                        + "WHERE run.session_id=? AND run.user_id=? AND event.visibility='USER' AND event.session_cursor<=? ORDER BY event.session_cursor DESC LIMIT ?",
                (rs,n)->new SessionEvent(sid,rs.getLong("session_cursor"),new RunId(rs.getString("run_id")),rs.getInt("run_sequence"),
                        rs.getString("event_type"),EventVisibility.USER,rs.getString("visible_content"),instant(rs.getTimestamp("created_at")),JdbcEventMetadata.read(rs)),
                sid.value(),owner.value(),cursor,Math.max(1,Math.min(200,limit))).stream().sorted(java.util.Comparator.comparingLong(SessionEvent::sessionCursor)).toList();
        return new SessionSnapshot(findSession(sid,owner).orElseThrow(),findRuns(sid,owner,100),content,cursor,oldestSessionCursor(sid,owner));
    }

    @Transactional public int rebuildProjection(SessionId sid, UserId owner) {
        if (jdbc.query("SELECT session_id FROM platform_agent_session WHERE session_id=? AND user_id=? FOR UPDATE",
                (rs,n)->rs.getString(1),sid.value(),owner.value()).isEmpty()) throw new IllegalArgumentException("Session was not found");
        return jdbc.update("INSERT INTO platform_agent_session_event(session_id,session_cursor,run_id,run_sequence,event_type,visibility,content,created_at,"
                        + "schema_version,event_id,attempt_id,fence_token,origin_kind,native_refs_json,payload_json,result_id) "
                        + "SELECT r.session_id,e.session_cursor,e.run_id,e.sequence_no,e.event_type,e.visibility,e.content,e.created_at,"
                        + "e.schema_version,e.event_id,e.attempt_id,e.fence_token,e.origin_kind,e.native_refs_json,e.payload_json,e.result_id "
                        + "FROM platform_agent_run_event e JOIN platform_agent_run r ON r.run_id=e.run_id WHERE r.session_id=? AND e.session_cursor IS NOT NULL "
                        + "AND NOT EXISTS(SELECT 1 FROM platform_agent_session_event p WHERE p.session_id=r.session_id AND p.session_cursor=e.session_cursor)",sid.value());
    }

    private Optional<AgentRun> findByRequest(UserId owner, String clientRequestId) {
        return jdbc.query("SELECT run_id,session_id,user_id,employee_id,definition_version_id,client_request_id,input_digest,state,runtime_profile,created_at,started_at,finished_at FROM platform_agent_run WHERE user_id=? AND client_request_id=?",
                (rs, row) -> mapRun(rs), owner.value(), clientRequestId).stream().findFirst();
    }

    private static AgentRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentRun(new RunId(rs.getString("run_id")), new SessionId(rs.getString("session_id")),
                new UserId(rs.getLong("user_id")), rs.getLong("employee_id"), rs.getLong("definition_version_id"),
                rs.getString("client_request_id"), rs.getString("input_digest"), RunState.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("created_at")), nullableInstant(rs.getTimestamp("started_at")),
                nullableInstant(rs.getTimestamp("finished_at")), RuntimeProfile.valueOf(rs.getString("runtime_profile")));
    }

    private static SessionRoleSlot mapRoleSlot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRoleSlot(rs.getString("slot_id"), new SessionId(rs.getString("session_id")),
                new UserId(rs.getLong("user_id")), rs.getString("role_id"), rs.getLong("employee_id"),
                rs.getLong("definition_version_id"), rs.getString("harness_session_key"),
                rs.getString("workspace_runtime_key"), instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp timestamp) { return timestamp.toInstant(); }
    private static Instant nullableInstant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
