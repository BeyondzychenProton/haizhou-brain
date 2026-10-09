package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.run.RunFeedback;
import com.haizhuo.brain.platform.run.RunFeedbackRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public final class JdbcRunFeedbackRepository implements RunFeedbackRepository {
    private final JdbcTemplate jdbc;

    public JdbcRunFeedbackRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public SaveResult saveOrGet(RunFeedback proposed) {
        try {
            jdbc.update("INSERT INTO platform_run_feedback(feedback_id,run_id,user_id,client_request_id,request_digest,"
                            + "score_value,feedback_comment,created_at,export_disposition) VALUES(?,?,?,?,?,?,?,?,?)",
                    proposed.feedbackId(), proposed.runId().value(), proposed.userId().value(),
                    proposed.clientRequestId(), proposed.requestDigest(), proposed.value(), proposed.comment(),
                    Timestamp.from(proposed.createdAt()), proposed.exportDisposition().name());
            return new SaveResult(proposed, true);
        } catch (DuplicateKeyException duplicate) {
            return findByRequest(proposed.userId(), proposed.runId(), proposed.clientRequestId())
                    .map(existing -> new SaveResult(existing, false)).orElseThrow(() -> duplicate);
        }
    }

    @Override public Optional<RunFeedback> findByRequest(UserId owner, RunId runId, String clientRequestId) {
        return jdbc.query("SELECT * FROM platform_run_feedback WHERE user_id=? AND run_id=? AND client_request_id=?",
                JdbcRunFeedbackRepository::map, owner.value(), runId.value(), clientRequestId).stream().findFirst();
    }

    @Override public Optional<RunFeedback> updateExportDisposition(UserId owner, RunId runId, String feedbackId,
                                                                    RunFeedback.ExportDisposition disposition) {
        jdbc.update("UPDATE platform_run_feedback SET export_disposition=? WHERE user_id=? AND run_id=? "
                        + "AND feedback_id=? AND export_disposition='QUEUE_STATUS_UNKNOWN'",
                disposition.name(), owner.value(), runId.value(), feedbackId);
        return jdbc.query("SELECT * FROM platform_run_feedback WHERE user_id=? AND run_id=? AND feedback_id=?",
                JdbcRunFeedbackRepository::map, owner.value(), runId.value(), feedbackId).stream().findFirst();
    }

    @Override public List<RunFeedback> list(UserId owner, RunId runId, Position before, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 101));
        if (before == null) {
            return jdbc.query("SELECT * FROM platform_run_feedback WHERE user_id=? AND run_id=? "
                            + "ORDER BY created_at DESC,feedback_id DESC LIMIT ?",
                    JdbcRunFeedbackRepository::map, owner.value(), runId.value(), boundedLimit);
        }
        Timestamp cursorTime = Timestamp.from(before.createdAt());
        return jdbc.query("SELECT * FROM platform_run_feedback WHERE user_id=? AND run_id=? "
                        + "AND (created_at<? OR (created_at=? AND feedback_id<?)) "
                        + "ORDER BY created_at DESC,feedback_id DESC LIMIT ?",
                JdbcRunFeedbackRepository::map, owner.value(), runId.value(), cursorTime, cursorTime,
                before.feedbackId(), boundedLimit);
    }

    private static RunFeedback map(ResultSet rs, int row) throws SQLException {
        return new RunFeedback(rs.getString("feedback_id"), new RunId(rs.getString("run_id")),
                new UserId(rs.getLong("user_id")), rs.getString("client_request_id"),
                rs.getString("request_digest"), rs.getDouble("score_value"), rs.getString("feedback_comment"),
                rs.getTimestamp("created_at").toInstant(),
                RunFeedback.ExportDisposition.valueOf(rs.getString("export_disposition")));
    }
}
