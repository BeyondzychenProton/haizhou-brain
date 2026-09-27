package com.haizhuo.brain.bootstrap.security;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.AuthenticationRejectedException;
import com.haizhuo.brain.security.identity.PlatformIdentityService;
import com.haizhuo.brain.security.identity.PlatformRole;
import com.haizhuo.brain.security.identity.PlatformSessionPrincipal;
import java.time.Clock;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

/** WebFlux security wiring: Redis holds only a session identifier and the minimal session principal. */
@Configuration
@EnableWebFluxSecurity
public class PlatformSecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    WebSessionIdResolver webSessionIdResolver(SecuritySessionProperties properties) {
        CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
        resolver.addCookieInitializer(cookie -> cookie.path("/").httpOnly(true)
                .secure(properties.secureCookie()).sameSite(properties.sameSite()));
        return resolver;
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                  ServerSecurityContextRepository securityContextRepository,
                                                  @Qualifier("platformIdentityRefreshFilter") WebFilter platformIdentityRefreshFilter) {
        CookieServerCsrfTokenRepository csrf = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        return http
                .securityContextRepository(securityContextRepository)
                .csrf(spec -> spec.csrfTokenRepository(csrf))
                .addFilterAfter(platformIdentityRefreshFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(spec -> spec
                        .pathMatchers("/actuator/health/**", "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/activate").permitAll()
                        .pathMatchers("/api/admin/**").hasRole(PlatformRole.PLATFORM_ADMIN.name())
                        .pathMatchers("/api/v1/employees").authenticated()
                        .pathMatchers("/api/v1/sessions/**").authenticated()
                        .pathMatchers("/api/v1/auth/password/change", "/api/v1/auth/logout", "/api/v1/auth/me").authenticated()
                        .anyExchange().denyAll())
                .exceptionHandling(spec -> spec
                        .authenticationEntryPoint((exchange, error) -> complete(exchange, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, error) -> complete(exchange, HttpStatus.FORBIDDEN)))
                .build();
    }

    /**
     * Refreshes account status, auth version and roles for every authenticated request. It is after
     * session authentication but before authorization, so an old Redis session cannot retain a role.
     */
    @Bean
    WebFilter platformIdentityRefreshFilter(PlatformIdentityService identityService) {
        return (exchange, chain) -> ReactiveSecurityContextHolder.getContext()
                .defaultIfEmpty(new SecurityContextImpl())
                .flatMap(context -> {
                    Authentication authentication = context.getAuthentication();
                    if (isPublicPath(exchange) || !(authentication != null && authentication.getPrincipal() instanceof PlatformSessionPrincipal session)) {
                        return chain.filter(exchange);
                    }
                    return Mono.fromCallable(() -> identityService.refresh(new UserId(session.userId()), session.authVersion()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(user -> {
                                if (user.mustChangePassword() && !isAllowedDuringForcedPasswordChange(exchange)) {
                                    return complete(exchange, HttpStatus.FORBIDDEN);
                                }
                                Authentication refreshed = new UsernamePasswordAuthenticationToken(user, null, authorities(user));
                                return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(refreshed));
                            })
                            .onErrorResume(AuthenticationRejectedException.class, error -> invalidateAndComplete(exchange, HttpStatus.UNAUTHORIZED))
                            .onErrorResume(error -> complete(exchange, HttpStatus.SERVICE_UNAVAILABLE));
                });
    }

    private static Set<SimpleGrantedAuthority> authorities(AuthenticatedUser user) {
        return user.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean isPublicPath(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.startsWith("/actuator/health") || path.equals("/api/v1/auth/csrf")
                || path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/activate");
    }

    private static boolean isAllowedDuringForcedPasswordChange(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        return path.equals("/api/v1/auth/csrf") || path.equals("/api/v1/auth/me")
                || path.equals("/api/v1/auth/password/change") || path.equals("/api/v1/auth/logout")
                || path.startsWith("/actuator/health");
    }

    private static Mono<Void> invalidateAndComplete(ServerWebExchange exchange, HttpStatus status) {
        return exchange.getSession().flatMap(session -> session.invalidate().onErrorResume(error -> Mono.empty()))
                .then(complete(exchange, status));
    }

    private static Mono<Void> complete(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
