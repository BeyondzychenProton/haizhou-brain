package com.haizhuo.brain.platform.channel;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import java.util.Optional;

/** runId 为空表示已受理，正排在当前活动 Run 之后等待。 */
public record ChannelAcceptance(SessionId sessionId, Optional<RunId> runId, boolean duplicate) {
}
