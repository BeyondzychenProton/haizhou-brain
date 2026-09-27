package com.haizhuo.brain.bootstrap.web;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Logs one safe, correlated line for each HTTP request. Query strings, request bodies, cookies,
 * authorization headers and response bodies are deliberately excluded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = requestId(exchange);
        long startedAt = System.nanoTime();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange).doFinally(signalType -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("http.request requestId={} method={} path={} status={} durationMs={} outcome={}",
                    requestId,
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    status == null ? 500 : status.value(),
                    durationMs,
                    signalType.name());
        });
    }

    private static String requestId(ServerWebExchange exchange) {
        String supplied = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        return supplied != null && supplied.matches("[A-Za-z0-9._-]{8,64}") ? supplied : UUID.randomUUID().toString();
    }
}
