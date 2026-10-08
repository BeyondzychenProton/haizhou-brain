package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import java.util.List;
import java.util.Objects;

/** 根最终结算；渠道路由只能由仓储读取本 Run 的冻结入站记录。 */
public record RunCompletion(String body, String mediaType, List<String> consumedToolIds,
                            AgentEventDescriptor descriptor) {
    public RunCompletion {
        Objects.requireNonNull(body);
        if (!java.util.Set.of("text/plain", "text/markdown").contains(mediaType))
            throw new IllegalArgumentException("Unsupported result media type");
        consumedToolIds = List.copyOf(consumedToolIds);
    }
    public enum Status { COMMITTED, STALE, REJECTED }
}
