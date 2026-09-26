package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import java.util.Optional;

/** Empty runId means accepted and waiting behind the active Run. */
public record ChannelAcceptance(SessionId sessionId, Optional<RunId> runId, boolean duplicate) {
}
