package com.haizhuo.brain.kernel.event;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
