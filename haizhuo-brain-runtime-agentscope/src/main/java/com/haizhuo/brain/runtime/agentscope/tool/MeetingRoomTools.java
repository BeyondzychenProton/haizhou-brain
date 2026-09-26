package com.haizhuo.brain.runtime.agentscope.tool;

import com.haizhuo.brain.runtime.api.ToolExecutionGateway;
import com.haizhuo.brain.runtime.api.model.AgentExecutionRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;

/** Only the meeting-room capabilities granted to this Run are registered with AgentScope. */
public class MeetingRoomTools {
    private final ToolExecutionGateway gateway;
    private final AgentExecutionRequest run;
    public MeetingRoomTools(ToolExecutionGateway gateway, AgentExecutionRequest run) { this.gateway = gateway; this.run = run; }

    @Tool(name = "meeting_room_search", description = "查询 A-201 在指定完整时段的资料、空闲情况和监控摘要")
    public String search(@ToolParam(name = "startAt", description = "开始时间，ISO-8601 日期时间") String startAt,
                         @ToolParam(name = "endAt", description = "结束时间，ISO-8601 日期时间") String endAt,
                         @ToolParam(name = "attendees", description = "参会人数") int attendees,
                         RuntimeContext context) {
        return gateway.execute(run, "meeting_room.search", Map.of("startAt", startAt, "endAt", endAt, "attendees", attendees));
    }

    @Tool(name = "meeting_room_reserve", description = "预定 A-201。必须先查询并确认可用")
    public String reserve(@ToolParam(name = "startAt", description = "开始时间，ISO-8601 日期时间") String startAt,
                          @ToolParam(name = "endAt", description = "结束时间，ISO-8601 日期时间") String endAt,
                          @ToolParam(name = "attendees", description = "参会人数") int attendees,
                          RuntimeContext context) {
        return gateway.execute(run, "meeting_room.reserve", Map.of("startAt", startAt, "endAt", endAt, "attendees", attendees));
    }
}
