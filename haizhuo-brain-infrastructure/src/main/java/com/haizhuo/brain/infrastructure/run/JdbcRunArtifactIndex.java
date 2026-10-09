package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RunArtifact;
import com.haizhuo.brain.platform.run.RunArtifactIndex;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 通过 JDBC 实现按属主过滤且支持幂等的成果物索引。 */
@Repository
public class JdbcRunArtifactIndex implements RunArtifactIndex {
    private static final String INSERT_COLUMNS = "artifact_id,run_id,result_id,owner_user_id,request_id,request_digest,"
            + "visibility,title,media_type,byte_size,sha256,blob_ref,state,created_at";
    private static final String SELECT_COLUMNS = "artifact.artifact_id,artifact.run_id,artifact.result_id,"
            + "artifact.owner_user_id,artifact.request_id,artifact.request_digest,artifact.title,artifact.media_type,"
            + "artifact.byte_size,artifact.sha256,artifact.blob_ref,artifact.state,artifact.created_at";
    private final JdbcTemplate jdbc;

    public JdbcRunArtifactIndex(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public StageResult stage(RunArtifact artifact) {
        Optional<RunArtifact> existing = findByRequest(artifact.runId(), artifact.ownerUserId(), artifact.requestId());
        if (existing.isPresent()) return new StageResult(existing.get(), false);
        try {
            jdbc.update("INSERT INTO platform_run_artifact(" + INSERT_COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    artifact.artifactId(), artifact.runId().value(), artifact.resultId(), artifact.ownerUserId().value(),
                    artifact.requestId(), artifact.requestDigest(), "USER", artifact.title(), artifact.mediaType(),
                    artifact.byteSize(), artifact.sha256(), artifact.blobRef(), artifact.state().name(),
                    Timestamp.from(artifact.createdAt()));
            return new StageResult(artifact, true);
        } catch (DuplicateKeyException raced) {
            return findByRequest(artifact.runId(), artifact.ownerUserId(), artifact.requestId())
                    .map(saved -> new StageResult(saved, false)).orElseThrow(() -> raced);
        }
    }

    @Override
    @Transactional
    public RunArtifact markAvailable(String artifactId, UserId owner, String sha256, long byteSize) {
        find(artifactId, owner).orElseThrow(IllegalStateException::new);
        int updated = jdbc.update("UPDATE platform_run_artifact SET state='AVAILABLE' WHERE artifact_id=? "
                        + "AND owner_user_id=? AND state IN ('STAGED','UNAVAILABLE') AND sha256=? AND byte_size=?",
                artifactId, owner.value(), sha256, byteSize);
        RunArtifact current = find(artifactId, owner).orElseThrow(IllegalStateException::new);
        if (updated == 0 && (current.state() != RunArtifact.State.AVAILABLE
                || !sha256.equalsIgnoreCase(current.sha256()) || byteSize != current.byteSize())) {
            throw new IllegalStateException("Artifact staging state changed");
        }
        return current;
    }

    @Override
    @Transactional
    public void markUnavailable(String artifactId, UserId owner) {
        if (find(artifactId, owner).isEmpty()) return;
        jdbc.update("UPDATE platform_run_artifact SET state='UNAVAILABLE' WHERE artifact_id=? AND owner_user_id=? "
                + "AND state='AVAILABLE'", artifactId, owner.value());
    }

    @Override
    public Optional<RunArtifact> find(String artifactId, UserId owner) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM platform_run_artifact artifact "
                        + "JOIN platform_agent_run run ON run.run_id=artifact.run_id "
                        + "JOIN platform_agent_result source ON source.result_id=artifact.result_id "
                        + "AND source.run_id=artifact.run_id AND source.kind='ROOT_FINAL' AND source.visibility='USER' "
                        + "WHERE artifact.artifact_id=? AND artifact.owner_user_id=? AND run.user_id=? "
                        + "AND run.state='SUCCEEDED' AND artifact.visibility='USER' AND artifact.media_type='text/markdown' "
                        + "AND source.body_sha256=artifact.sha256 AND source.byte_size=artifact.byte_size",
                JdbcRunArtifactIndex::map, artifactId, owner.value(), owner.value()).stream().findFirst();
    }

    @Override
    public Page page(RunId runId, UserId owner, Position before, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        List<RunArtifact> rows;
        if (before == null) {
            rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM platform_run_artifact artifact "
                            + "JOIN platform_agent_run run ON run.run_id=artifact.run_id "
                            + "JOIN platform_agent_result source ON source.result_id=artifact.result_id "
                            + "AND source.run_id=artifact.run_id AND source.kind='ROOT_FINAL' AND source.visibility='USER' "
                            + "WHERE artifact.run_id=? AND artifact.owner_user_id=? AND run.user_id=? "
                            + "AND run.state='SUCCEEDED' AND artifact.visibility='USER' AND artifact.media_type='text/markdown' "
                            + "AND source.body_sha256=artifact.sha256 AND source.byte_size=artifact.byte_size "
                            + "ORDER BY artifact.created_at DESC,artifact.artifact_id DESC LIMIT ?",
                    JdbcRunArtifactIndex::map, runId.value(), owner.value(), owner.value(), bounded + 1);
        } else {
            rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM platform_run_artifact artifact "
                            + "JOIN platform_agent_run run ON run.run_id=artifact.run_id "
                            + "JOIN platform_agent_result source ON source.result_id=artifact.result_id "
                            + "AND source.run_id=artifact.run_id AND source.kind='ROOT_FINAL' AND source.visibility='USER' "
                            + "WHERE artifact.run_id=? AND artifact.owner_user_id=? AND run.user_id=? "
                            + "AND run.state='SUCCEEDED' AND artifact.visibility='USER' AND artifact.media_type='text/markdown' "
                            + "AND source.body_sha256=artifact.sha256 AND source.byte_size=artifact.byte_size "
                            + "AND (artifact.created_at < ? OR (artifact.created_at = ? AND artifact.artifact_id < ?)) "
                            + "ORDER BY artifact.created_at DESC,artifact.artifact_id DESC LIMIT ?",
                    JdbcRunArtifactIndex::map, runId.value(), owner.value(), owner.value(),
                    Timestamp.from(before.createdAt()), Timestamp.from(before.createdAt()), before.artifactId(), bounded + 1);
        }
        boolean hasMore = rows.size() > bounded;
        return new Page(hasMore ? rows.subList(0, bounded) : rows, hasMore);
    }

    private Optional<RunArtifact> findByRequest(RunId runId, UserId owner, String requestId) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM platform_run_artifact artifact "
                        + "JOIN platform_agent_run run ON run.run_id=artifact.run_id "
                        + "JOIN platform_agent_result source ON source.result_id=artifact.result_id "
                        + "AND source.run_id=artifact.run_id AND source.kind='ROOT_FINAL' AND source.visibility='USER' "
                        + "WHERE artifact.run_id=? AND artifact.owner_user_id=? AND run.user_id=? "
                        + "AND run.state='SUCCEEDED' AND artifact.request_id=? AND artifact.visibility='USER' "
                        + "AND artifact.media_type='text/markdown' AND source.body_sha256=artifact.sha256 "
                        + "AND source.byte_size=artifact.byte_size",
                        JdbcRunArtifactIndex::map, runId.value(), owner.value(), owner.value(), requestId)
                .stream().findFirst();
    }

    private static RunArtifact map(ResultSet rs, int row) throws SQLException {
        return new RunArtifact(rs.getString("artifact_id"), new RunId(rs.getString("run_id")),
                rs.getString("result_id"), new UserId(rs.getLong("owner_user_id")), rs.getString("request_id"),
                rs.getString("request_digest"), rs.getString("title"), rs.getString("media_type"),
                rs.getLong("byte_size"), rs.getString("sha256"), rs.getString("blob_ref"),
                RunArtifact.State.valueOf(rs.getString("state")), rs.getTimestamp("created_at").toInstant());
    }
}
