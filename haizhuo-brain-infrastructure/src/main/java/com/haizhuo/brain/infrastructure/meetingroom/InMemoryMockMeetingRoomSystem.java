package com.haizhuo.brain.infrastructure.meetingroom;

import com.haizhuo.brain.platform.meetingroom.MeetingRoomSystem;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Single-process deterministic A-201 mock. Data is intentionally volatile. */
@Component
@Profile("test")
public class InMemoryMockMeetingRoomSystem implements MeetingRoomSystem {
    private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    private final Map<String, String> operationDigests = new ConcurrentHashMap<>();
    private final Map<String, Booking> operationBookings = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override public Availability inspect(OffsetDateTime startAt, OffsetDateTime endAt) {
        boolean free;
        synchronized (bookings) {
            free = bookings.values().stream().noneMatch(b -> overlaps(startAt, endAt, b.startAt(), b.endAt()));
        }
        return new Availability("A-201", 8, true, free, "当前空闲，设备正常（模拟监控）", OffsetDateTime.now());
    }

    @Override public Booking reserve(String operationKey, long userId, OffsetDateTime startAt, OffsetDateTime endAt, int attendees) {
        String digest = userId + "|A-201|" + startAt + "|" + endAt + "|" + attendees;
        synchronized (bookings) {
            String priorDigest = operationDigests.get(operationKey);
            if (priorDigest != null) {
                if (!priorDigest.equals(digest)) throw new IllegalStateException("idempotency key reused with different booking details");
                return operationBookings.get(operationKey);
            }
            if (attendees < 1 || attendees > 8) throw new IllegalArgumentException("A-201 capacity is 8");
            if (!endAt.isAfter(startAt)) throw new IllegalArgumentException("endAt must be after startAt");
            if (bookings.values().stream().anyMatch(b -> overlaps(startAt, endAt, b.startAt(), b.endAt())))
                throw new IllegalStateException("A-201 is already booked for this time range");
            String bookingId = "BK-%04d".formatted(sequence.incrementAndGet());
            Booking booking = new Booking(bookingId, "A-201", startAt, endAt, attendees, userId);
            bookings.put(bookingId, booking);
            operationDigests.put(operationKey, digest);
            operationBookings.put(operationKey, booking);
            return booking;
        }
    }

    @Override public Booking find(String bookingId) { return bookings.get(bookingId); }
    @Override public Booking findByOperationKey(String operationKey) { return operationBookings.get(operationKey); }

    private static boolean overlaps(OffsetDateTime start, OffsetDateTime end, OffsetDateTime otherStart, OffsetDateTime otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }
}
