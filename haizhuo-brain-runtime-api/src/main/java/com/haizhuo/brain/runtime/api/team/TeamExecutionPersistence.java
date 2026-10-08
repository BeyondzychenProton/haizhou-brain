package com.haizhuo.brain.runtime.api.team;

import java.time.Instant;

/** Platform-owned durable lifecycle and action budget for one autonomous Team generation. */
public interface TeamExecutionPersistence {

    void start(TeamExecutionSnapshot execution);

    /** Reserve before mutating the native Team board or mailbox; reservations are not refunded. */
    void reserveAction(TeamExecutionSnapshot execution, Action action, int count, int limit);

    /** Persist a minimal internal member lifecycle fact; raw reasoning and tool arguments are excluded. */
    boolean recordMemberEvent(TeamExecutionSnapshot execution, String roleId, MemberEvent event, long ordinal,
                              Instant occurredAt);

    /** Returns false when the parent Run attempt no longer owns the execution fence. */
    boolean complete(TeamExecutionSnapshot execution, String resultSha256);

    /** Records an uncertain Team outcome; the old native generation must then remain read-only. */
    boolean requireRecovery(TeamExecutionSnapshot execution, String safeReasonCode);

    enum Action { TASK_CREATED, MESSAGE_RECIPIENT }

    enum MemberEvent { STARTED, ENDED, STOPPED }
}
