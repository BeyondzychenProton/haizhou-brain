package com.haizhuo.brain.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationContextSmokeTest {
    @LocalServerPort int port;

    @Test
    void retiredUnauthenticatedApisAreUnavailable() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.post().uri("/api/v1/sessions").exchange().expectStatus().isNotFound();
        client.get().uri("/api/admin/v1/capabilities").exchange().expectStatus().isNotFound();
        client.get().uri("/mock/api/v1/rooms/A-201/availability").exchange().expectStatus().isNotFound();
    }
}
