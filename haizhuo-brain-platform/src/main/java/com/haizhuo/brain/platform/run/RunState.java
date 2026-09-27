package com.haizhuo.brain.platform.run;

/** Active states retain a Session execution slot; terminal states release it. */
public enum RunState {
    QUEUED, RUNNING, WAITING_CONFIRMATION, CANCELLING, SUCCEEDED, FAILED, CANCELLED, EXPIRED;

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == WAITING_CONFIRMATION || this == CANCELLING;
    }
}
