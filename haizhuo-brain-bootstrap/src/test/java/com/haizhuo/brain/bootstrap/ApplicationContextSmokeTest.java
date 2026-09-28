package com.haizhuo.brain.bootstrap;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.AgentDefinitionRepository;
import com.haizhuo.brain.security.identity.ratelimit.AuthenticationRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    /**
     * CSRF 豁免必须只落在渠道回调上，且不能破坏"安全方法不参与校验"这条默认语义。
     * 这三条断言各自对应一种回归：豁免失效、豁免过度、以及授权被 CSRF 抢先。
     */
    @Test
    void csrfExemptionIsLimitedToChannelCallbacksAndLeavesSafeMethodsAlone() {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

        // 安全方法不参与 CSRF 校验：匿名也必须能取到 token。
        // 一旦匹配器被整体替换（丢掉默认语义），这里会变成 403。
        client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isOk();
        // 受保护资源对匿名请求仍应是 401，而不是被 CSRF 过滤抢先判成 403。
        client.get().uri("/api/v1/employees").exchange().expectStatus().isUnauthorized();
        // 渠道回调豁免 CSRF，无 token 的 POST 也能抵达控制器；未配置验签密钥时控制器返回 503。
        // 若豁免失效，请求会在 CSRF 过滤处被挡下并返回 403。
        client.post().uri("/api/v1/channels/simulated/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @TestConfiguration
    static class TestRateLimitConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        AgentDefinitionRepository agentDefinitionRepository() {
            // JdbcAgentDefinitionRepository 标注 @Profile("!test")。AgentDefinitionRepository
            // 继承 EmployeeCatalog，一个 mock 同时满足两类注入点；Mockito 默认对
            // Optional 返回类型给空值，匿名访问校验无需真实员工数据。
            return org.mockito.Mockito.mock(AgentDefinitionRepository.class);
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
