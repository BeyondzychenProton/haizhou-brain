package com.haizhuo.brain.api.channel;

import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAccountChangedException;
import com.haizhuo.brain.platform.channel.ChannelAdministrationService;
import com.haizhuo.brain.platform.channel.ChannelManagementCommandExecutor;
import com.haizhuo.brain.platform.channel.ChannelIdentityBinding;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin;
import com.haizhuo.brain.platform.channel.SessionScope;
import com.haizhuo.brain.kernel.identity.TenantId;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 渠道绑定管理（P3 方向 4）：管理员维护渠道账号绑定与外部用户身份绑定。
 *
 * <p>鉴权由 {@code /api/admin/**} 的 {@code PLATFORM_ADMIN} 规则统一兜底，这里不重复声明；
 * 凭据只以引用形式存取，因此响应里没有任何可用的明文密钥。</p>
 *
 * <p>操作者一律取自安全上下文，请求体无法自我提权。管理操作审计与幂等回执由平台命令
 * 执行器和持久化适配器在业务事务中写入；控制层日志只保留受控摘要。</p>
 */
@RestController
@Validated
@RequestMapping("/api/admin/v1/channels")
public class ChannelAdminController {

    private static final Logger log = LoggerFactory.getLogger(ChannelAdminController.class);

    private final ChannelAdministrationService administration;

    public ChannelAdminController(ChannelAdministrationService administration) {
        this.administration = administration;
    }

    @GetMapping("/accounts")
    public Mono<List<AccountResponse>> listAccounts() {
        return blocking(() -> administration.accounts().stream().map(ChannelAdminController::account).toList());
    }

    @GetMapping("/accounts/{bindingId}")
    public Mono<AccountResponse> getAccount(@PathVariable String bindingId) {
        return blocking(() -> account(administration.account(bindingId)));
    }

    @PostMapping("/accounts")
    public Mono<AccountResponse> createAccount(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @Valid @RequestBody CreateAccountRequest request) {
        return blocking(() -> {
            var command = command(actor.userId(), "CREATE_ACCOUNT", request.bindingId(), null,
                    request.requestId(), request.reason(),
                    canonical(request.bindingId(), request.tenantId(), request.provider(), request.externalAccountKey(),
                            request.credentialRef(), request.defaultEmployeeId(), request.sessionScope(),
                            request.enabled(), request.requestId(), request.reason()),
                    safeCreateValues(request));
            AccountResponse saved = administration.executeManagementCommand(command, AccountResponse.class,
                    () -> account(administration.createAccountForCommand(actor.userId(),
                            new ChannelAdministrationService.CreateAccount(request.bindingId(), request.tenantId(),
                                    request.provider(), request.externalAccountKey(), request.credentialRef(),
                                    request.defaultEmployeeId(), request.sessionScope(), request.enabled()))));
            return withRuntimeRefresh(saved, administration.refreshAfterMutation(binding(saved)));
        })
                .doOnNext(created -> log.info("channel account created: actor={} binding={} provider={} scope={}",
                        actor.userId().value(), created.bindingId(), created.provider(), created.sessionScope()));
    }

    /** 未提供的字段保持原值，因此停用与改默认员工可以分开操作。 */
    @PatchMapping("/accounts/{bindingId}")
    public Mono<AccountResponse> updateAccount(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @PathVariable String bindingId,
                                               @Valid @RequestBody UpdateAccountRequest request) {
        return blocking(() -> {
            var command = command(actor.userId(), "UPDATE_ACCOUNT", bindingId, null,
                    request.requestId(), request.reason(),
                    canonical(bindingId, request.enabled(), request.defaultEmployeeId(), request.sessionScope(),
                            request.expectedRevision(), request.requestId(), request.reason()),
                    safeChangedValues(request.enabled(), request.defaultEmployeeId(), request.sessionScope()));
            AccountResponse saved = administration.executeManagementCommand(command, AccountResponse.class,
                    () -> account(administration.updateAccountForCommand(actor.userId(), bindingId,
                            new ChannelAdministrationService.UpdateAccount(request.enabled(),
                                    request.defaultEmployeeId(), request.sessionScope()), request.expectedRevision())));
            return withRuntimeRefresh(saved, administration.refreshAfterMutation(binding(saved)));
        })
                .doOnNext(updated -> log.info(
                        "channel account updated: actor={} binding={} enabled={} employee={} scope={}",
                        actor.userId().value(), updated.bindingId(), updated.enabled(),
                        updated.defaultEmployeeId(), updated.sessionScope()));
    }

    @GetMapping("/accounts/{bindingId}/identities")
    public Mono<List<IdentityResponse>> listIdentities(@PathVariable String bindingId) {
        return blocking(() -> administration.identities(bindingId).stream()
                .map(ChannelAdminController::identity).toList());
    }

    /** 绑定后该外部用户的入站即可解析；解绑立即反向生效。 */
    @PutMapping("/accounts/{bindingId}/identities/{externalUserId}")
    public Mono<IdentityResponse> linkIdentity(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @PathVariable String bindingId,
                                               @PathVariable String externalUserId,
                                               @Valid @RequestBody LinkIdentityRequest request) {
        return blocking(() -> {
            var command = command(actor.userId(), "LINK_IDENTITY", bindingId, externalUserId,
                    request.requestId(), request.reason(),
                    canonical(bindingId, externalUserId, request.userId(), request.requestId(), request.reason()),
                    "userId=" + request.userId());
            return administration.executeManagementCommand(command, IdentityResponse.class,
                    () -> identity(administration.linkIdentity(actor.userId(), bindingId, externalUserId,
                            request.userId())));
        })
                .doOnNext(linked -> log.info("channel identity linked: actor={} binding={} external={} user={}",
                        actor.userId().value(), bindingId, externalUserId, linked.userId()));
    }

    @PostMapping("/accounts/{bindingId}/identities/{externalUserId}/revoke")
    public Mono<RevokeIdentityResponse> revokeIdentityCommand(@AuthenticationPrincipal AuthenticatedUser actor,
                                                               @PathVariable String bindingId,
                                                               @PathVariable String externalUserId,
                                                               @Valid @RequestBody RevokeIdentityRequest request) {
        return blocking(() -> {
            var command = command(actor.userId(), "REVOKE_IDENTITY", bindingId, externalUserId,
                    request.requestId(), request.reason(),
                    canonical(bindingId, externalUserId, request.requestId(), request.reason()), "state=REVOKED");
            return administration.executeManagementCommand(command, RevokeIdentityResponse.class, () -> {
                administration.revokeIdentity(actor.userId(), bindingId, externalUserId);
                return new RevokeIdentityResponse(bindingId, externalUserId, "REVOKED");
            });
        });
    }

    @DeleteMapping("/accounts/{bindingId}/identities/{externalUserId}")
    public Mono<Void> revokeIdentity(@AuthenticationPrincipal AuthenticatedUser actor,
                                     @PathVariable String bindingId,
                                     @PathVariable String externalUserId) {
        return blocking(() -> {
            var command = command(actor.userId(), "REVOKE_IDENTITY", bindingId, externalUserId,
                    null, null, canonical(bindingId, externalUserId), "state=REVOKED");
            administration.executeManagementCommand(command, Boolean.class, () -> {
                administration.revokeIdentity(actor.userId(), bindingId, externalUserId);
                return Boolean.TRUE;
            });
            return true;
        }).doOnSuccess(ignored -> log.info("channel identity revoked: actor={} binding={} external={}",
                actor.userId().value(), bindingId, externalUserId)).then();
    }

    /** 渠道运行时的装载视图：确认管理侧写入的配置是否已经生效到框架注册表。 */
    @GetMapping("/runtime")
    public Mono<List<RuntimeChannelResponse>> runtimeChannels() {
        return blocking(() -> administration.runtimeChannels().stream()
                .map(ChannelAdminController::runtime).toList());
    }

    private static AccountResponse account(ChannelAccountBinding binding) {
        return new AccountResponse(binding.bindingId(), binding.tenantId().value(), binding.provider(),
                binding.externalAccountKey(), binding.credentialRef(), binding.defaultEmployeeId(),
                binding.sessionScope().name(), binding.enabled(), binding.revision(), null);
    }

    private static IdentityResponse identity(ChannelIdentityBinding binding) {
        return new IdentityResponse(binding.bindingId(), binding.externalUserId(), binding.userId().value(),
                binding.state().name(), binding.linkedAt(), binding.updatedAt());
    }

    private static RuntimeChannelResponse runtime(ChannelRuntimeAdmin.ChannelRuntimeStatus status) {
        return new RuntimeChannelResponse(status.channelId(), status.defaultAgentId(), status.sessionScope(),
                status.bindingCount(), status.started(), status.instanceLabel(), status.observedAt(),
                status.accountSnapshots());
    }

    private static AccountResponse withRuntimeRefresh(AccountResponse saved,
                                                       ChannelAdministrationService.RuntimeRefresh refresh) {
        return new AccountResponse(saved.bindingId(), saved.tenantId(), saved.provider(),
                saved.externalAccountKey(), saved.credentialRef(), saved.defaultEmployeeId(),
                saved.sessionScope(), saved.enabled(), saved.revision(), refresh);
    }

    private static ChannelAccountBinding binding(AccountResponse response) {
        return new ChannelAccountBinding(response.bindingId(), new TenantId(response.tenantId()),
                response.provider(), response.externalAccountKey(), response.credentialRef(),
                response.defaultEmployeeId(), SessionScope.valueOf(response.sessionScope()),
                response.enabled(), response.revision());
    }

    private static ChannelManagementCommandExecutor.Command command(
            com.haizhuo.brain.kernel.identity.UserId actor, String action, String bindingId,
            String externalUserId, String requestId, String reason, String payload, String safeChanges) {
        boolean hasRequestId = requestId != null && !requestId.isBlank();
        boolean hasReason = reason != null && !reason.isBlank();
        if (hasRequestId != hasReason) {
            throw new IllegalArgumentException("requestId and reason must be supplied together");
        }
        return new ChannelManagementCommandExecutor.Command(actor.value(), action, bindingId, externalUserId,
                hasRequestId ? requestId.trim() : null, hasReason ? reason.trim() : null,
                hasRequestId ? "ADMIN_UI" : "LEGACY_CLIENT", payload, safeChanges);
    }

    /** 使用稳定的长度前缀规范化输入；原始命令数据仅由基础设施适配器计算摘要。 */
    private static String canonical(Object... values) {
        return Arrays.stream(values).map(value -> Objects.toString(value, "<null>"))
                .map(value -> value.length() + ":" + value).reduce("", String::concat);
    }

    private static String safeChangedValues(Boolean enabled, Long employeeId, SessionScope scope) {
        List<String> values = new ArrayList<>();
        if (enabled != null) values.add("enabled=" + enabled);
        if (employeeId != null) values.add("defaultEmployeeId=" + employeeId);
        if (scope != null) values.add("sessionScope=" + scope.name());
        return String.join(",", values);
    }

    private static String safeCreateValues(CreateAccountRequest request) {
        SessionScope scope = request.sessionScope() == null ? SessionScope.defaultScope() : request.sessionScope();
        boolean enabled = request.enabled() == null || request.enabled();
        return "provider=" + request.provider() + ",defaultEmployeeId=" + request.defaultEmployeeId()
                + ",sessionScope=" + scope.name() + ",enabled=" + enabled;
    }

    @ExceptionHandler(ChannelAccountChangedException.class)
    public Mono<ResponseEntity<com.haizhuo.brain.api.error.ApiError>> handleAccountChanged(
            ChannelAccountChangedException ignored) {
        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new com.haizhuo.brain.api.error.ApiError("CHANNEL_ACCOUNT_CHANGED",
                        "账号已被其他管理员修改，请刷新后重新核对。")));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record CreateAccountRequest(@NotBlank @Size(max = 64) String bindingId,
                                       @Positive long tenantId,
                                       @NotBlank @Size(max = 32) String provider,
                                       @NotBlank @Size(max = 128) String externalAccountKey,
                                       @NotBlank @Size(max = 256) String credentialRef,
                                       @Positive long defaultEmployeeId,
                                       SessionScope sessionScope,
                                       Boolean enabled,
                                       @Size(max = 128) String requestId,
                                       @Size(max = 500) String reason) {
        public CreateAccountRequest(String bindingId, long tenantId, String provider, String externalAccountKey,
                                    String credentialRef, long defaultEmployeeId, SessionScope sessionScope,
                                    Boolean enabled) {
            this(bindingId, tenantId, provider, externalAccountKey, credentialRef, defaultEmployeeId,
                    sessionScope, enabled, null, null);
        }
    }

    public record UpdateAccountRequest(Boolean enabled, Long defaultEmployeeId, SessionScope sessionScope,
                                       @Positive Long expectedRevision, @Size(max = 128) String requestId,
                                       @Size(max = 500) String reason) {
        public UpdateAccountRequest(Boolean enabled, Long defaultEmployeeId, SessionScope sessionScope) {
            this(enabled, defaultEmployeeId, sessionScope, null, null, null);
        }
    }

    public record LinkIdentityRequest(@Positive long userId, @Size(max = 128) String requestId,
                                      @Size(max = 500) String reason) {
        public LinkIdentityRequest(long userId) {
            this(userId, null, null);
        }
    }

    public record RevokeIdentityRequest(@NotBlank @Size(max = 128) String requestId,
                                        @NotBlank @Size(max = 500) String reason) {
    }

    public record RevokeIdentityResponse(String bindingId, String externalUserId, String state) {
    }

    /** 只回传凭据引用；平台侧不保存也不回传明文凭据。 */
    public record AccountResponse(String bindingId, long tenantId, String provider, String externalAccountKey,
                                  String credentialRef, long defaultEmployeeId, String sessionScope,
                                  boolean enabled, long revision,
                                  ChannelAdministrationService.RuntimeRefresh runtimeRefresh) {
        public AccountResponse(String bindingId, long tenantId, String provider, String externalAccountKey,
                              String credentialRef, long defaultEmployeeId, String sessionScope,
                              boolean enabled) {
            this(bindingId, tenantId, provider, externalAccountKey, credentialRef,
                    defaultEmployeeId, sessionScope, enabled, 1, null);
        }
    }

    public record IdentityResponse(String bindingId, String externalUserId, long userId, String state,
                                   Instant linkedAt, Instant updatedAt) {
    }

    public record RuntimeChannelResponse(String channelId, String defaultAgentId, String sessionScope,
                                         int bindingCount, boolean started, String instanceLabel,
                                         Instant observedAt,
                                         List<ChannelRuntimeAdmin.AccountLoadSnapshot> accountSnapshots) {
        public RuntimeChannelResponse(String channelId, String defaultAgentId, String sessionScope,
                                      int bindingCount, boolean started) {
            this(channelId, defaultAgentId, sessionScope, bindingCount, started, "UNKNOWN", null, List.of());
        }
    }
}
