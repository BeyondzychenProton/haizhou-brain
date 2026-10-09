package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.platform.run.RecoveryRunQuery;
import com.haizhuo.brain.platform.run.RecoveryRunQueryStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 仅投影经过筛选的恢复事实；不会读取租约令牌或事件正文。 */
@Repository
public class JdbcRecoveryRunQueryRepository implements RecoveryRunQueryStore {
    private static final List<String> PUBLIC_STATUS_EVENTS = List.of(
            "RUN_QUEUED", "RUN_STARTED", "RUN_COMPLETED", "RUN_FAILED", "RUN_CANCELLED", "RUN_EXPIRED",
            "RUN_RECOVERY_REQUIRED", "RUN_RECOVERY_TERMINATED");

    private final JdbcTemplate jdbc;

    public JdbcRecoveryRunQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecoveryRunQuery.Summary> findPage(RecoveryRunQuery.Filter filter,
                                                   Instant beforeCreatedAt, String beforeRunId, int limit) {
        StringBuilder sql = new StringBuilder(summarySelect())
                .append(" WHERE r.state=?");
        List<Object> args = new ArrayList<>();
        args.add(filter.state());
        if (filter.userId() != null) {
            sql.append(" AND r.user_id=?");
            args.add(filter.userId());
        }
        if (filter.employeeId() != null) {
            sql.append(" AND r.employee_id=?");
            args.add(filter.employeeId());
        }
        if (filter.createdFrom() != null) {
            sql.append(" AND r.created_at>=?");
            args.add(Timestamp.from(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            sql.append(" AND r.created_at<=?");
            args.add(Timestamp.from(filter.createdTo()));
        }
        if (beforeCreatedAt != null) {
            sql.append(" AND (r.created_at<? OR (r.created_at=? AND r.run_id<?))");
            args.add(Timestamp.from(beforeCreatedAt));
            args.add(Timestamp.from(beforeCreatedAt));
            args.add(beforeRunId);
        }
        sql.append(" ORDER BY r.created_at DESC,r.run_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> summary(rs), args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryRunQuery.Detail> findDetail(String runId) {
        List<RecoveryRunQuery.Summary> summaries = jdbc.query(summarySelect() + " WHERE r.run_id=?",
                (rs, row) -> summary(rs), runId);
        if (summaries.isEmpty()) return Optional.empty();

        RecoveryRunQuery.Summary summary = summaries.get(0);
        RecoveryRunQuery.Attempt attempt = latestAttempt(runId).orElse(null);
        RecoveryRunQuery.Eligibility eligibility = eligibility(summary, attempt);
        List<RecoveryRunQuery.PublicEvent> events = publicEvents(runId);
        List<RecoveryRunQuery.Action> actions = recoveryActions(runId);
        RecoveryRunQuery.SessionActivity activity = sessionActivity(summary.sessionId());
        return Optional.of(new RecoveryRunQuery.Detail(summary, attempt, eligibility, events, actions,
                activity, Instant.now()));
    }

    private Optional<RecoveryRunQuery.Attempt> latestAttempt(String runId) {
        List<RecoveryRunQuery.Attempt> rows = jdbc.query("SELECT attempt_id,attempt_no,worker_id,fence_token,state,"
                        + "heartbeat_at,lease_expires_at,started_at,finished_at "
                        + "FROM platform_run_execution_attempt WHERE run_id=? ORDER BY attempt_no DESC LIMIT 1",
                (rs, row) -> new RecoveryRunQuery.Attempt(rs.getString("attempt_id"), rs.getInt("attempt_no"),
                        rs.getString("worker_id"), rs.getLong("fence_token"), rs.getString("state"),
                        instant(rs, "heartbeat_at"), instant(rs, "lease_expires_at"),
                        instant(rs, "started_at"), instant(rs, "finished_at")), runId);
        return rows.stream().findFirst();
    }

    private List<RecoveryRunQuery.PublicEvent> publicEvents(String runId) {
        String placeholders = String.join(",", Collections.nCopies(PUBLIC_STATUS_EVENTS.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(runId);
        args.addAll(PUBLIC_STATUS_EVENTS);
        args.add(100);
        List<RecoveryRunQuery.PublicEvent> descending = jdbc.query("SELECT event_type,created_at "
                        + "FROM platform_agent_run_event WHERE run_id=? AND event_type IN (" + placeholders + ") "
                        + "ORDER BY sequence_no DESC LIMIT ?",
                (rs, row) -> new RecoveryRunQuery.PublicEvent(rs.getString("event_type"),
                        instant(rs, "created_at")), args.toArray());
        Collections.reverse(descending);
        return descending;
    }

    private List<RecoveryRunQuery.Action> recoveryActions(String runId) {
        return jdbc.query("SELECT request_id,actor_user_id,expected_fence_token,reason,created_at "
                        + "FROM platform_run_recovery_action WHERE run_id=? "
                        + "ORDER BY created_at DESC,request_id DESC LIMIT 100",
                (rs, row) -> new RecoveryRunQuery.Action(rs.getString("request_id"), rs.getLong("actor_user_id"),
                        rs.getLong("expected_fence_token"), rs.getString("reason"),
                        "已记录外部停止证据引用", instant(rs, "created_at")), runId);
    }

    private RecoveryRunQuery.SessionActivity sessionActivity(String sessionId) {
        List<RecoveryRunQuery.SessionRunFact> runs = jdbc.query("SELECT run.run_id,run.state,run.created_at,"
                        + "CASE WHEN run.state='QUEUED' THEN 1+(SELECT COUNT(*) FROM platform_agent_run earlier "
                        + "WHERE earlier.session_id=run.session_id AND earlier.state='QUEUED' "
                        + "AND (earlier.created_at<run.created_at OR (earlier.created_at=run.created_at "
                        + "AND earlier.run_id<run.run_id))) ELSE 0 END queue_position "
                        + "FROM platform_agent_run run WHERE run.session_id=? "
                        + "AND run.state IN ('QUEUED','RUNNING','WAITING_TOOL','WAITING_CONFIRMATION','CANCELLING','RECOVERY_REQUIRED') "
                        + "ORDER BY run.created_at,run.run_id LIMIT 20",
                (rs, row) -> new RecoveryRunQuery.SessionRunFact(rs.getString("run_id"), rs.getString("state"),
                        instant(rs, "created_at"), rs.getInt("queue_position")), sessionId);
        return new RecoveryRunQuery.SessionActivity(!runs.isEmpty(), runs, Instant.now());
    }

    private static RecoveryRunQuery.Eligibility eligibility(RecoveryRunQuery.Summary summary,
                                                             RecoveryRunQuery.Attempt attempt) {
        if (!"RECOVERY_REQUIRED".equals(summary.state()))
            return new RecoveryRunQuery.Eligibility(false, "NOT_RECOVERY_REQUIRED", null);
        if ("LEGACY_STABLE".equals(summary.runtimeProfile()))
            return new RecoveryRunQuery.Eligibility(false, "LEGACY_PROFILE", null);
        if (attempt == null)
            return new RecoveryRunQuery.Eligibility(false, "ATTEMPT_MISSING", null);
        if (attempt.fenceToken() <= 0)
            return new RecoveryRunQuery.Eligibility(false, "FENCE_UNAVAILABLE", null);
        return new RecoveryRunQuery.Eligibility(true, "ELIGIBLE", attempt.fenceToken());
    }

    private static String summarySelect() {
        return "SELECT r.run_id,r.session_id,r.user_id,r.employee_id,r.definition_version_id,r.runtime_profile,"
                + "r.state,r.failure_code,r.created_at,r.started_at,r.finished_at,"
                + "(SELECT MIN(e.created_at) FROM platform_agent_run_event e "
                + "WHERE e.run_id=r.run_id AND e.event_type='RUN_RECOVERY_REQUIRED') AS recovery_required_at "
                + "FROM platform_agent_run r";
    }

    private static RecoveryRunQuery.Summary summary(ResultSet rs) throws SQLException {
        Instant recoveryRequiredAt = instant(rs, "recovery_required_at");
        return new RecoveryRunQuery.Summary(rs.getString("run_id"), rs.getString("session_id"),
                rs.getLong("user_id"), rs.getLong("employee_id"), rs.getLong("definition_version_id"),
                rs.getString("runtime_profile"), rs.getString("state"), rs.getString("failure_code"),
                instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
                recoveryRequiredAt, recoveryRequiredAt == null ? "UNKNOWN" : "KNOWN");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
