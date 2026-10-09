package com.haizhuo.brain.infrastructure.session;

import com.haizhuo.brain.platform.session.SessionHistoryQuery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 执行属主范围内的 keyset 查询；不会读取结果正文或 Run 内部字段。
 */
@Repository
public class JdbcSessionHistoryQuery implements SessionHistoryQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSessionHistoryQuery(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public boolean ownsSession(long ownerUserId, String sessionId) {
        Integer count = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM platform_agent_session WHERE session_id=? AND user_id=?",
                Integer.class, sessionId, ownerUserId);
        return count != null && count == 1;
    }

    @Override
    public List<SessionItem> findSessions(SessionFilter filter, Position before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT s.session_id,s.employee_id,s.definition_version_id,s.status,"
                + "s.created_at,s.last_active_at FROM platform_agent_session s WHERE s.user_id=:owner");
        MapSqlParameterSource args = new MapSqlParameterSource("owner", filter.ownerUserId())
                .addValue("limit", limit);
        if (filter.employeeId() != null) {
            sql.append(" AND s.employee_id=:employeeId");
            args.addValue("employeeId", filter.employeeId());
        }
        if (filter.status() != null) {
            sql.append(" AND s.status=:status");
            args.addValue("status", filter.status());
        }
        appendPosition(sql, args, "s", "session_id", before);
        sql.append(" ORDER BY s.created_at DESC,s.session_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), args, (rs, row) -> new SessionItem(rs.getString("session_id"),
                rs.getLong("employee_id"), nullableLong(rs, "definition_version_id"), rs.getString("status"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_active_at"))));
    }

    @Override
    public List<RunItem> findRuns(RunFilter filter, Position before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT r.run_id,r.session_id,r.state,r.definition_version_id,r.created_at,"
                + "CASE WHEN r.state='QUEUED' THEN 1+(SELECT COUNT(*) FROM platform_agent_run earlier "
                + "WHERE earlier.session_id=r.session_id AND earlier.state='QUEUED' AND "
                + "(earlier.created_at<r.created_at OR (earlier.created_at=r.created_at AND earlier.run_id<r.run_id))) "
                + "ELSE 0 END AS queue_position,COALESCE(target.role_id,'coordinator') AS executor_role_id,"
                + "COALESCE(target.employee_id,r.employee_id) AS executor_employee_id,"
                + "COALESCE(target.definition_version_id,r.definition_version_id) AS executor_definition_version_id,"
                + "COALESCE(target.execution_mode,'DIRECT') AS execution_mode FROM platform_agent_run r "
                + "LEFT JOIN platform_agent_run_execution_target target ON target.run_id=r.run_id "
                + "JOIN platform_agent_session s ON s.session_id=r.session_id AND s.user_id=r.user_id "
                + "WHERE r.session_id=:sessionId AND r.user_id=:owner");
        MapSqlParameterSource args = new MapSqlParameterSource("owner", filter.ownerUserId())
                .addValue("sessionId", filter.sessionId()).addValue("limit", limit);
        if (filter.state() != null) {
            sql.append(" AND r.state=:state");
            args.addValue("state", filter.state());
        }
        appendPosition(sql, args, "r", "run_id", before);
        sql.append(" ORDER BY r.created_at DESC,r.run_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), args, (rs, row) -> new RunItem(rs.getString("run_id"),
                rs.getString("session_id"), rs.getString("state"), rs.getLong("definition_version_id"),
                instant(rs.getTimestamp("created_at")), rs.getInt("queue_position"), rs.getString("executor_role_id"),
                rs.getLong("executor_employee_id"), rs.getLong("executor_definition_version_id"),
                rs.getString("execution_mode")));
    }

    @Override
    public List<ResultItem> findResults(ResultFilter filter, Position before, int limit) {
        StringBuilder sql = new StringBuilder("SELECT result.result_id,result.run_id,result.kind,result.media_type,"
                + "result.body_sha256,result.byte_size,result.created_at,result.executor_role_id,result.employee_id,"
                + "result.definition_version_id FROM platform_agent_result result "
                + "JOIN platform_agent_run source ON source.run_id=result.run_id "
                + "JOIN platform_agent_session s ON s.session_id=source.session_id AND s.user_id=source.user_id "
                + "WHERE source.session_id=:sessionId AND source.user_id=:owner AND source.state='SUCCEEDED' "
                + "AND result.visibility='USER' AND result.body IS NOT NULL AND result.body_sha256 IS NOT NULL");
        MapSqlParameterSource args = new MapSqlParameterSource("owner", filter.ownerUserId())
                .addValue("sessionId", filter.sessionId()).addValue("limit", limit);
        appendPosition(sql, args, "result", "result_id", before);
        sql.append(" ORDER BY result.created_at DESC,result.result_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), args, (rs, row) -> new ResultItem(rs.getString("result_id"),
                rs.getString("run_id"), rs.getString("kind"), rs.getString("media_type"), rs.getString("body_sha256"),
                rs.getLong("byte_size"), instant(rs.getTimestamp("created_at")), rs.getString("executor_role_id"),
                nullableLong(rs, "employee_id"), nullableLong(rs, "definition_version_id")));
    }

    private static void appendPosition(StringBuilder sql, MapSqlParameterSource args, String alias, String idColumn,
                                       Position before) {
        if (before == null) return;
        sql.append(" AND (").append(alias).append(".created_at<:beforeCreatedAt OR (")
                .append(alias).append(".created_at=:beforeCreatedAt AND ").append(alias)
                .append('.').append(idColumn).append("<:beforeId))");
        args.addValue("beforeCreatedAt", Timestamp.from(before.createdAt())).addValue("beforeId", before.id());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }
}
