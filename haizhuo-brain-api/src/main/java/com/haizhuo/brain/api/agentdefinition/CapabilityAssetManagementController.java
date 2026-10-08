package com.haizhuo.brain.api.agentdefinition;

import com.haizhuo.brain.platform.employee.CapabilityAssetDraft;
import com.haizhuo.brain.platform.employee.CapabilityAssetDraftConflictException;
import com.haizhuo.brain.platform.employee.CapabilityAssetFileInput;
import com.haizhuo.brain.platform.employee.CapabilityAssetManagementService;
import com.haizhuo.brain.platform.employee.CapabilityAssetRevision;
import com.haizhuo.brain.platform.employee.CapabilityAssetSummary;
import com.haizhuo.brain.platform.employee.CapabilityAssetValidationException;
import com.haizhuo.brain.platform.employee.CapabilityBinding;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 资产正文只对平台管理员开放；认证主体提供审计 actor，不接受请求体 userId。 */
@RestController
@Validated
@Profile("!test")
@RequestMapping("/api/admin/v1/capability-assets")
public class CapabilityAssetManagementController {
    private final CapabilityAssetManagementService assets;

    public CapabilityAssetManagementController(CapabilityAssetManagementService assets) {
        this.assets = assets;
    }

    @GetMapping
    public Mono<List<CapabilityAssetSummary>> list() {
        return blocking(assets::listAssets);
    }

    @GetMapping("/{capabilityCode}/draft")
    public Mono<CapabilityAssetDraft> draft(@PathVariable @NotBlank @Size(max = 128) String capabilityCode) {
        return blocking(() -> assets.getDraft(capabilityCode));
    }

    @PutMapping("/{capabilityCode}/draft")
    public Mono<CapabilityAssetDraft> saveDraft(@AuthenticationPrincipal AuthenticatedUser actor,
                                                @PathVariable @NotBlank @Size(max = 128) String capabilityCode,
                                                @Valid @RequestBody DraftRequest request) {
        List<CapabilityAssetFileInput> files = request.files().stream()
                .map(file -> new CapabilityAssetFileInput(file.relativePath(), file.content())).toList();
        return blocking(() -> assets.saveDraft(capabilityCode, request.expectedDraftRevision(), request.type(),
                request.displayName(), request.description(), files, actor.userId().value(), request.reason()));
    }

    @PostMapping("/{capabilityCode}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CapabilityAssetRevision> publish(@AuthenticationPrincipal AuthenticatedUser actor,
                                                  @PathVariable @NotBlank @Size(max = 128) String capabilityCode,
                                                  @Valid @RequestBody PublishRequest request) {
        return blocking(() -> assets.publish(capabilityCode, request.expectedDraftRevision(), request.requestId(),
                actor.userId().value(), request.reason()));
    }

    @GetMapping("/{capabilityCode}/revisions/{revisionId}")
    public Mono<CapabilityAssetRevision> revision(@PathVariable @NotBlank @Size(max = 128) String capabilityCode,
                                                  @PathVariable @Positive long revisionId) {
        return blocking(() -> assets.getRevision(capabilityCode, revisionId));
    }

    @ExceptionHandler(CapabilityAssetValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<AssetValidationError> invalidAsset(CapabilityAssetValidationException error) {
        return Mono.just(new AssetValidationError("INVALID_ASSET", error.fieldPath(), error.getMessage()));
    }

    @ExceptionHandler(CapabilityAssetDraftConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Mono<AssetValidationError> staleDraft(CapabilityAssetDraftConflictException error) {
        return Mono.just(new AssetValidationError("DRAFT_REVISION_CONFLICT", "expectedDraftRevision", error.getMessage()));
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    public record DraftRequest(@PositiveOrZero int expectedDraftRevision,
                               @NotNull CapabilityBinding.CapabilityType type,
                               @NotBlank @Size(max = 128) String displayName,
                               @NotBlank @Size(max = 1000) String description,
                               @NotEmpty List<@Valid FileRequest> files,
                               @NotBlank @Size(max = 500) String reason) {}

    public record FileRequest(@NotBlank @Size(max = 512) String relativePath, @NotEmpty String content) {}

    public record PublishRequest(@Positive int expectedDraftRevision,
                                 @NotBlank @Size(max = 128) String requestId,
                                 @NotBlank @Size(max = 500) String reason) {}

    public record AssetValidationError(String code, String fieldPath, String message) {}
}
