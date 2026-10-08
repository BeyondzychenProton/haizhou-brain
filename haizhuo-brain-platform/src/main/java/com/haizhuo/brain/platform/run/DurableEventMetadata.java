package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import java.util.Map;

/** 平台事实的安全来源；不保存任意框架 metadata、思考链或工具原始参数。 */
public record DurableEventMetadata(int schemaVersion, String eventId, String attemptId, Long fenceToken,
                                   String originKind, AgentEventDescriptor nativeRefs,
                                   Map<String, Object> payload, String resultId, Long sessionCursor) {
    public DurableEventMetadata {
        if (schemaVersion < 1 || schemaVersion > 2) throw new IllegalArgumentException("Unsupported event schema");
        originKind = originKind == null ? "PLATFORM" : originKind;
        if (!java.util.Set.of("PLATFORM", "ROOT", "CHILD", "UNKNOWN").contains(originKind))
            throw new IllegalArgumentException("Invalid event origin");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
    public static DurableEventMetadata legacy() {
        return new DurableEventMetadata(1, null, null, null, "PLATFORM", null, Map.of(), null, null);
    }
    public static DurableEventMetadata platform() {
        return new DurableEventMetadata(2, null, null, null, "PLATFORM", null, Map.of(), null, null);
    }
    public static DurableEventMetadata execution(AgentEventDescriptor descriptor) {
        if (descriptor == null) return platform();
        return new DurableEventMetadata(2, null, descriptor.attemptId(), descriptor.fenceToken(),
                descriptor.executionRole().name(), descriptor, Map.of(), null, null);
    }
    public DurableEventMetadata withResult(String id) {
        return new DurableEventMetadata(schemaVersion, eventId, attemptId, fenceToken, originKind,
                nativeRefs, payload, id, sessionCursor);
    }
}
