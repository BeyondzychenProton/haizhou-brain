package com.haizhuo.brain.runtime.agentscope.team;

import io.agentscope.harness.agent.bus.AsyncToolRecord;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Disables AgentScope's background message path for fixed read-only experts, which only permit
 * synchronous {@code agent_spawn}/{@code agent_send}. The native workspace-backed bus uses an
 * empty RuntimeContext and cannot safely access this application's Session-scoped filesystem.
 */
public final class SynchronousOnlyHarnessTransport implements MessageBus, AsyncToolRegistry {
    private static final String ERROR = "background AgentScope work is disabled for TEAM_READONLY";

    @Override public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return unsupported();
    }

    @Override public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.just(List.of());
    }

    @Override public Mono<Void> queueDelete(String key) {
        return unsupported();
    }

    @Override public Mono<Boolean> queuePeek(String key) {
        return Mono.just(false);
    }

    @Override public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return unsupported();
    }

    @Override public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.just(List.of());
    }

    @Override public Mono<Void> logTrim(String key) {
        return unsupported();
    }

    @Override public Mono<Void> publish(String key, Map<String, Object> payload) {
        return unsupported();
    }

    @Override public Flux<Map<String, Object>> subscribe(String key) {
        return Flux.empty();
    }

    @Override public Mono<Void> register(AsyncToolRecord record) {
        return unsupported();
    }

    @Override public Mono<Void> complete(String toolUseId, String result) {
        return unsupported();
    }

    @Override public Mono<Void> fail(String toolUseId, String error) {
        return unsupported();
    }

    @Override public Mono<List<AsyncToolRecord>> findStale(String sessionId, Duration age) {
        return Mono.just(List.of());
    }

    @Override public Mono<Void> markTimeout(String toolUseId) {
        return unsupported();
    }

    private static <T> Mono<T> unsupported() {
        return Mono.error(new UnsupportedOperationException(ERROR));
    }
}
