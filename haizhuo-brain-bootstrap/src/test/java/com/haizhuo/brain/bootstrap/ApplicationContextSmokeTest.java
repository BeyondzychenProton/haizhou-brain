package com.haizhuo.brain.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.junit.jupiter.api.Assertions;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("meeting-mock")
class ApplicationContextSmokeTest {
    @LocalServerPort int port;

    @Test
    void contextLoads() { }

    @Test
    void meetingMockUsesFixedServerIdentityWithoutLogin() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        var response = client.post().uri("/api/v1/sessions").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.sessionId").exists().returnResult();
        Assertions.assertNotNull(response.getResponseBody());
    }

    @Test
    void httpRunReportsMissingModelKeyAndDoesNotPretendToBook() throws InterruptedException {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        java.util.concurrent.atomic.AtomicReference<String> sessionId = new java.util.concurrent.atomic.AtomicReference<>();
        client.post().uri("/api/v1/sessions").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.sessionId").value(value -> sessionId.set(String.valueOf(value)));
        java.util.concurrent.atomic.AtomicReference<String> runId = new java.util.concurrent.atomic.AtomicReference<>();
        client.post().uri("/api/v1/sessions/{sessionId}/runs", sessionId.get())
                .bodyValue(java.util.Map.of("clientRequestId", "http-smoke", "message", "预定明天下午会议室"))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.runId")
                .value(value -> runId.set(String.valueOf(value)));
        for (int attempt = 0; attempt < 50; attempt++) {
            java.util.concurrent.atomic.AtomicReference<String> state = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> outcome = new java.util.concurrent.atomic.AtomicReference<>();
            client.get().uri("/api/v1/runs/{runId}", runId.get()).exchange().expectStatus().isOk().expectBody()
                    .jsonPath("$.state").value(value -> state.set(String.valueOf(value)))
                    .jsonPath("$.businessOutcome").value(value -> outcome.set(String.valueOf(value)));
            if ("FAILED".equals(state.get())) {
                Assertions.assertEquals("UNKNOWN", outcome.get());
                return;
            }
            Thread.sleep(100);
        }
        Assertions.fail("Run did not finish within the test deadline");
    }
}
