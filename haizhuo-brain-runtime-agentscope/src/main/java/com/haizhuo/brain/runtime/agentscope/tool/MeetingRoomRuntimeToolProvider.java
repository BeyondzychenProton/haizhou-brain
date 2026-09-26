package com.haizhuo.brain.runtime.agentscope.tool;

import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import com.haizhuo.brain.runtime.api.model.RuntimeCapability;
import io.agentscope.core.tool.AgentTool;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 首个闭环的白名单提供者；数据库只能选择这两项已由代码实现的会议室动作。 */
@Component
@Profile("meeting-mock | test")
public class MeetingRoomRuntimeToolProvider implements RuntimeToolProvider {
    private static final Set<String> CAPABILITIES = Set.of("meeting_room.search", "meeting_room.reserve");

    @Override public String implementationKey() { return "meeting-room-v1"; }
    @Override public boolean supports(String capabilityCode) { return CAPABILITIES.contains(capabilityCode); }

    @Override
    public AgentTool create(RuntimeCapability capability, AgentExecutionRequest run, ToolExecutionGateway gateway) {
        if (!supports(capability.referenceId()) || !"meeting-room-v1".equals(capability.implementationKey()))
            throw new IllegalArgumentException("Unsupported meeting-room capability provider mapping");
        return new DynamicCapabilityAgentTool(capability, run, gateway);
    }
}
