package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.*;
import com.haizhuo.brain.kernel.identity.SessionId;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentResultRepository implements AgentResultRepository {
    public static final int MAX_RESULT_BYTES = 1024 * 1024;
    private final JdbcTemplate jdbc;
    public JdbcAgentResultRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String saveRoot(ExecutionClaim claim, RunCompletion completion, Instant now) {
        return save(claim, "ROOT_FINAL", claim.run().id().value() + ":final", null,
                completion.mediaType(), completion.body(), EventVisibility.USER,
                DurableEventMetadata.execution(completion.descriptor()), now);
    }

    /** 资料和子调用使用独立业务键；不会写 Run 终态或创建渠道意图。 */
    public String save(ExecutionClaim claim, String kind, String resultKey, String invocationId,
                       String mediaType, String body, EventVisibility visibility,
                       DurableEventMetadata metadata, Instant now) {
        return save(claim, kind, resultKey, invocationId, mediaType, body, visibility, metadata, now, null);
    }

    public String saveDelegation(ExecutionClaim claim, String invocationId, String roleId,
                                 long employeeId, long definitionVersionId, String body,
                                 com.haizhuo.brain.runtime.api.event.AgentEventDescriptor descriptor, Instant now) {
        return saveDelegation(claim, invocationId, roleId, employeeId, definitionVersionId,
                "text/plain", body, descriptor, now);
    }

    public String saveDelegation(ExecutionClaim claim, String invocationId, String roleId,
                                 Long employeeId, Long definitionVersionId, String mediaType, String body,
                                 com.haizhuo.brain.runtime.api.event.AgentEventDescriptor descriptor, Instant now) {
        return save(claim, "DELEGATION_FINAL", "delegation:" + invocationId, invocationId,
                mediaType, body, EventVisibility.INTERNAL, DurableEventMetadata.execution(descriptor), now,
                new ExecutorIdentity(roleId, employeeId, definitionVersionId));
    }

    private String save(ExecutionClaim claim, String kind, String resultKey, String invocationId,
                        String mediaType, String body, EventVisibility visibility,
                        DurableEventMetadata metadata, Instant now, ExecutorIdentity executorOverride) {
        JdbcRunEventAppender.requireTransaction(jdbc);
        if (!java.util.Set.of("ROOT_FINAL", "DELEGATION_FINAL", "TEAM_RESULT", "RUN_MATERIAL").contains(kind))
            throw new IllegalArgumentException("Invalid result kind");
        if ("ROOT_FINAL".equals(kind) && !resultKey.equals(claim.run().id().value() + ":final"))
            throw new IllegalArgumentException("Invalid root result key");
        if (!"ROOT_FINAL".equals(kind) && resultKey.equals(claim.run().id().value() + ":final"))
            throw new IllegalArgumentException("Non-root result cannot use final key");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RESULT_BYTES) throw new ResultSizeExceededException();
        String hash = CanonicalJson.sha256Hex(bytes);
        String keyHash = CanonicalJson.sha256Hex((claim.run().id().value() + "|" + resultKey)
                .getBytes(StandardCharsets.UTF_8));
        var old = jdbc.query("SELECT result_id,body_sha256 FROM platform_agent_result WHERE result_key=? AND run_id=?",
                (rs, n) -> new String[] {rs.getString(1),rs.getString(2)}, keyHash, claim.run().id().value());
        if (!old.isEmpty()) {
            if (!hash.equals(old.get(0)[1])) throw new IllegalStateException("Immutable result conflicts");
            return old.get(0)[0];
        }
        String id = UUID.randomUUID().toString();
        ExecutorIdentity executor = executorOverride == null ? executorIdentity(claim.run()) : executorOverride;
        jdbc.update("INSERT INTO platform_agent_result(result_id,run_id,result_key,kind,attempt_id,invocation_id,"
                        + "executor_role_id,employee_id,definition_version_id,media_type,body,body_sha256,byte_size,schema_version,visibility,native_refs_json,check_metadata_json,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, claim.run().id().value(), keyHash, kind, claim.attempt().attemptId(), invocationId,
                executor.roleId(), executor.employeeId(), executor.definitionVersionId(), mediaType, body, hash, bytes.length, 2,
                visibility.name(), JdbcEventMetadata.json(metadata.nativeRefs()), "{}", Timestamp.from(now));
        return id;
    }

    private ExecutorIdentity executorIdentity(AgentRun run) {
        List<ExecutorIdentity> targets = jdbc.query("SELECT target.role_id,target.employee_id,target.definition_version_id "
                        + "FROM platform_agent_run_execution_target target JOIN platform_agent_run run ON run.run_id=target.run_id "
                        + "WHERE target.run_id=? AND run.user_id=?",
                (rs, row) -> new ExecutorIdentity(rs.getString("role_id"), rs.getLong("employee_id"),
                        rs.getLong("definition_version_id")), run.id().value(), run.userId().value());
        return targets.isEmpty()
                ? new ExecutorIdentity("coordinator", run.employeeId(), run.definitionVersionId())
                : targets.get(0);
    }

    @Override public Optional<AgentResult> findRoot(RunId runId, UserId owner) {
        Optional<AgentResult> result = jdbc.query("SELECT result.* FROM platform_agent_result result JOIN platform_agent_run run ON run.run_id=result.run_id "
                        + "WHERE result.run_id=? AND run.user_id=? AND result.kind='ROOT_FINAL' AND result.visibility='USER'",
                (rs,n) -> map(rs), runId.value(), owner.value()).stream().findFirst();
        if (result.isPresent()) return result;
        return jdbc.query("SELECT event.content,event.created_at FROM platform_agent_run_event event JOIN platform_agent_run run ON run.run_id=event.run_id "
                        + "WHERE event.run_id=? AND run.user_id=? AND event.event_type='RUN_COMPLETED' AND event.result_id IS NULL AND event.visibility='USER' ORDER BY event.sequence_no DESC LIMIT 1",
                (rs,n) -> {
                    String body = rs.getString(1); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    return new AgentResult(null,runId,"ROOT_FINAL","text/plain",body,CanonicalJson.sha256Hex(bytes),bytes.length,1,
                            EventVisibility.USER,null,null,Map.of("legacySummary",true),rs.getTimestamp(2).toInstant(),true);
                }, runId.value(), owner.value()).stream().findFirst();
    }

    @Override public Optional<AgentResult> findOwned(String resultId, RunId runId, UserId owner) {
        return jdbc.query("SELECT result.* FROM platform_agent_result result JOIN platform_agent_run run ON run.run_id=result.run_id "
                        + "WHERE result.result_id=? AND result.run_id=? AND run.user_id=? AND result.visibility='USER'",
                (rs,n) -> map(rs), resultId, runId.value(), owner.value()).stream().findFirst();
    }

    @Override public Optional<AgentResult> findReferenceable(String resultId, SessionId sessionId, UserId owner) {
        return jdbc.query("SELECT result.* FROM platform_agent_result result "
                        + "JOIN platform_agent_run source ON source.run_id=result.run_id "
                        + "WHERE result.result_id=? AND source.session_id=? AND source.user_id=? "
                        + "AND source.state='SUCCEEDED' AND result.visibility='USER' "
                        + "AND result.body IS NOT NULL AND result.body_sha256 IS NOT NULL",
                (rs, row) -> map(rs), resultId, sessionId.value(), owner.value()).stream().findFirst();
    }

    @Override public List<AgentResult> listReferenceable(SessionId sessionId, UserId owner, int limit) {
        return jdbc.query("SELECT result.* FROM platform_agent_result result "
                        + "JOIN platform_agent_run source ON source.run_id=result.run_id "
                        + "WHERE source.session_id=? AND source.user_id=? AND source.state='SUCCEEDED' "
                        + "AND result.visibility='USER' AND result.body IS NOT NULL AND result.body_sha256 IS NOT NULL "
                        + "ORDER BY result.created_at DESC,result.result_id DESC LIMIT ?",
                (rs, row) -> map(rs), sessionId.value(), owner.value(), Math.max(1, Math.min(limit, 100)));
    }

    @Override public List<AgentResult> findReferencesForRun(RunId runId, UserId owner) {
        Integer expected = jdbc.queryForObject("SELECT COUNT(*) FROM platform_agent_run_result_reference ref "
                        + "JOIN platform_agent_run accepted ON accepted.run_id=ref.run_id "
                        + "WHERE ref.run_id=? AND accepted.user_id=?", Integer.class, runId.value(), owner.value());
        List<AgentResult> found = jdbc.query("SELECT result.* FROM platform_agent_run_result_reference ref "
                        + "JOIN platform_agent_run accepted ON accepted.run_id=ref.run_id "
                        + "JOIN platform_agent_run source ON source.run_id=ref.source_run_id "
                        + "JOIN platform_agent_result result ON result.result_id=ref.result_id AND result.run_id=source.run_id "
                        + "WHERE ref.run_id=? AND accepted.user_id=? AND source.user_id=accepted.user_id "
                        + "AND source.session_id=accepted.session_id AND source.state='SUCCEEDED' "
                        + "AND result.visibility='USER' AND result.body IS NOT NULL "
                        + "AND result.body_sha256=ref.body_sha256 ORDER BY ref.reference_order",
                (rs, row) -> map(rs), runId.value(), owner.value());
        if (expected != null && expected != found.size())
            throw new IllegalStateException("A frozen Run result reference is missing or no longer valid");
        return found;
    }

    private static AgentResult map(ResultSet rs) throws SQLException {
        return new AgentResult(rs.getString("result_id"), new RunId(rs.getString("run_id")), rs.getString("kind"),
                rs.getString("media_type"),rs.getString("body"),rs.getString("body_sha256"),rs.getLong("byte_size"),
                rs.getInt("schema_version"),EventVisibility.valueOf(rs.getString("visibility")),rs.getString("attempt_id"),
                rs.getString("invocation_id"),Map.of(),rs.getTimestamp("created_at").toInstant(),false,
                rs.getString("executor_role_id"), nullableLong(rs, "employee_id"), nullableLong(rs, "definition_version_id"));
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private record ExecutorIdentity(String roleId, Long employeeId, Long definitionVersionId) { }
}
