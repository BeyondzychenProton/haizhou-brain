package com.haizhuo.brain.api.channel;

import com.haizhuo.brain.platform.channel.ChannelAccountBinding;
import com.haizhuo.brain.platform.channel.ChannelAdministrationService;
import com.haizhuo.brain.platform.channel.ChannelIdentityBinding;
import com.haizhuo.brain.platform.channel.ChannelRuntimeAdmin;
import com.haizhuo.brain.platform.channel.SessionScope;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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
 * <p>操作者一律取自安全上下文，请求体无法自我提权。平台层按既有约定不依赖日志实现，
 * 因此管理操作的审计留痕落在控制层：改动谁、改了什么，都在这里记录。</p>
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
        return blocking(() -> account(administration.createAccount(actor.userId(),
                new ChannelAdministrationService.CreateAccount(request.bindingId(), request.tenantId(),
                        request.provider(), request.externalAccountKey(), request.credentialRef(),
                        request.defaultEmployeeId(), request.sessionScope(), request.enabled()))))
                .doOnNext(created -> log.info("channel account created: actor={} binding={} provider={} scope={}",
                        actor.userId().value(), created.bindingId(), created.provider(), created.sessionScope()));
    }

    /** 未提供的字段保持原值，因此停用与改默认员工可以分开操作。 */
    @PatchMapping("/accounts/{bindingId}")
    public Mono<AccountResponse> updateAccount(@AuthenticationPrincipal AuthenticatedUser actor,
                                               @PathVariable String bindingId,
                                               @Valid @RequestBody UpdateAccountRequest request) {
        return blocking(() -> account(administration.updateAccount(actor.userId(), bindingId,
                new ChannelAdministrationService.UpdateAccount(request.enabled(), request.defaultEmployeeId(),
                        request.sessionScope()))))
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
        return blocking(() -> identity(administration.linkIdentity(actor.userId(), bindingId, externalUserId,
                request.userId())))
                .doOnNext(linked -> log.info("channel identity linked: actor={} binding={} external={} user={}",
                        actor.userId().value(), bindingId, externalUserId, linked.userId()));
    }

    @DeleteMapping("/accounts/{bindingId}/identities/{externalUserId}")
    public Mono<Void> revokeIdentity(@AuthenticationPrincipal AuthenticatedUser actor,
                                     @PathVariable String bindingId,
                                     @PathVariable String externalUserId) {
        return blocking(() -> {
            administration.revokeIdentity(actor.userId(), bindingId, externalUserId);
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
                binding.sessionScope().name(), binding.enabled());
    }

    private static IdentityResponse identity(ChannelIdentityBinding binding) {
        return new IdentityResponse(binding.bindingId(), binding.externalUserId(), binding.userId().value(),
                binding.state().name(), binding.linkedAt(), binding.updatedAt());
    }

    private static RuntimeChannelResponse runtime(ChannelRuntimeAdmin.ChannelRuntimeStatus status) {
        return new RuntimeChannelResponse(status.channelId(), status.defaultAgentId(), status.sessionScope(),
                status.bindingCount(), status.started());
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
                                       Boolean enabled) {
    }

    public record UpdateAccountRequest(Boolean enabled, Long defaultEmployeeId, SessionScope sessionScope) {
    }

    public record LinkIdentityRequest(@Positive long userId) {
    }

    /** 只回传凭据引用；平台侧不保存也不回传明文凭据。 */
    public record AccountResponse(String bindingId, long tenantId, String provider, String externalAccountKey,
                                  String credentialRef, long defaultEmployeeId, String sessionScope,
                                  boolean enabled) {
    }

    public record IdentityResponse(String bindingId, String externalUserId, long userId, String state,
                                   Instant linkedAt, Instant updatedAt) {
    }

    public record RuntimeChannelResponse(String channelId, String defaultAgentId, String sessionScope,
                                         int bindingCount, boolean started) {
    }
}
