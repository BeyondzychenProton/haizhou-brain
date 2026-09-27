package com.haizhuo.brain.platform.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunStateTest {
    @Test
    void only_non_terminal_states_hold_the_session_slot() {
        assertTrue(RunState.QUEUED.active());
        assertTrue(RunState.RUNNING.active());
        assertTrue(RunState.WAITING_TOOL.active());
        assertTrue(RunState.WAITING_CONFIRMATION.active());
        assertTrue(RunState.CANCELLING.active());
        assertFalse(RunState.SUCCEEDED.active());
        assertFalse(RunState.FAILED.active());
        assertFalse(RunState.CANCELLED.active());
        assertFalse(RunState.EXPIRED.active());
    }
}
