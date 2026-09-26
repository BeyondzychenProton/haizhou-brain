package com.haizhuo.brain.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.junit.jupiter.api.Assertions;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
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
}
