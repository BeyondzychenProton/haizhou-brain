package com.haizhuo.brain.platform.meetingroom;

import java.time.OffsetDateTime;

public interface MeetingRoomSystem {
    Availability inspect(OffsetDateTime startAt, OffsetDateTime endAt);
    Booking reserve(String operationKey, long userId, OffsetDateTime startAt, OffsetDateTime endAt, int attendees);
    Booking find(String bookingId);
    Booking findByOperationKey(String operationKey);

    record Availability(String roomId, int capacity, boolean projector, boolean available,
                        String monitorSummary, OffsetDateTime observedAt) {}
    record Booking(String bookingId, String roomId, OffsetDateTime startAt, OffsetDateTime endAt,
                   int attendees, long userId) {}
}
