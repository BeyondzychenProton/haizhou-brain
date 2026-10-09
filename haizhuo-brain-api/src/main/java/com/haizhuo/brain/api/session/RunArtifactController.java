package com.haizhuo.brain.api.session;

import com.haizhuo.brain.api.error.ApiError;
import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.platform.run.RunArtifactService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ContentDisposition;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 通过属主身份校验提供 Markdown 成果物导出与附件下载。 */
@RestController
@Validated
public final class RunArtifactController {
    private final RunArtifactService artifacts;

    public RunArtifactController(RunArtifactService artifacts) { this.artifacts = artifacts; }

    @PostMapping("/api/v1/sessions/runs/{runId}/artifacts/markdown")
    public Mono<ResponseEntity<ArtifactResponse>> createMarkdown(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable @Size(max = 36) String runId,
            @Valid @RequestBody CreateRequest request) {
        return blocking(() -> {
            RunArtifactService.Submission submission = artifacts.createMarkdown(new RunId(runId), user.userId(),
                    request.requestId());
            HttpStatus status = submission.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).headers(privateNoStoreHeaders())
                    .body(ArtifactResponse.from(submission.artifact()));
        });
    }

    @GetMapping("/api/v1/sessions/runs/{runId}/artifacts")
    public Mono<ResponseEntity<ArtifactPageResponse>> list(@AuthenticationPrincipal AuthenticatedUser user,
                                                           @PathVariable @Size(max = 36) String runId,
                                                           @RequestParam(required = false) @Size(max = 1024) String cursor,
                                                           @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return blocking(() -> {
            var page = artifacts.list(new RunId(runId), user.userId(), cursor, limit == null ? 20 : limit);
            return ResponseEntity.ok().headers(privateNoStoreHeaders()).body(
                    new ArtifactPageResponse(page.items().stream().map(ArtifactResponse::from).toList(),
                            page.nextCursor(), page.hasMore()));
        });
    }

    @GetMapping("/api/v1/artifacts/{artifactId}/content")
    public Mono<ResponseEntity<?>> content(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable @Size(max = 36) String artifactId,
                                           @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        return blocking(() -> {
            RunArtifactService.Download download = artifacts.loadContent(artifactId, user.userId());
            byte[] content = download.content();
            HttpHeaders headers = contentHeaders(download.artifact());
            Range parsed = parseRange(range, content.length);
            if (parsed.invalid()) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + content.length);
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .headers(headers).body(new ApiError("INVALID_RANGE", "请求的文件范围无效"));
            }
            if (parsed.partial()) {
                byte[] body = Arrays.copyOfRange(content, Math.toIntExact(parsed.start()),
                        Math.toIntExact(parsed.end() + 1));
                headers.set(HttpHeaders.CONTENT_RANGE,
                        "bytes " + parsed.start() + "-" + parsed.end() + "/" + content.length);
                headers.setContentLength(body.length);
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(headers).body(body);
            }
            headers.setContentLength(content.length);
            return ResponseEntity.ok().headers(headers).body(content);
        });
    }

    @ExceptionHandler(RunArtifactService.RunArtifactNotFoundException.class)
    public Mono<ResponseEntity<ApiError>> notFound(RunArtifactService.RunArtifactNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "成果物不存在");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactNotReadyException.class)
    public Mono<ResponseEntity<ApiError>> notReady(RunArtifactService.RunArtifactNotReadyException ignored) {
        return error(HttpStatus.CONFLICT, "ARTIFACT_SOURCE_NOT_READY", "运行尚无可导出的完整结果");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactConflictException.class)
    public Mono<ResponseEntity<ApiError>> conflict(RunArtifactService.RunArtifactConflictException ignored) {
        return error(HttpStatus.CONFLICT, "ARTIFACT_REQUEST_CONFLICT", "导出请求标识已用于不同内容");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactUnavailableException.class)
    public Mono<ResponseEntity<ApiError>> unavailableArtifact(RunArtifactService.RunArtifactUnavailableException ignored) {
        return error(HttpStatus.GONE, "ARTIFACT_UNAVAILABLE", "成果物暂不可用");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactTooLargeException.class)
    public Mono<ResponseEntity<ApiError>> tooLarge(RunArtifactService.RunArtifactTooLargeException ignored) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", "运行结果超过导出限制");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactSourceIntegrityException.class)
    public Mono<ResponseEntity<ApiError>> integrity(RunArtifactService.RunArtifactSourceIntegrityException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_SOURCE_UNAVAILABLE", "运行结果校验失败");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactFeatureNotAvailableException.class)
    public Mono<ResponseEntity<ApiError>> featureDisabled(
            RunArtifactService.RunArtifactFeatureNotAvailableException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_FEATURE_NOT_AVAILABLE", "成果物功能当前不可用");
    }

    @ExceptionHandler(RunArtifactService.RunArtifactStorageUnavailableException.class)
    public Mono<ResponseEntity<ApiError>> storageUnavailable(
            RunArtifactService.RunArtifactStorageUnavailableException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_STORAGE_UNAVAILABLE", "成果物存储暂不可用");
    }

    @ExceptionHandler(DataAccessException.class)
    public Mono<ResponseEntity<ApiError>> indexUnavailable(DataAccessException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_INDEX_UNAVAILABLE", "成果物索引暂不可用");
    }

    private static HttpHeaders contentHeaders(RunArtifactService.ArtifactItem artifact) {
        HttpHeaders headers = privateNoStoreHeaders();
        headers.setContentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(artifact.artifactId() + ".md", StandardCharsets.UTF_8).build().toString());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        return headers;
    }

    private static HttpHeaders privateNoStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
        headers.set(HttpHeaders.PRAGMA, "no-cache");
        return headers;
    }

    private static Range parseRange(String header, long size) {
        if (header == null || header.isBlank()) return new Range(false, false, 0, Math.max(-1, size - 1));
        if (size == 0 || !header.startsWith("bytes=") || header.indexOf(',') >= 0)
            return Range.invalidRange();
        String value = header.substring("bytes=".length()).trim();
        int dash = value.indexOf('-');
        if (dash < 0 || value.indexOf('-', dash + 1) >= 0) return Range.invalidRange();
        try {
            String left = value.substring(0, dash).trim();
            String right = value.substring(dash + 1).trim();
            if (left.isEmpty()) {
                if (!right.matches("\\d+")) return Range.invalidRange();
                long suffix = Long.parseLong(right);
                if (suffix <= 0) return Range.invalidRange();
                long start = Math.max(0, size - suffix);
                return new Range(true, false, start, size - 1);
            }
            if (!left.matches("\\d+") || (!right.isEmpty() && !right.matches("\\d+")))
                return Range.invalidRange();
            long start = Long.parseLong(left);
            long end = right.isEmpty() ? size - 1 : Long.parseLong(right);
            if (start < 0 || end < start || start >= size) return Range.invalidRange();
            return new Range(true, false, start, Math.min(end, size - 1));
        } catch (NumberFormatException invalid) {
            return Range.invalidRange();
        }
    }

    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record CreateRequest(@NotBlank @Size(max = 128) String requestId) { }
    public record ArtifactResponse(String artifactId, String runId, String title, String mediaType, long byteSize,
                                   String sha256, String state, Instant createdAt) {
        static ArtifactResponse from(RunArtifactService.ArtifactItem item) {
            return new ArtifactResponse(item.artifactId(), item.runId(), item.title(), item.mediaType(),
                    item.byteSize(), item.sha256(), item.state(), item.createdAt());
        }
    }
    public record ArtifactPageResponse(java.util.List<ArtifactResponse> items, String nextCursor, boolean hasMore) {
        public ArtifactPageResponse { items = java.util.List.copyOf(items); }
    }
    private record Range(boolean partial, boolean invalid, long start, long end) {
        private static Range invalidRange() { return new Range(false, true, 0, -1); }
    }
}
