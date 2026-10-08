package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.platform.session.AgentSession;
import java.util.List;

/** 内容和恢复游标来自同一一致性读；专家私有事件不进入快照。 */
public record SessionSnapshot(AgentSession session, List<AgentRun> runs, List<SessionEvent> events,
                              long snapshotCursor, long cursorFloor) {
    public SessionSnapshot { runs = List.copyOf(runs); events = List.copyOf(events); }
}
