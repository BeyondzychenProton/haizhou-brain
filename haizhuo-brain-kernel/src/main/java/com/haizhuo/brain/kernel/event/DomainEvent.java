package com.haizhuo.brain.kernel.event;

import java.time.Instant;

public interface DomainEvent {
    Instant occurredAt();
}
