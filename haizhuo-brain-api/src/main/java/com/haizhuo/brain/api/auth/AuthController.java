package com.haizhuo.brain.api.auth;

import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformIdentityService;
import com.haizhuo.brain.security.identity.PlatformSessionPrincipal;
import com.haizhuo.brain.security.identity.PlatformUserManagementService;
import com.haizhuo.brain.security.identity.AuthenticationRejectedException;
import com.haizhuo.brain.security.identity.ratelimit.AuthenticationRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Authentication endpoints do not accept a current user ID from JSON or headers. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final PlatformIdentityService identityService;
    private final PlatformUserManagementService userManagementService;
    private final AuthenticationRateLimiter rateLimiter;
    private final ServerSecurityContextRepository securityContextRepository;

    public AuthController(PlatformIdentityService identityService, PlatformUserManagementService userManagementService,
                          AuthenticationRateLimiter rateLimiter, ServerSecurityContextRepository securityContextRepository) {
        this.identityService = identityService;
        this.userManagementService = userManagementService;
        this.rateLimiter = rateLimiter;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/csrf")
    public Mono<CsrfTokenResponse> csrf(ServerWebExchange exchange) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        if (token == null) return Mono.error(new IllegalStateException("CSRF 未正确配置"));
        return token.map(value -> new CsrfTokenResponse(value.getHeaderName(), value.getParameterName(), value.getToken()));
    }

    @PostMapping("/login")
    public Mono<LoginResponse> login(@RequestBody LoginRequest request, ServerWebExchange exchange) {
        String source = requestSource(exchange);
        return blocking(() -> authenticateWithRateLimit(request.mobile(), request.password(), source))
                .flatMap(user -> persistSession(exchange, user).thenReturn(loginResponse(user)));
    }

    @PostMapping("/activate")
    public Mono<Void> activate(@Valid @RequestBody ActivateRequest request, ServerWebExchange exchange) {
        String source = requestSource(exchange);
        return blocking(() -> {
            activateWithRateLimit(request.activationToken(), request.newPassword(), source);
            return true;
        }).then();
    }

    @PostMapping("/password/change")
    public Mono<LoginResponse> changePassword(@AuthenticationPrincipal AuthenticatedUser user,
                                              @Valid @RequestBody ChangePasswordRequest request,
                                              ServerWebExchange exchange) {
        return blocking(() -> identityService.changePassword(user.userId(), request.currentPassword(), request.newPassword()))
                .flatMap(updated -> persistSession(exchange, updated).thenReturn(loginResponse(updated)));
    }

    @PostMapping("/logout")
    public Mono<Void> logout(ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> session.invalidate().then());
    }

    @GetMapping("/me")
    public Mono<CurrentUserResponse> me(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return blocking(() -> identityService.currentUser(authenticatedUser.userId()))
                .map(user -> new CurrentUserResponse(user.id().value(), maskMobile(user.mobileNormalized()),
                        authenticatedUser.roles().stream().map(Enum::name).sorted().toList(), user.mustChangePassword()));
    }

    private Mono<Void> persistSession(ServerWebExchange exchange, AuthenticatedUser user) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new PlatformSessionPrincipal(user.userId().value(), user.authVersion()), null, List.of());
        return exchange.getSession().flatMap(session -> session.changeSessionId()
                .then(securityContextRepository.save(exchange, new SecurityContextImpl(authentication))));
    }

    private AuthenticatedUser authenticateWithRateLimit(String mobile, String password, String source) {
        rateLimiter.checkLoginAllowed(mobile, source);
        try {
            AuthenticatedUser user = identityService.authenticate(mobile, password, source);
            rateLimiter.loginSucceeded(mobile, source);
            return user;
        } catch (AuthenticationRejectedException error) {
            rateLimiter.loginFailed(mobile, source);
            throw error;
        }
    }

    private void activateWithRateLimit(String credential, String newPassword, String source) {
        rateLimiter.checkActivationAllowed(credential, source);
        try {
            userManagementService.activate(credential, newPassword, source);
            rateLimiter.activationSucceeded(credential, source);
        } catch (AuthenticationRejectedException error) {
            rateLimiter.activationFailed(credential, source);
            throw error;
        }
    }

    private static String requestSource(ServerWebExchange exchange) {
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) return "unknown";
        return remoteAddress.getAddress().getHostAddress();
    }

    private static LoginResponse loginResponse(AuthenticatedUser user) {
        return new LoginResponse(user.userId().value(), user.roles().stream().map(Enum::name).sorted().toList(), user.mustChangePassword());
    }

    private static String maskMobile(String mobile) {
        if (mobile.length() < 7) return "***";
        return mobile.substring(0, 3) + "****" + mobile.substring(mobile.length() - 4);
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CsrfTokenResponse(String headerName, String parameterName, String token) {
    }

    public record LoginRequest(String mobile, String password) {
    }

    public record ActivateRequest(@NotBlank @Size(max = 512) String activationToken,
                                  @NotBlank @Size(max = 128) String newPassword) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
                                        @NotBlank @Size(max = 128) String newPassword) {
    }

    public record LoginResponse(long userId, List<String> roles, boolean mustChangePassword) {
    }

    public record CurrentUserResponse(long userId, String mobileMasked, List<String> roles, boolean mustChangePassword) {
    }
}
