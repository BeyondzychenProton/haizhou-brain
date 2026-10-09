package com.haizhuo.brain.infrastructure.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 为返回给 AgentScope 的委派预留记录提供带 fence 的事务生命周期写入。 */
@Repository
public  class JdbcDelegationReservationLifecycle {
    private final JdbcTemplate jdbc;
    private final JdbcRunEventAppender events;
    private final TransactionTemplate transactions;

    @Autowired
    public JdbcDelegationReservationLifecycle(JdbcTemplate jdbc, JdbcRunEventAppender events,
                                              PlatformTransactionManager transactionManager) {
        this(jdbc, events, new TransactionTemplate(transactionManager));
    }

    JdbcDelegationReservationLifecycle(JdbcTemplate jdbc, JdbcRunEventAppender events) {
        this(jdbc, events, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
    }

    private JdbcDelegationReservationLifecycle(JdbcTemplate jdbc, JdbcRunEventAppender events,
                                               TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.events = events;
        this.transactions = transactions;
    }

    public boolean activate(RunId runId, String attemptId, long fenceToken, String invocationId) {
        Boolean activated = transactions.execute(status -> {
            if (!lockCurrent(runId, attemptId, fenceToken, true)) return false;
            Instant now = Instant.now();
            int updated = jdbc.update("UPDATE platform_run_delegation_invocation SET state='ACTIVE',started_at=? "
                            + "WHERE invocation_id=? AND run_id=? AND attempt_id=? AND fence_token=? AND state='ACCEPTED'",
                    Timestamp.from(now), invocationId, runId.value(), attemptId, fenceToken);
            if (updated == 1) JdbcRunProgressNotifier.append(events, runId, attemptId, fenceToken,
                    "delegation-invocation:" + invocationId + ":activated", now);
            return updated == 1;
        });
        return Boolean.TRUE.equals(activated);
    }

    public void close(RunId runId, String attemptId, long fenceToken, String invocationId,
                      boolean activated, boolean acceptanceOwner) {
        String nextState = activated ? "FINISHED" : acceptanceOwner ? "NOT_STARTED" : null;
        String expectedState = activated ? "ACTIVE" : "ACCEPTED";
        if (nextState == null) return;
        transactions.executeWithoutResult(status -> {
            if (!lockCurrent(runId, attemptId, fenceToken, false)) return;
            Instant now = Instant.now();
            int updated = jdbc.update("UPDATE platform_run_delegation_invocation SET state=?,finished_at=? "
                            + "WHERE invocation_id=? AND run_id=? AND attempt_id=? AND fence_token=? AND state=?",
                    nextState, Timestamp.from(now), invocationId, runId.value(), attemptId, fenceToken, expectedState);
            if (updated == 1) JdbcRunProgressNotifier.append(events, runId, attemptId, fenceToken,
                    "delegation-invocation:" + invocationId + ":closed:" + nextState, now);
        });
    }

    /** 按事件追加器使用的 Session → Run → 最新 attempt 顺序加锁。 */
    private boolean lockCurrent(RunId runId, String attemptId, long fenceToken, boolean requireActiveLease) {
        if (events.lockRun(runId) == null) return false;
        Instant now = Instant.now();
        List<CurrentAttempt> rows = jdbc.query("SELECT run.runtime_profile,run.state,attempt.attempt_id,"
                        + "attempt.fence_token,attempt.state,attempt.lease_expires_at "
                        + "FROM platform_agent_run run JOIN platform_run_execution_attempt attempt "
                        + "ON attempt.run_id=run.run_id WHERE run.run_id=? ORDER BY attempt.attempt_no DESC "
                        + "LIMIT 1 FOR UPDATE",
                (rs, row) -> new CurrentAttempt(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), rs.getTimestamp(6).toInstant()), runId.value());
        if (rows.size() != 1) return false;
        CurrentAttempt current = rows.get(0);
        if (!"TEAM_READONLY".equals(current.runtimeProfile()) || !attemptId.equals(current.attemptId())
                || fenceToken != current.fenceToken()) return false;
        return !requireActiveLease || ("RUNNING".equals(current.runState()) && "RUNNING".equals(current.attemptState())
                && current.leaseExpiresAt().isAfter(now));
    }

    private record CurrentAttempt(String runtimeProfile, String runState, String attemptId, long fenceToken,
                                  String attemptState, Instant leaseExpiresAt) { }
}
