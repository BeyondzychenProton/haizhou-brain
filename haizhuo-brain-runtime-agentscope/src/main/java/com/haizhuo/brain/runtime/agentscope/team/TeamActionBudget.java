package com.haizhuo.brain.runtime.agentscope.team;

/** Run-owned budget port. Implementations reserve against the persisted parent Run budget. */
public interface TeamActionBudget {

    void reserveTask();

    /** Reserves the number of concrete recipients, not the number of broadcast tool calls. */
    void reserveMessageRecipients(int recipients);
}
