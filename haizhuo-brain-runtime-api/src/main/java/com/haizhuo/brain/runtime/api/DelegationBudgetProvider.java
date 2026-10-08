package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.kernel.identity.RunId;

/** Durable reservation boundary for child-agent calls; the platform owns Run-scoped limits. */
@FunctionalInterface
public interface DelegationBudgetProvider {
    Reservation reserve(RunId runId, String attemptId, long fenceToken, String roleId,
                        int maxInvocations, int maxParallel);

    @FunctionalInterface
    interface Reservation extends AutoCloseable {
        String invocationId();

        /** Transition a preaccepted call into native execution; old reservations fail closed. */
        default boolean activate() { return true; }

        @Override
        default void close() {
        }
    }
}
