package com.haizhuo.brain.infrastructure.capability;

import static org.junit.jupiter.api.Assertions.*;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.platform.employee.CapabilityCatalogEntry;
import com.haizhuo.brain.platform.tool.CapabilityExecutionContext;
import com.haizhuo.brain.platform.tool.PlatformToolExecution;
import com.haizhuo.brain.platform.tool.ResolvedCredential;
import com.haizhuo.brain.platform.tool.ToolExecutionResult;
import com.haizhuo.brain.platform.tool.ToolExecutionState;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 会议室种子能力执行器（implementationKey=meeting-room-v1，spec §43/§89）：可用性查询的
 * 安全文案、预订的 operation_key 幂等（重复去重 / 指纹冲突 IDEMPOTENCY_CONFLICT）、
 * 容量与时间冲突校验、参数校验与未知 businessAction 拒绝。
 */
class MeetingRoomCapabilityExecutorTest {

    private JdbcTemplate jdbc;
    private MeetingRoomCapabilityExecutor executor;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:meeting_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE mock_meeting_room(room_id VARCHAR(32) PRIMARY KEY,capacity INT NOT NULL,"
                + "projector_available BOOLEAN NOT NULL,monitor_summary VARCHAR(500) NOT NULL,"
                + "monitor_observed_at TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE mock_meeting_booking(booking_id VARCHAR(48) PRIMARY KEY,room_id VARCHAR(32) NOT NULL,"
                + "user_id BIGINT NOT NULL,start_at TIMESTAMP NOT NULL,end_at TIMESTAMP NOT NULL,attendees INT NOT NULL,"
                + "operation_key VARCHAR(128) NOT NULL UNIQUE,request_digest CHAR(64) NOT NULL,created_at TIMESTAMP NOT NULL)");
        jdbc.update("INSERT INTO mock_meeting_room VALUES('A-201',8,TRUE,'温度 23℃，设备正常',?)",
                Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 8, 0)));
        executor = new MeetingRoomCapabilityExecutor(jdbc);
    }

    @Test
    void supportsOnlyMeetingRoomV1() {
        assertTrue(executor.supports(capability("meeting_room.availability.read")));
        assertFalse(executor.supports(new CapabilityCatalogEntry(101L, "other.read",
                CapabilityBinding.CapabilityType.TOOL, "1", "其他", "其他能力", "other_read",
                "other-impl-v1", "other.read", Map.of("type", "object"), true, false)));
        assertEquals("meeting-room-v1", executor.implementationKey());
    }

    @Test
    void searchReportsAvailabilityWithSafeSummary() {
        ToolExecutionResult result = executor.execute(context("ik-search-1", "meeting_room.availability.read"),
                Map.of("startAt", "2026-02-01T14:00:00+08:00", "endAt", "2026-02-01T15:00:00+08:00", "attendees", 4));

        assertTrue(result.success());
        assertTrue(result.content().contains("A-201"), "文案必须面向用户可读");
        assertTrue(result.content().contains("空闲，可预定"));
        assertTrue(result.content().contains("容量 8 人"));
        assertTrue(result.content().contains("投影仪可用"));
        assertTrue(result.content().contains("温度 23℃"), "监控摘要原样带出");
        assertFalse(result.content().contains("SELECT"), "不得泄漏任何 SQL 细节（§89）");
    }

    @Test
    void searchReportsConflictAndCapacityReasons() {
        insertBooking("BK-EXISTING", "op-existing", "2026-02-01 14:00:00", "2026-02-01 15:00:00", 4);

        ToolExecutionResult conflict = executor.execute(context("ik-search-2", "meeting_room.availability.read"),
                Map.of("startAt", "2026-02-01T14:30:00+08:00", "endAt", "2026-02-01T15:30:00+08:00", "attendees", 4));
        assertTrue(conflict.success());
        assertTrue(conflict.content().contains("时间冲突"));

        ToolExecutionResult tooMany = executor.execute(context("ik-search-3", "meeting_room.availability.read"),
                Map.of("startAt", "2026-02-01T16:00:00+08:00", "endAt", "2026-02-01T17:00:00+08:00", "attendees", 20));
        assertTrue(tooMany.content().contains("人数超过容量"));

        ToolExecutionResult adjacent = executor.execute(context("ik-search-4", "meeting_room.availability.read"),
                Map.of("startAt", "2026-02-01T15:00:00+08:00", "endAt", "2026-02-01T16:00:00+08:00", "attendees", 4));
        assertTrue(adjacent.content().contains("空闲，可预定"), "首尾相接不算冲突");
    }

    @Test
    void reserveCreatesBookingAndDeduplicatesRepeatedRequests() {
        Map<String, Object> arguments = Map.of("startAt", "2026-02-01T14:00:00+08:00",
                "endAt", "2026-02-01T15:00:00+08:00", "attendees", 4);

        ToolExecutionResult first = executor.execute(context("ik-reserve-1", "meeting_room.booking.create"), arguments);
        assertTrue(first.success());
        assertNotNull(first.externalReceipt());
        assertTrue(first.externalReceipt().startsWith("BK-"), "外部回执即预订号");
        assertTrue(first.content().contains(first.externalReceipt()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_meeting_booking", Integer.class));
        assertEquals("ik-reserve-1", jdbc.queryForObject(
                "SELECT operation_key FROM mock_meeting_booking", String.class), "幂等键落库（§43.1）");

        ToolExecutionResult repeated = executor.execute(context("ik-reserve-1", "meeting_room.booking.create"), arguments);
        assertTrue(repeated.success(), "相同幂等键+相同内容必须去重成功而非报错");
        assertEquals(first.externalReceipt(), repeated.externalReceipt());
        assertTrue(repeated.content().contains("重复请求已去重"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_meeting_booking", Integer.class),
                "重复请求不得产生第二条预订");
    }

    @Test
    void reserveRejectsSameKeyWithDifferentContent() {
        executor.execute(context("ik-reserve-2", "meeting_room.booking.create"),
                Map.of("startAt", "2026-02-01T14:00:00+08:00", "endAt", "2026-02-01T15:00:00+08:00", "attendees", 4));

        ToolExecutionResult conflict = executor.execute(context("ik-reserve-2", "meeting_room.booking.create"),
                Map.of("startAt", "2026-02-01T16:00:00+08:00", "endAt", "2026-02-01T17:00:00+08:00", "attendees", 4));
        assertFalse(conflict.success());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.errorCode(), "相同幂等键对应不同内容必须显式冲突（§43.2）");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_meeting_booking", Integer.class));
    }

    @Test
    void reserveValidatesCapacityOverlapAndTimeOrder() {
        ToolExecutionResult tooMany = executor.execute(context("ik-reserve-3", "meeting_room.booking.create"),
                Map.of("startAt", "2026-02-01T14:00:00+08:00", "endAt", "2026-02-01T15:00:00+08:00", "attendees", 20));
        assertFalse(tooMany.success());
        assertEquals("CAPACITY_EXCEEDED", tooMany.errorCode());
        assertTrue(tooMany.content().contains("8 人"));

        insertBooking("BK-EXISTING", "op-existing", "2026-02-01 14:00:00", "2026-02-01 15:00:00", 4);
        ToolExecutionResult overlap = executor.execute(context("ik-reserve-4", "meeting_room.booking.create"),
                Map.of("startAt", "2026-02-01T14:30:00+08:00", "endAt", "2026-02-01T15:30:00+08:00", "attendees", 4));
        assertFalse(overlap.success());
        assertEquals("MEETING_ROOM_CONFLICT", overlap.errorCode());

        ToolExecutionResult backwards = executor.execute(context("ik-reserve-5", "meeting_room.booking.create"),
                Map.of("startAt", "2026-02-01T15:00:00+08:00", "endAt", "2026-02-01T14:00:00+08:00", "attendees", 4));
        assertFalse(backwards.success());
        assertEquals("INVALID_ARGUMENTS", backwards.errorCode(), "endAt 必须晚于 startAt");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mock_meeting_booking", Integer.class),
                "校验失败的请求不得落库");
    }

    @Test
    void rejectsUnknownActionAndMalformedArguments() {
        ToolExecutionResult unknown = executor.execute(context("ik-x-1", "meeting_room.booking.cancel"),
                Map.of("startAt", "2026-02-01T14:00:00+08:00", "endAt", "2026-02-01T15:00:00+08:00", "attendees", 4));
        assertFalse(unknown.success());
        assertEquals("UNSUPPORTED_BUSINESS_ACTION", unknown.errorCode());

        CapabilityExecutionContext search = context("ik-x-2", "meeting_room.availability.read");
        assertEquals("INVALID_ARGUMENTS", executor.execute(search,
                Map.of("endAt", "2026-02-01T15:00:00+08:00", "attendees", 4)).errorCode(), "缺 startAt");
        assertEquals("INVALID_ARGUMENTS", executor.execute(search,
                Map.of("startAt", "not-a-time", "endAt", "2026-02-01T15:00:00+08:00", "attendees", 4)).errorCode(),
                "非法时间格式");
        assertEquals("INVALID_ARGUMENTS", executor.execute(search,
                Map.of("startAt", "2026-02-01T14:00:00+08:00", "endAt", "2026-02-01T15:00:00+08:00",
                        "attendees", "abc")).errorCode(), "非法人数");
    }

    private CapabilityExecutionContext context(String idempotencyKey, String businessAction) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        PlatformToolExecution execution = new PlatformToolExecution("exec-" + idempotencyKey,
                new RunId("run-1"), "tu-1", 101L, "meeting_room_reserve", "{}", "i".repeat(64),
                ToolExecutionState.EXECUTING, idempotencyKey, null, null, null, null, null, now, now);
        return new CapabilityExecutionContext(new RunId("run-1"), new UserId(42), execution,
                capability(businessAction), ResolvedCredential.none());
    }

    private static CapabilityCatalogEntry capability(String businessAction) {
        return new CapabilityCatalogEntry(101L, "meeting-room.reserve", CapabilityBinding.CapabilityType.TOOL,
                "1", "会议室", "会议室能力", "meeting_room_reserve", "meeting-room-v1", businessAction,
                Map.of("type", "object"), true, true);
    }

    private void insertBooking(String bookingId, String operationKey, String startAt, String endAt, int attendees) {
        jdbc.update("INSERT INTO mock_meeting_booking(booking_id,room_id,user_id,start_at,end_at,attendees,"
                        + "operation_key,request_digest,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                bookingId, "A-201", 42L, Timestamp.valueOf(startAt), Timestamp.valueOf(endAt), attendees,
                operationKey, "d".repeat(64), Timestamp.valueOf(startAt));
    }
}
