package com.haizhuo.brain.infrastructure.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RunProgressQueryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 读取属主范围内的协作事实；另一个精简查询只返回已核验且对 USER 可见的结果正文。 */
@Repository
public class JdbcRunProgressQueryStore implements RunProgressQueryStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ITEM_SELECT = "SELECT item.work_item_ref,item.role_id,item.source_kind,item.required,"
            + "item.latest_assignment_revision,item.created_at,item.updated_at,item.display_ordinal,"
            + "bundle.employee_name,revision.format_status,revision.review_status,revision.result_id,"
            + "result.kind result_kind,result.visibility result_visibility,invocation.state invocation_state,"
            + "CASE WHEN result.result_id IS NOT NULL AND result.kind IN ('DELEGATION_FINAL','TEAM_RESULT') "
            + "AND result.visibility='USER' AND result.body IS NOT NULL AND revision.result_sha256 IS NOT NULL "
            + "AND result.body_sha256=revision.result_sha256 AND result.byte_size BETWEEN 0 AND 1048576 "
            + "THEN TRUE ELSE FALSE END result_body_available,"
            + "revision.created_at revision_created_at,invocation.finished_at completed_at "
            + "FROM (SELECT i.work_item_ref,i.run_id,i.role_id,i.source_kind,i.employee_id,i.definition_version_id,"
            + "i.required,i.latest_assignment_revision,i.created_at,i.updated_at,"
            + "ROW_NUMBER() OVER (ORDER BY i.created_at,i.work_item_ref) display_ordinal "
            + "FROM platform_run_work_item i WHERE i.run_id=?) item "
            + "JOIN platform_agent_run owned_run ON owned_run.run_id=item.run_id AND owned_run.user_id=? "
            + "JOIN platform_agent_session owned_session ON owned_session.session_id=owned_run.session_id "
            + "AND owned_session.user_id=owned_run.user_id "
            + "LEFT JOIN platform_run_work_item_revision revision ON revision.work_item_ref=item.work_item_ref "
            + "AND revision.assignment_revision=item.latest_assignment_revision "
            + "LEFT JOIN platform_agent_result result ON result.result_id=revision.result_id "
            + "AND result.run_id=item.run_id "
            + "LEFT JOIN agent_definition_runtime_bundle bundle ON bundle.definition_version_id=item.definition_version_id "
            + "LEFT JOIN platform_run_delegation_invocation invocation ON invocation.invocation_id=("
            + "SELECT latest.invocation_id FROM platform_run_delegation_invocation latest "
            + "WHERE latest.run_id=item.run_id AND latest.work_item_ref=item.work_item_ref "
            + "AND latest.assignment_revision=item.latest_assignment_revision "
            + "ORDER BY latest.started_at DESC,latest.invocation_id DESC LIMIT 1) ";

    private final JdbcTemplate jdbc;

    public JdbcRunProgressQueryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnapshotFacts snapshot(UserId owner, RunId runId) {
        List<RunHeader> headers = jdbc.query("SELECT run.runtime_profile,run.state,run.definition_version_id,"
                        + "run.created_at,run.started_at,run.finished_at,session.next_event_cursor-1 session_cursor "
                        + "FROM platform_agent_run run JOIN platform_agent_session session "
                        + "ON session.session_id=run.session_id WHERE run.run_id=? AND run.user_id=?",
                (rs, row) -> new RunHeader(rs.getString("runtime_profile"), rs.getString("state"),
                        rs.getLong("definition_version_id"), instant(rs, "created_at"), instant(rs, "started_at"),
                        instant(rs, "finished_at"), rs.getLong("session_cursor")), runId.value(), owner.value());
        if (headers.size() != 1) throw new RunNotFoundException();
        RunHeader header = headers.get(0);
        List<WorkItemFact> allItems = readWorkItems(owner, runId, null, Integer.MAX_VALUE);
        Budget budget = readBudget(header.definitionVersionId());
        List<TeamFact> teams = readTeam(owner, runId);
        TeamFact team = teams.isEmpty() ? null : teams.get(0);
        int invocationCount = count("SELECT COUNT(*) FROM platform_run_delegation_invocation invocation "
                + "JOIN platform_agent_run run ON run.run_id=invocation.run_id AND run.user_id=? "
                + "WHERE invocation.run_id=?", owner.value(), runId.value());
        int activeInvocations = count("SELECT COUNT(*) FROM platform_run_delegation_invocation invocation "
                + "JOIN platform_agent_run run ON run.run_id=invocation.run_id AND run.user_id=? "
                + "WHERE invocation.run_id=? AND invocation.state IN ('ACCEPTED','ACTIVE')",
                owner.value(), runId.value());
        Instant updatedAt = max(header.createdAt(), header.startedAt(), header.finishedAt());
        for (WorkItemFact item : allItems) updatedAt = max(updatedAt, item.updatedAt(), item.completedAt());
        if (team != null) {
            updatedAt = max(updatedAt, team.startedAt(), team.completedAt(), team.lastActionAt());
            for (MemberFact member : team.members()) updatedAt = max(updatedAt, member.lastTransitionAt());
        }
        return new SnapshotFacts(knownProfile(header.runtimeProfile()), knownRunState(header.runState()),
                Math.max(0, header.sessionCursor()), updatedAt, allItems, invocationCount, activeInvocations,
                budget.invocationLimit(), budget.parallelLimit(), team);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<WorkItemFact> workItems(UserId owner, RunId runId, Position before, int limit) {
        if (limit < 1 || limit > 101) throw new IllegalArgumentException("work item limit is invalid");
        return readWorkItems(owner, runId, before, limit);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<WorkItemDetailFacts> workItem(UserId owner, RunId runId, String workItemRef) {
        List<WorkItemFact> rows = readWorkItems(owner, runId, null, 1, workItemRef);
        if (rows.isEmpty()) return Optional.empty();
        List<RevisionFact> revisions = jdbc.query("SELECT revision.assignment_revision,revision.format_status,"
                        + "revision.review_status,revision.result_id,result.kind result_kind,result.visibility result_visibility,"
                        + "CASE WHEN result.result_id IS NOT NULL AND result.kind IN ('DELEGATION_FINAL','TEAM_RESULT') "
                        + "AND result.visibility='USER' AND result.body IS NOT NULL AND revision.result_sha256 IS NOT NULL "
                        + "AND result.body_sha256=revision.result_sha256 AND result.byte_size BETWEEN 0 AND 1048576 "
                        + "THEN TRUE ELSE FALSE END result_body_available,"
                        + "revision.created_at,invocation.state invocation_state,invocation.finished_at completed_at "
                        + "FROM platform_run_work_item item JOIN platform_agent_run owned_run "
                        + "ON owned_run.run_id=item.run_id AND owned_run.user_id=? "
                        + "JOIN platform_agent_session owned_session ON owned_session.session_id=owned_run.session_id "
                        + "AND owned_session.user_id=owned_run.user_id "
                        + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=item.work_item_ref "
                        + "LEFT JOIN platform_agent_result result ON result.result_id=revision.result_id AND result.run_id=item.run_id "
                        + "LEFT JOIN platform_run_delegation_invocation invocation ON invocation.invocation_id=("
                        + "SELECT latest.invocation_id FROM platform_run_delegation_invocation latest "
                        + "WHERE latest.run_id=item.run_id AND latest.work_item_ref=item.work_item_ref "
                        + "AND latest.assignment_revision=revision.assignment_revision "
                        + "ORDER BY latest.started_at DESC,latest.invocation_id DESC LIMIT 1) "
                        + "WHERE item.run_id=? AND item.work_item_ref=? ORDER BY revision.assignment_revision",
                (rs, row) -> new RevisionFact(rs.getInt("assignment_revision"), rs.getString("invocation_state"),
                        rs.getString("format_status"), rs.getString("review_status"), rs.getString("result_id"),
                        rs.getString("result_kind"), rs.getString("result_visibility"),
                        rs.getBoolean("result_body_available"),
                        instant(rs, "created_at"), instant(rs, "completed_at")),
                owner.value(), runId.value(), workItemRef);
        return Optional.of(new WorkItemDetailFacts(rows.get(0), revisions));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<WorkItemResultFact> workItemResult(UserId owner, RunId runId, String workItemRef,
                                                       int assignmentRevision) {
        List<WorkItemResultFact> rows = jdbc.query("SELECT result.result_id,result.kind,result.visibility,result.media_type,"
                        + "result.body,result.body_sha256,result.byte_size,revision.result_sha256 revision_sha256,result.created_at "
                        + "FROM platform_run_work_item item JOIN platform_agent_run owned_run "
                        + "ON owned_run.run_id=item.run_id AND owned_run.user_id=? "
                        + "JOIN platform_agent_session owned_session ON owned_session.session_id=owned_run.session_id "
                        + "AND owned_session.user_id=owned_run.user_id "
                        + "JOIN platform_run_work_item_revision revision ON revision.work_item_ref=item.work_item_ref "
                        + "AND revision.assignment_revision=? AND revision.result_id IS NOT NULL "
                        + "JOIN platform_agent_result result ON result.result_id=revision.result_id "
                        + "AND result.run_id=owned_run.run_id AND result.kind IN ('DELEGATION_FINAL','TEAM_RESULT') "
                        + "AND result.visibility='USER' AND result.body IS NOT NULL "
                        + "AND result.byte_size BETWEEN 0 AND 1048576 AND OCTET_LENGTH(result.body)=result.byte_size "
                        + "AND revision.result_sha256 IS NOT NULL AND result.body_sha256=revision.result_sha256 "
                        + "WHERE item.run_id=? AND item.work_item_ref=?",
                (rs, row) -> new WorkItemResultFact(rs.getString("result_id"), rs.getString("kind"),
                        rs.getString("visibility"), rs.getString("media_type"), rs.getString("body"),
                        rs.getString("body_sha256"), rs.getLong("byte_size"), rs.getString("revision_sha256"),
                        instant(rs, "created_at")), owner.value(), assignmentRevision, runId.value(), workItemRef);
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    private List<WorkItemFact> readWorkItems(UserId owner, RunId runId, Position before, int limit) {
        return readWorkItems(owner, runId, before, limit, null);
    }

    private List<WorkItemFact> readWorkItems(UserId owner, RunId runId, Position before, int limit, String exactRef) {
        StringBuilder sql = new StringBuilder(ITEM_SELECT)
                .append("WHERE item.run_id=? ");
        List<Object> args = new ArrayList<>(List.of(runId.value(), owner.value(), runId.value()));
        if (exactRef != null) {
            sql.append("AND item.work_item_ref=? ");
            args.add(exactRef);
        }
        if (before != null) {
            sql.append("AND (item.created_at>? OR (item.created_at=? AND item.work_item_ref>?)) ");
            Timestamp cursorTime = Timestamp.from(before.createdAt());
            args.add(cursorTime);
            args.add(cursorTime);
            args.add(before.workItemRef());
        }
        sql.append("ORDER BY item.created_at,item.work_item_ref");
        if (limit != Integer.MAX_VALUE) {
            sql.append(" LIMIT ?");
            args.add(limit);
        }
        return jdbc.query(sql.toString(), (rs, row) -> workItem(rs), args.toArray());
    }

    private List<TeamFact> readTeam(UserId owner, RunId runId) {
        List<TeamRow> rows = jdbc.query("SELECT team.team_execution_id,team.state,team.started_at,team.completed_at,"
                        + "team.recovery_reason_code,action.action_type,action.reserved,action.last_action_at "
                        + "FROM platform_run_team_execution team JOIN platform_agent_run run "
                        + "ON run.run_id=team.run_id AND run.user_id=? "
                        + "LEFT JOIN (SELECT team_execution_id,action_type,SUM(action_count) reserved,MAX(reserved_at) last_action_at "
                        + "FROM platform_run_team_action_reservation GROUP BY team_execution_id,action_type) action "
                        + "ON action.team_execution_id=team.team_execution_id WHERE team.run_id=? "
                        + "ORDER BY team.started_at,action.action_type",
                (rs, row) -> new TeamRow(rs.getString("team_execution_id"), rs.getString("state"),
                        instant(rs, "started_at"), instant(rs, "completed_at"), rs.getString("recovery_reason_code"),
                        rs.getString("action_type"), rs.getInt("reserved"), instant(rs, "last_action_at")),
                owner.value(), runId.value());
        if (rows.isEmpty()) return List.of();
        TeamRow first = rows.get(0);
        List<MemberFact> members = jdbc.query("SELECT member.role_id,bundle.employee_name,member.member_kind,member.state,"
                        + "member.last_transition_ordinal,member.last_transition_at FROM platform_run_team_member_binding member "
                        + "LEFT JOIN agent_definition_runtime_bundle bundle ON bundle.definition_version_id=member.definition_version_id "
                        + "WHERE member.team_execution_id=? ORDER BY member.role_id",
                (rs, row) -> new MemberFact(rs.getString("role_id"), rs.getString("employee_name"),
                        rs.getString("member_kind"), rs.getString("state"), rs.getLong("last_transition_ordinal"),
                        instant(rs, "last_transition_at")), first.executionId());
        List<ActionBudgetFact> actionBudgets = new ArrayList<>();
        Instant lastActionAt = null;
        for (TeamRow row : rows) {
            if (row.actionType() == null) continue;
            actionBudgets.add(new ActionBudgetFact(row.actionType(), row.reserved()));
            lastActionAt = max(lastActionAt, row.lastActionAt());
        }
        return List.of(new TeamFact(first.executionId(), first.state(), first.startedAt(), first.completedAt(),
                first.reasonCode(), members, actionBudgets, lastActionAt));
    }

    private Budget readBudget(long definitionVersionId) {
        List<String> rows = jdbc.query("SELECT configuration_json FROM agent_definition_version WHERE id=?",
                (rs, row) -> rs.getString(1), definitionVersionId);
        if (rows.isEmpty() || rows.get(0) == null) return new Budget(null, null);
        try {
            JsonNode policy = JSON.readTree(rows.get(0)).path("runtimePolicy");
            Integer invocation = positiveOrZero(policy.path("maxExpertInvocationsPerRun"));
            Integer parallel = positiveOrZero(policy.path("maxParallelDelegations"));
            return new Budget(invocation, parallel);
        } catch (Exception invalidFrozenConfiguration) {
            return new Budget(null, null);
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static WorkItemFact workItem(ResultSet rs) throws SQLException {
        return new WorkItemFact(rs.getString("work_item_ref"), rs.getString("role_id"),
                rs.getString("employee_name"), rs.getString("source_kind"), rs.getBoolean("required"),
                rs.getInt("latest_assignment_revision"), rs.getString("invocation_state"),
                rs.getString("format_status"), rs.getString("review_status"), rs.getString("result_id"),
                rs.getString("result_kind"), rs.getString("result_visibility"),
                rs.getBoolean("result_body_available"),
                instant(rs, "revision_created_at"), instant(rs, "completed_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getInt("display_ordinal"));
    }

    private static Integer positiveOrZero(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0
                ? value.intValue() : null;
    }

    private static String knownProfile(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,32}") ? value : "UNKNOWN";
    }

    private static String knownRunState(String value) {
        try { return com.haizhuo.brain.platform.run.RunState.valueOf(value).name(); }
        catch (RuntimeException unknown) { return "UNKNOWN"; }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant max(Instant... values) {
        Instant latest = null;
        for (Instant value : values) if (value != null && (latest == null || value.isAfter(latest))) latest = value;
        return latest;
    }

    private record RunHeader(String runtimeProfile, String runState, long definitionVersionId,
                             Instant createdAt, Instant startedAt, Instant finishedAt, long sessionCursor) { }
    private record Budget(Integer invocationLimit, Integer parallelLimit) { }
    private record TeamRow(String executionId, String state, Instant startedAt, Instant completedAt,
                           String reasonCode, String actionType, int reserved, Instant lastActionAt) { }

}
