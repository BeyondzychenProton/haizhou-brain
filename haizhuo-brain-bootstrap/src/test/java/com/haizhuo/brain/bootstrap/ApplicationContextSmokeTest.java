package com.haizhuo.brain.bootstrap;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.EmployeeCatalog;
import com.haizhuo.brain.security.identity.ratelimit.AuthenticationRateLimiter;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ApplicationContextSmokeTest.TestRateLimitConfiguration.class)
class ApplicationContextSmokeTest {
    @LocalServerPort int port;

    @Test
    void anonymousClientsCannotAccessBusinessOrAdministratorApis() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.post().uri("/api/v1/sessions").exchange().expectStatus().is4xxClientError();
        client.get().uri("/api/admin/v1/capabilities").exchange().expectStatus().isUnauthorized();
        client.get().uri("/mock/api/v1/rooms/A-201/availability").exchange().expectStatus().is4xxClientError();
    }

    @Test
    void anonymousClientCanObtainCsrfTokenBeforeAuthentication() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.headerName").isEqualTo("X-XSRF-TOKEN")
                .jsonPath("$.token").isNotEmpty();
    }

    @TestConfiguration
    static class TestRateLimitConfiguration {
        @Bean
        EmployeeCatalog employeeCatalog() {
            // 测试环境不加载 JDBC 员工仓储；匿名访问校验无需真实员工数据。
            return (tenantId, employeeId) -> Optional.empty();
        }

        @Bean
        AuthenticationRateLimiter authenticationRateLimiter() {
            return new AuthenticationRateLimiter() {
                @Override public void checkLoginAllowed(String mobile, String source) { }
                @Override public void loginFailed(String mobile, String source) { }
                @Override public void loginSucceeded(String mobile, String source) { }
                @Override public void checkActivationAllowed(String activationCredential, String source) { }
                @Override public void activationFailed(String activationCredential, String source) { }
                @Override public void activationSucceeded(String activationCredential, String source) { }
            };
        }

        @Bean
        AgentDefinitionManagementService agentDefinitionManagementService() {
            return org.mockito.Mockito.mock(AgentDefinitionManagementService.class);
        }
    }
}
