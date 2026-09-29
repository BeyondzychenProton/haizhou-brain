package com.haizhuo.brain.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LangfuseScoreClientTest {

    @Test
    void acceptsScoreLocallyAndPostsPublicApiPayload() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/scores", exchange -> handle(exchange, received, body, authorization));
        server.start();

        LangfuseProperties properties = new LangfuseProperties(
                true, "http://127.0.0.1:" + server.getAddress().getPort(), null,
                "pk-test", "sk-test", "test", "haizhuo-test", false,
                2000, 8, 10, "4");
        LangfuseScoreClient client = new LangfuseScoreClient(properties, new ObjectMapper());
        try {
            assertTrue(client.submit("trace-1", "observation-1", "quality", 0.75,
                    "NUMERIC", "manual"));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertTrue(body.get().contains("\"traceId\":\"trace-1\""));
            assertTrue(body.get().contains("\"name\":\"quality\""));
            assertEquals(properties.basicAuthorization(), authorization.get());
        } finally {
            client.destroy();
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, CountDownLatch received,
                               AtomicReference<String> body, AtomicReference<String> authorization)
            throws IOException {
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.sendResponseHeaders(201, -1);
        exchange.close();
        received.countDown();
    }
}
