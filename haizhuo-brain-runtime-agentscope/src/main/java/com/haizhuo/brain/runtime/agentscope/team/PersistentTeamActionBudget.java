package com.haizhuo.brain.runtime.agentscope.team;

import com.haizhuo.brain.runtime.api.team.TeamExecutionPersistence;
import com.haizhuo.brain.runtime.api.team.TeamExecutionSnapshot;
import java.util.Objects;

/** Run-durable limits reserved before AgentScope's native Team board or mailbox is mutated. */
public final class PersistentTeamActionBudget implements TeamActionBudget {
    public static final int MAX_TASKS = 6;
    public static final int MAX_MESSAGE_RECIPIENTS = 20;

    private final TeamExecutionPersistence persistence;
    private final TeamExecutionSnapshot execution;

    public PersistentTeamActionBudget(TeamExecutionPersistence persistence, TeamExecutionSnapshot execution) {
        this.persistence = Objects.requireNonNull(persistence);
        this.execution = Objects.requireNonNull(execution);
    }

    @Override
    public void reserveTask() {
        persistence.reserveAction(execution, TeamExecutionPersistence.Action.TASK_CREATED, 1, MAX_TASKS);
    }

    @Override
    public void reserveMessageRecipients(int recipients) {
        if (recipients < 1) throw new IllegalArgumentException("at least one Team message recipient is required");
        persistence.reserveAction(execution, TeamExecutionPersistence.Action.MESSAGE_RECIPIENT,
                recipients, MAX_MESSAGE_RECIPIENTS);
    }
}
