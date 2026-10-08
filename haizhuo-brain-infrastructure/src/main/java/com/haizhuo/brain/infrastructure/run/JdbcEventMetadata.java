package com.haizhuo.brain.infrastructure.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.run.DurableEventMetadata;
import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** 事实与投影使用完全相同的序列化格式。 */
public final class JdbcEventMetadata {
    private static final ObjectMapper JSON = new ObjectMapper();
    private JdbcEventMetadata() { }
    public static String json(Object value) {
        if (value == null) return null;
        try { return JSON.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid durable event metadata", error); }
    }
    public static DurableEventMetadata read(ResultSet rs) throws SQLException {
        try {
            String refs = rs.getString("native_refs_json");
            String payload = rs.getString("payload_json");
            return new DurableEventMetadata(rs.getInt("schema_version"), rs.getString("event_id"),
                    rs.getString("attempt_id"), rs.getObject("fence_token", Long.class),
                    rs.getString("origin_kind"), refs == null ? null : JSON.readValue(refs, AgentEventDescriptor.class),
                    payload == null ? Map.of() : JSON.readValue(payload, new TypeReference<Map<String, Object>>() { }),
                    rs.getString("result_id"), rs.getObject("session_cursor", Long.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new SQLException("Unreadable durable event metadata", error);
        }
    }
}
