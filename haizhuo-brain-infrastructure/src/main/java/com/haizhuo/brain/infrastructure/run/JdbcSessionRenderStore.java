package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.SessionRenderBatch;
import com.haizhuo.brain.platform.run.SessionRenderDelta;
import com.haizhuo.brain.platform.run.SessionRenderMessage;
import com.haizhuo.brain.platform.run.SessionRenderRun;
import com.haizhuo.brain.platform.run.SessionRenderStore;
import com.haizhuo.brain.platform.run.SessionRenderView;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 基于 MySQL 的可重建消息投影；Run 事实和正式结果仍是权威数据。 */
@Repository
public class JdbcSessionRenderStore implements SessionRenderStore {
    private static final Duration BATCH_TTL = Duration.ofMinutes(10);
    private static final long MAX_DRAFT_BYTES_PER_RUN = 2L * 1024L * 1024L;
    private final JdbcTemplate jdbc;

    public JdbcSessionRenderStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public long append(SessionRenderDelta delta) {
        JdbcRunEventAppender.requireTransaction(jdbc);
        String sessionId = jdbc.query("SELECT session_id FROM platform_agent_run WHERE run_id=? AND session_id=?",
                (rs, row) -> rs.getString(1), delta.runId().value(), delta.sessionId().value())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Render Run was not found"));
        if (jdbc.query("SELECT session_id FROM platform_agent_session WHERE session_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), sessionId).isEmpty())
            throw new IllegalStateException("Render Session was not found");
        List<String> states = jdbc.query("SELECT state FROM platform_agent_run WHERE run_id=? AND session_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), delta.runId().value(), sessionId);
        if (states.isEmpty() || !"RUNNING".equals(states.get(0))) return currentCursor(sessionId);

        List<AttemptRow> latest = jdbc.query("SELECT attempt_id,fence_token,state,lease_expires_at "
                        + "FROM platform_run_execution_attempt WHERE run_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                (rs, row) -> new AttemptRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()), delta.runId().value());
        if (latest.isEmpty() || !latest.get(0).attemptId().equals(delta.attemptId())
                || latest.get(0).fenceToken() != delta.fenceToken()
                || !"RUNNING".equals(latest.get(0).state())
                || !latest.get(0).leaseExpiresAt().isAfter(delta.occurredAt()))
            return currentCursor(sessionId);

        ensureRenderState(sessionId, delta.occurredAt());
        RenderState render = lockRenderState(sessionId);
        pruneExpiredBatches(sessionId, render, Instant.now());
        if ("DEGRADED".equals(render.status())) return render.committedCursor();
        ensureAttemptState(delta);
        AttemptRenderState attempt = lockAttemptState(delta);

        String messageId = messageId(delta);
        String blockId = blockId(delta);
        String digest = CanonicalJson.sha256Hex(delta.text().getBytes(StandardCharsets.UTF_8));
        List<Duplicate> duplicate = jdbc.query("SELECT render_cursor,to_offset,content_sha256 FROM platform_session_render_batch "
                        + "WHERE session_id=? AND run_id=? AND attempt_id=? AND from_offset=? FOR UPDATE",
                (rs, row) -> new Duplicate(rs.getLong(1), rs.getLong(2), rs.getString(3)),
                sessionId, delta.runId().value(), delta.attemptId(), delta.fromOffset());
        if (!duplicate.isEmpty()) {
            Duplicate found = duplicate.get(0);
            if (found.toOffset() == delta.toOffset() && found.digest().equals(digest)) return found.renderCursor();
            markDegraded(sessionId, "CONFLICTING_REPLAY", delta.occurredAt());
            return render.committedCursor();
        }
        if (attempt.lastTextOffset() != delta.fromOffset()) {
            markDegraded(sessionId, attempt.lastTextOffset() > delta.fromOffset()
                    ? "OFFSET_OVERLAP" : "OFFSET_GAP", delta.occurredAt());
            return render.committedCursor();
        }

        long runBytes = jdbc.queryForObject("SELECT COALESCE(SUM(b.byte_size),0) FROM platform_session_message m "
                        + "JOIN platform_session_message_block b ON b.message_id=m.message_id "
                        + "WHERE m.run_id=? AND m.kind='ASSISTANT_DRAFT'", Long.class, delta.runId().value());
        long deltaBytes = delta.text().getBytes(StandardCharsets.UTF_8).length;
        if (runBytes + deltaBytes > MAX_DRAFT_BYTES_PER_RUN) {
            markDegraded(sessionId, "DRAFT_LIMIT_REACHED", delta.occurredAt());
            return render.committedCursor();
        }

        ensureMessage(delta, messageId, render.committedCursor() + 1, executorRoleId(delta.runId().value()));
        ensureBlock(delta, messageId, blockId);
        jdbc.update("UPDATE platform_session_message_block SET safe_body=CONCAT(safe_body,?),last_applied_offset=?,"
                        + "byte_size=byte_size+?,body_sha256=? WHERE message_id=? AND block_id=?",
                delta.text(), delta.toOffset(), deltaBytes,
                bodyDigestAfterAppend(messageId, blockId, delta.text()), messageId, blockId);
        long cursor = render.nextCursor();
        String segments = CanonicalJson.write(List.of(Map.of("messageId", messageId, "blockId", blockId,
                "fromOffset", delta.fromOffset(), "toOffset", delta.toOffset(), "delta", delta.text())));
        jdbc.update("INSERT INTO platform_session_render_batch(session_id,render_cursor,run_id,attempt_id,fence_token,"
                        + "stream_offset,from_offset,to_offset,message_id,block_id,safe_segments_json,safe_delta,"
                        + "content_sha256,committed_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                sessionId, cursor, delta.runId().value(), delta.attemptId(), delta.fenceToken(), delta.streamOffset(),
                delta.fromOffset(), delta.toOffset(), messageId, blockId, segments, delta.text(), digest,
                Timestamp.from(delta.occurredAt()), Timestamp.from(delta.occurredAt().plus(BATCH_TTL)));
        jdbc.update("UPDATE platform_session_message SET last_render_cursor=?,partial=1,phase='GENERATING' WHERE message_id=?",
                cursor, messageId);
        jdbc.update("UPDATE platform_session_render_attempt SET last_text_offset=?,phase='GENERATING',updated_at=? "
                        + "WHERE session_id=? AND run_id=? AND attempt_id=? AND fence_token=?",
                delta.toOffset(), Timestamp.from(delta.occurredAt()), sessionId, delta.runId().value(),
                delta.attemptId(), delta.fenceToken());
        jdbc.update("UPDATE platform_session_render_state SET next_render_cursor=?,committed_render_cursor=?,updated_at=? "
                        + "WHERE session_id=?",
                Math.addExact(cursor, 1L), cursor, Timestamp.from(delta.occurredAt()), sessionId);
        return cursor;
    }

    @Override
    @Transactional
    public void markDegraded(SessionId sid, RunId runId, String attemptId, Long fenceToken,
                             String reasonCode, Instant occurredAt) {
        if (fenceToken == null || reasonCode == null || reasonCode.isBlank()) return;
        String sessionId = jdbc.query("SELECT session_id FROM platform_agent_run WHERE run_id=? AND session_id=?",
                (rs, row) -> rs.getString(1), runId.value(), sid.value()).stream().findFirst().orElse(null);
        if (sessionId == null) return;
        if (jdbc.query("SELECT session_id FROM platform_agent_session WHERE session_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), sessionId).isEmpty()) return;
        if (jdbc.query("SELECT run_id FROM platform_agent_run WHERE run_id=? AND session_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId.value(), sessionId).isEmpty()) return;
        List<AttemptRow> latest = jdbc.query("SELECT attempt_id,fence_token,state,lease_expires_at "
                        + "FROM platform_run_execution_attempt WHERE run_id=? ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE",
                (rs, row) -> new AttemptRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()), runId.value());
        if (latest.isEmpty() || !latest.get(0).attemptId().equals(attemptId)
                || latest.get(0).fenceToken() != fenceToken) return;
        ensureRenderState(sessionId, occurredAt);
        lockRenderState(sessionId);
        markDegraded(sessionId, reasonCode, occurredAt);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionRenderView view(SessionId sid, UserId owner, int limit, HistoryBoundary before) {
        int bounded = Math.max(1, Math.min(limit, 100));
        SessionCursor session = jdbc.query("SELECT next_event_cursor-1 FROM platform_agent_session "
                        + "WHERE session_id=? AND user_id=?",
                (rs, row) -> new SessionCursor(rs.getLong(1)), sid.value(), owner.value())
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Session was not found"));
        long snapshotCursor = before == null ? session.cursor() : before.snapshotCursor();
        RenderState render = readRenderState(sid.value());
        long renderCursor = before == null ? render.committedCursor() : before.renderCursor();
        long floor = jdbc.query("SELECT COALESCE(MIN(session_cursor),0) FROM platform_agent_session_event WHERE session_id=?",
                (rs, row) -> rs.getLong(1), sid.value()).stream().findFirst().orElse(0L);
        long renderFloor = currentRenderFloor(sid.value(), render.floor(), Instant.now());
        List<SessionRenderRun> runs = readRuns(sid, owner, snapshotCursor);
        Map<String, String> runStates = runs.stream().collect(java.util.stream.Collectors.toMap(
                SessionRenderRun::runId, SessionRenderRun::state));
        List<SessionRenderMessage> candidates = readMessages(sid, owner, snapshotCursor, renderCursor, bounded, before);
        boolean hasMore = candidates.size() > bounded;
        if (hasMore) candidates = new ArrayList<>(candidates.subList(0, bounded));
        Collections.reverse(candidates);
        candidates = candidates.stream().map(item -> withBlocks(item, runStates)).toList();
        List<SessionRenderView.ResultReference> refs = readResultRefs(sid, owner, snapshotCursor);
        return new SessionRenderView(sid.value(), runs, candidates, refs, snapshotCursor, floor,
                renderCursor, renderFloor, render.status(), null, hasMore);
    }

    @Override
    @Transactional
    public RenderBatchPage batches(SessionId sid, UserId owner, long afterSessionCursor,
                                   long afterRenderCursor, int limit) {
        Instant instantNow = Instant.now();
        Timestamp now = Timestamp.from(instantNow);
        if (jdbc.query("SELECT session_id FROM platform_agent_session WHERE session_id=? AND user_id=?",
                (rs, row) -> rs.getString(1), sid.value(), owner.value()).isEmpty())
            throw new IllegalArgumentException("Session was not found");
        RenderState state = jdbc.query("SELECT next_render_cursor,committed_render_cursor,render_cursor_floor,draft_recovery_status "
                        + "FROM platform_session_render_state WHERE session_id=? FOR UPDATE",
                (rs, row) -> new RenderState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                sid.value()).stream().findFirst().orElse(new RenderState(1, 0, 0, "AVAILABLE"));
        Long expiredMax = jdbc.query("SELECT MAX(render_cursor) FROM platform_session_render_batch "
                        + "WHERE session_id=? AND expires_at<=?",
                (rs, row) -> { long value = rs.getLong(1); return rs.wasNull() ? null : value; },
                sid.value(), now).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        long floor = expiredMax == null ? state.floor() : Math.max(state.floor(), expiredMax);
        boolean expired = floor > Math.max(0, afterRenderCursor);
        List<SessionRenderBatch> rows = expired ? List.of() : jdbc.query(
                "SELECT render_cursor,run_id,attempt_id,stream_offset,message_id,block_id,from_offset,to_offset,"
                        + "safe_delta,committed_at FROM platform_session_render_batch "
                        + "WHERE session_id=? AND render_cursor>? AND expires_at>? ORDER BY render_cursor LIMIT ?",
                (rs, row) -> new SessionRenderBatch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getString(9),
                        rs.getTimestamp(10).toInstant()), sid.value(), Math.max(0, afterRenderCursor),
                now, Math.max(1, Math.min(limit, 200)));
        return new RenderBatchPage(rows, floor, state.committedCursor(), expired);
    }

    private long currentRenderFloor(String sessionId, long storedFloor, Instant now) {
        Long expiredMax = jdbc.query("SELECT MAX(render_cursor) FROM platform_session_render_batch "
                        + "WHERE session_id=? AND expires_at<=?",
                (rs, row) -> { long value = rs.getLong(1); return rs.wasNull() ? null : value; },
                sessionId, Timestamp.from(now)).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return expiredMax == null ? storedFloor : Math.max(storedFloor, expiredMax);
    }

    private void pruneExpiredBatches(String sessionId, RenderState render, Instant now) {
        long floor = currentRenderFloor(sessionId, render.floor(), now);
        if (floor <= render.floor()) return;
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("DELETE FROM platform_session_render_batch WHERE session_id=? AND expires_at<=?",
                sessionId, timestamp);
        jdbc.update("UPDATE platform_session_render_state SET render_cursor_floor=?,updated_at=? WHERE session_id=?",
                floor, timestamp, sessionId);
    }

    private void ensureRenderState(String sessionId, Instant now) {
        if (jdbc.query("SELECT session_id FROM platform_session_render_state WHERE session_id=?",
                (rs, row) -> rs.getString(1), sessionId).isEmpty()) {
            jdbc.update("INSERT INTO platform_session_render_state(session_id,next_render_cursor,committed_render_cursor,"
                            + "render_cursor_floor,draft_recovery_status,updated_at) VALUES(?,1,0,0,'AVAILABLE',?)",
                    sessionId, Timestamp.from(now));
        }
    }

    private RenderState lockRenderState(String sessionId) {
        return jdbc.query("SELECT next_render_cursor,committed_render_cursor,render_cursor_floor,draft_recovery_status "
                        + "FROM platform_session_render_state WHERE session_id=? FOR UPDATE",
                (rs, row) -> new RenderState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                sessionId).get(0);
    }

    private RenderState readRenderState(String sessionId) {
        return jdbc.query("SELECT next_render_cursor,committed_render_cursor,render_cursor_floor,draft_recovery_status "
                        + "FROM platform_session_render_state WHERE session_id=?",
                (rs, row) -> new RenderState(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)),
                sessionId).stream().findFirst().orElse(new RenderState(1, 0, 0, "AVAILABLE"));
    }

    private long currentCursor(String sessionId) {
        return jdbc.query("SELECT committed_render_cursor FROM platform_session_render_state WHERE session_id=?",
                (rs, row) -> rs.getLong(1), sessionId).stream().findFirst().orElse(0L);
    }

    private void ensureAttemptState(SessionRenderDelta delta) {
        if (jdbc.query("SELECT attempt_id FROM platform_session_render_attempt WHERE session_id=? AND run_id=? AND attempt_id=?",
                (rs, row) -> rs.getString(1), delta.sessionId().value(), delta.runId().value(), delta.attemptId()).isEmpty()) {
            jdbc.update("INSERT INTO platform_session_render_attempt(session_id,run_id,attempt_id,fence_token,last_text_offset,phase,updated_at) "
                            + "VALUES(?,?,?,?,0,'GENERATING',?)",
                    delta.sessionId().value(), delta.runId().value(), delta.attemptId(), delta.fenceToken(),
                    Timestamp.from(delta.occurredAt()));
        }
    }

    private AttemptRenderState lockAttemptState(SessionRenderDelta delta) {
        return jdbc.query("SELECT last_text_offset,fence_token FROM platform_session_render_attempt "
                        + "WHERE session_id=? AND run_id=? AND attempt_id=? FOR UPDATE",
                (rs, row) -> new AttemptRenderState(rs.getLong(1), rs.getLong(2)),
                delta.sessionId().value(), delta.runId().value(), delta.attemptId()).stream().findFirst()
                .filter(row -> row.fenceToken() == delta.fenceToken())
                .orElseThrow(() -> new IllegalStateException("Render attempt fence mismatch"));
    }

    private void ensureMessage(SessionRenderDelta delta, String messageId, long renderCursor, String executorRoleId) {
        if (jdbc.query("SELECT message_id FROM platform_session_message WHERE message_id=?",
                (rs, row) -> rs.getString(1), messageId).isEmpty()) {
            Long ordinal = jdbc.queryForObject("SELECT COALESCE(MAX(message_ordinal),0)+1 "
                    + "FROM platform_session_message WHERE session_id=?", Long.class, delta.sessionId().value());
            jdbc.update("INSERT INTO platform_session_message(message_id,session_id,run_id,attempt_id,kind,"
                            + "executor_role_id,identity_quality,message_ordinal,phase,last_render_cursor,partial,created_at) "
                            + "VALUES(?,?,?,?,'ASSISTANT_DRAFT',?,?, ?,?,?,1,?)",
                    messageId, delta.sessionId().value(), delta.runId().value(), delta.attemptId(),
                    executorRoleId, delta.identityQuality(), ordinal, "GENERATING", renderCursor,
                    Timestamp.from(delta.occurredAt()));
        }
    }

    private String executorRoleId(String runId) {
        return jdbc.query("SELECT role_id FROM platform_agent_run_execution_target WHERE run_id=?",
                (rs, row) -> rs.getString(1), runId).stream().findFirst().orElse("coordinator");
    }

    private void ensureBlock(SessionRenderDelta delta, String messageId, String blockId) {
        if (jdbc.query("SELECT block_id FROM platform_session_message_block WHERE message_id=? AND block_id=?",
                (rs, row) -> rs.getString(1), messageId, blockId).isEmpty()) {
            Long ordinal = jdbc.queryForObject("SELECT COALESCE(MAX(block_ordinal),0)+1 "
                    + "FROM platform_session_message_block WHERE message_id=?", Long.class, messageId);
            jdbc.update("INSERT INTO platform_session_message_block(message_id,block_id,block_type,block_ordinal,"
                            + "safe_body,last_applied_offset,byte_size,body_sha256,created_at) VALUES(?,?,'TEXT',?,'',0,0,?,?)",
                    messageId, blockId, ordinal, CanonicalJson.sha256Hex(new byte[0]), Timestamp.from(delta.occurredAt()));
        }
    }

    private void markDegraded(String sessionId, String reason, Instant now) {
        jdbc.update("UPDATE platform_session_render_state SET draft_recovery_status='DEGRADED',"
                        + "degraded_reason_code=?,updated_at=? WHERE session_id=?",
                reason, Timestamp.from(now), sessionId);
    }

    private String bodyDigestAfterAppend(String messageId, String blockId, String delta) {
        String current = jdbc.query("SELECT safe_body FROM platform_session_message_block WHERE message_id=? AND block_id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), messageId, blockId).get(0);
        return CanonicalJson.sha256Hex((current + delta).getBytes(StandardCharsets.UTF_8));
    }

    private List<SessionRenderRun> readRuns(SessionId sid, UserId owner, long snapshotCursor) {
        return jdbc.query("SELECT r.run_id,r.state,r.definition_version_id,r.created_at,r.started_at,r.finished_at "
                        + "FROM platform_agent_run r WHERE r.session_id=? AND r.user_id=? AND EXISTS "
                        + "(SELECT 1 FROM platform_agent_run_event e WHERE e.run_id=r.run_id AND e.event_type='USER_INPUT' "
                        + "AND e.session_cursor<=?) ORDER BY r.created_at,r.run_id",
                (rs, row) -> new SessionRenderRun(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant(), instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6))),
                sid.value(), owner.value(), snapshotCursor);
    }

    private List<SessionRenderMessage> readMessages(SessionId sid, UserId owner, long snapshotCursor,
                                                     long renderCursor, int limit, HistoryBoundary before) {
        String sql = "SELECT * FROM ("
                + "SELECT r.created_at run_created_at,r.run_id,0 kind_order,e.sequence_no ordinal,"
                + "CONCAT(e.run_id,'-user-',e.sequence_no) item_id,'USER_INPUT' kind,e.event_type item_type,"
                + "e.content body,NULL result_id,0 partial,e.created_at occurred_at,e.attempt_id,NULL executor_role_id,e.session_cursor "
                + "FROM platform_agent_run_event e JOIN platform_agent_run r ON r.run_id=e.run_id "
                + "WHERE r.session_id=? AND r.user_id=? AND e.visibility='USER' AND e.event_type='USER_INPUT' AND e.session_cursor<=? "
                + "UNION ALL "
                + "SELECT r.created_at,r.run_id,3,e.sequence_no,CONCAT(e.run_id,'-assistant'),'ROOT_FINAL',e.event_type,"
                + "e.content,e.result_id,0,e.created_at,e.attempt_id,res.executor_role_id,e.session_cursor "
                + "FROM platform_agent_run_event e JOIN platform_agent_run r ON r.run_id=e.run_id "
                + "LEFT JOIN platform_agent_result res ON res.result_id=e.result_id AND res.run_id=e.run_id AND res.visibility='USER' "
                + "WHERE r.session_id=? AND r.user_id=? AND e.visibility='USER' AND e.event_type='RUN_COMPLETED' AND e.session_cursor<=? "
                + "UNION ALL "
                + "SELECT r.created_at,r.run_id,2,e.sequence_no,CONCAT(e.run_id,'-status-',e.sequence_no),"
                + "'PUBLIC_STATUS',e.event_type,e.content,NULL,0,e.created_at,e.attempt_id,NULL,e.session_cursor "
                + "FROM platform_agent_run_event e JOIN platform_agent_run r ON r.run_id=e.run_id "
                + "WHERE r.session_id=? AND r.user_id=? AND e.visibility='USER' "
                + "AND e.event_type NOT IN ('USER_INPUT','RUN_COMPLETED') AND e.session_cursor<=? "
                + "UNION ALL "
                + "SELECT r.created_at,r.run_id,1,m.message_ordinal,m.message_id,m.kind,m.phase,'',m.result_id,m.partial,"
                + "m.created_at,m.attempt_id,m.executor_role_id,m.last_render_cursor FROM platform_session_message m "
                + "JOIN platform_agent_run r ON r.run_id=m.run_id WHERE m.session_id=? AND r.user_id=? "
                + "AND m.kind='ASSISTANT_DRAFT' AND m.last_render_cursor<=? "
                + ") items";
        List<Object> args = new ArrayList<>(List.of(sid.value(), owner.value(), snapshotCursor,
                sid.value(), owner.value(), snapshotCursor, sid.value(), owner.value(), snapshotCursor,
                sid.value(), owner.value(), renderCursor));
        if (before != null) {
            sql += " WHERE (run_created_at<? OR (run_created_at=? AND run_id<?) OR "
                    + "(run_created_at=? AND run_id=? AND kind_order<?) OR "
                    + "(run_created_at=? AND run_id=? AND kind_order=? AND ordinal<?) OR "
                    + "(run_created_at=? AND run_id=? AND kind_order=? AND ordinal=? AND item_id<?))";
            Timestamp createdAt = Timestamp.from(before.runCreatedAt());
            args.add(createdAt); args.add(createdAt); args.add(before.runId());
            args.add(createdAt); args.add(before.runId()); args.add(before.kindOrder());
            args.add(createdAt); args.add(before.runId()); args.add(before.kindOrder()); args.add(before.ordinal());
            args.add(createdAt); args.add(before.runId()); args.add(before.kindOrder()); args.add(before.ordinal());
            args.add(before.messageId());
        }
        sql += " ORDER BY run_created_at DESC,run_id DESC,kind_order DESC,ordinal DESC,item_id DESC LIMIT ?";
        args.add(limit + 1);
        List<SessionRenderMessage> rows = jdbc.query(sql,
                (rs, row) -> {
                    String kind = rs.getString("kind");
                    String resultId = rs.getString("result_id");
                    String body = rs.getString("body");
                    String source = switch (kind) {
                        case "ROOT_FINAL" -> resultId == null ? "legacy-summary" : "canonical-result";
                        case "ASSISTANT_DRAFT" -> "draft";
                        case "USER_INPUT" -> "input";
                        default -> "status";
                    };
                    return new SessionRenderMessage(rs.getString("item_id"), rs.getString("run_id"),
                            rs.getString("attempt_id"), rs.getString("executor_role_id"), kind,
                            rs.getInt("kind_order"), rs.getLong("ordinal"), rs.getString("item_type"),
                            kind.equals("ROOT_FINAL") ? "COMPLETE" : kind.equals("USER_INPUT") ? "SUBMITTED" : null,
                            source, body, resultId, kind.equals("ROOT_FINAL") && resultId == null,
                            rs.getBoolean("partial"), rs.getTimestamp("run_created_at").toInstant(),
                            rs.getTimestamp("occurred_at").toInstant(), List.of());
                }, args.toArray());
        return rows;
    }

    private SessionRenderMessage withBlocks(SessionRenderMessage item, Map<String, String> runStates) {
        if (!"ASSISTANT_DRAFT".equals(item.kind())) return item;
        List<SessionRenderMessage.Block> blocks = jdbc.query("SELECT block_id,block_type,safe_body,last_applied_offset "
                        + "FROM platform_session_message_block WHERE message_id=? ORDER BY block_ordinal",
                (rs, row) -> new SessionRenderMessage.Block(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                item.messageId());
        String text = blocks.stream().map(SessionRenderMessage.Block::text).reduce("", String::concat);
        String state = runStates.get(item.runId());
        String phase = switch (state == null ? "" : state) {
            case "SUCCEEDED" -> "COMPLETE";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            case "RECOVERY_REQUIRED" -> "RECOVERY_REQUIRED";
            case "WAITING_TOOL" -> "WAITING_TOOL";
            case "WAITING_CONFIRMATION" -> "WAITING_CONFIRMATION";
            case "CANCELLING" -> "CANCELLING";
            default -> item.phase();
        };
        return new SessionRenderMessage(item.messageId(), item.runId(), item.attemptId(), item.executorRoleId(),
                item.kind(), item.kindOrder(),
                item.messageOrdinal(), item.itemType(), phase, item.bodySource(), text, item.resultId(),
                item.legacySummary(), item.partial(), item.runCreatedAt(), item.occurredAt(), blocks);
    }

    private List<SessionRenderView.ResultReference> readResultRefs(SessionId sid, UserId owner, long snapshotCursor) {
        return jdbc.query("SELECT res.result_id,ref.run_id,res.kind,res.media_type,res.body_sha256,res.byte_size,res.executor_role_id "
                        + "FROM platform_agent_run_result_reference ref JOIN platform_agent_run r ON r.run_id=ref.run_id "
                        + "JOIN platform_agent_result res ON res.result_id=ref.result_id "
                        + "WHERE r.session_id=? AND r.user_id=? AND EXISTS(SELECT 1 FROM platform_agent_run_event e "
                        + "WHERE e.run_id=r.run_id AND e.event_type='USER_INPUT' AND e.session_cursor<=?) "
                        + "ORDER BY r.created_at,r.run_id,ref.reference_order",
                (rs, row) -> new SessionRenderView.ResultReference(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7)),
                sid.value(), owner.value(), snapshotCursor);
    }

    private static String messageId(SessionRenderDelta delta) {
        String reply = delta.replyId() == null ? "fallback:" + delta.attemptId() : "reply:" + delta.replyId();
        return delta.runId().value() + "-draft-" + shortHash(delta.attemptId() + "\u0000" + reply);
    }

    private static String blockId(SessionRenderDelta delta) {
        String block = delta.blockId() == null ? "text" : delta.blockId();
        return "b-" + shortHash(block);
    }

    private static String shortHash(String value) {
        return CanonicalJson.sha256Hex(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    private record AttemptRow(String attemptId, long fenceToken, String state, Instant leaseExpiresAt) { }
    private record AttemptRenderState(long lastTextOffset, long fenceToken) { }
    private record Duplicate(long renderCursor, long toOffset, String digest) { }
    private record SessionCursor(long cursor) { }
    private record RenderState(long nextCursor, long committedCursor, long floor, String status) { }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
