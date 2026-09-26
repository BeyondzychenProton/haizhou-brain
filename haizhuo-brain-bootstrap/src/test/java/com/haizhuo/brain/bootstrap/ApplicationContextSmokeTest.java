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

    @Test
    void managementApiSavesValidatesAndPublishesDynamicDefinition() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.get().uri("/api/admin/v1/capabilities").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].capabilityCode").exists();

        client.put().uri("/api/admin/v1/agents/1/draft")
                .header("Content-Type", "application/json")
                .bodyValue("""
                        {"expectedDraftRevision":1,"instructions":"仅查询会议室，不执行预定。","modelProvider":"openai","modelName":"qwen3.7-max","capabilities":[{"capabilityCode":"meeting_room.search","revision":"1"}]}
                        """)
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.draftRevision").isEqualTo(2);

        client.post().uri("/api/admin/v1/agents/1/validate").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.publishable").isEqualTo(true);

        client.post().uri("/api/admin/v1/agents/1/publish")
                .header("Content-Type", "application/json")
                .bodyValue("{\"expectedDraftRevision\":2,\"requestId\":\"integration-publish-1\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.definition.version").isEqualTo(2)
                .jsonPath("$.capabilities.length()").isEqualTo(1)
                .jsonPath("$.capabilities[0].referenceId").isEqualTo("meeting_room.search");

        client.put().uri("/api/admin/v1/users/1001/capability-grants/meeting_room.search")
                .header("Content-Type", "application/json").bodyValue("{\"enabled\":false}")
                .exchange().expectStatus().isNoContent();
    }
}
