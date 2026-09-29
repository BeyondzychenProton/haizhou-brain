package com.haizhuo.brain.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Langfuse Score API 的有界异步客户端。
 *
 * <p>Trace 走 OTLP exporter；评分是 Langfuse 的独立 Public API，因此不把评分
 * 伪装成 legacy ingestion 事件。网络故障、队列满或 Langfuse 暂时不可用都不能
 * 影响 Run、工具执行或浏览器实时流。</p>
 */
@Component
public class LangfuseScoreClient implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(LangfuseScoreClient.class);

    private final LangfuseProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final BlockingQueue<ScoreRequest> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;

    public LangfuseScoreClient(LangfuseProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.queue = new ArrayBlockingQueue<>(properties.scoreQueueCapacity());
        if (properties.configured()) {
            this.worker = new Thread(this::drain, "langfuse-score-exporter");
            this.worker.setDaemon(true);
            this.worker.start();
        } else {
            this.worker = null;
        }
    }

    public boolean configured() {
        return properties.configured();
    }

    public long droppedCount() {
        return dropped.get();
    }

    /** 入队成功只代表本地接受，不代表 Langfuse 已持久化。 */
    public boolean submit(String traceId, String observationId, String name, Object value,
                          String dataType, String comment) {
        if (!configured() || closed.get()) return false;
        ScoreRequest request = new ScoreRequest(traceId, observationId, name, value, dataType, comment);
        if (queue.offer(request)) return true;
        long count = dropped.incrementAndGet();
        if (count == 1 || count % 100 == 0) {
            log.warn("langfuse score queue full; droppedCount={}", count);
        }
        return false;
    }

    private void drain() {
        while (!closed.get()) {
            try {
                ScoreRequest request = queue.poll(properties.scoreFlushIntervalMillis(), TimeUnit.MILLISECONDS);
                if (request != null) send(request);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                log.warn("langfuse score worker failed; errorType={}",
                        error.getClass().getSimpleName());
            }
        }
    }

    private void send(ScoreRequest score) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", java.util.UUID.randomUUID().toString());
            body.put("traceId", score.traceId());
            if (score.observationId() != null && !score.observationId().isBlank()) {
                body.put("observationId", score.observationId());
            }
            body.put("name", score.name());
            body.put("value", score.value());
            body.put("dataType", score.dataType());
            if (score.comment() != null && !score.comment().isBlank()) {
                body.put("comment", score.comment());
            }
            String json = objectMapper.writeValueAsString(body);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.scoreEndpoint()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            String authorization = properties.basicAuthorization();
            if (authorization != null) request.header("Authorization", authorization);
            HttpResponse<Void> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("langfuse score rejected; status={} scoreName={}",
                        response.statusCode(), score.name());
            }
        } catch (Exception error) {
            log.warn("langfuse score unavailable; errorType={} scoreName={}",
                    error.getClass().getSimpleName(), score.name());
        }
    }

    @Override
    public void destroy() {
        if (closed.compareAndSet(false, true) && worker != null) worker.interrupt();
    }

    private record ScoreRequest(String traceId, String observationId, String name, Object value,
                                String dataType, String comment) {
        private ScoreRequest {
            Objects.requireNonNull(traceId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            Objects.requireNonNull(dataType);
        }
    }
}
