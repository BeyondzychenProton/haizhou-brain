package com.haizhuo.brain.api.admin;

import com.haizhuo.brain.api.error.ApiError;
import com.haizhuo.brain.platform.audit.AuditChange;
import com.haizhuo.brain.platform.audit.AuditCorrelationId;
import com.haizhuo.brain.platform.audit.AuditQueryService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 仅管理员可访问的结构化审计安全投影；授权由 /api/admin/** 统一处理。 */
@RestController
@Validated
@RequestMapping("/api/admin/v1/audits")
public class AuditQueryController {
    private static final String CSV_HEADER = "auditId,domain,action,actorUserId,targetType,targetId,requestId,runId,outcome,safeSummary,createdAt";
    private final AuditQueryService queries;

    public AuditQueryController(AuditQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public Mono<AuditQueryService.Page> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @Min(1) Long actorUserId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return Mono.fromCallable(() -> queries.list(actor.userId().value(), domain, action, actorUserId,
                        targetType, targetId, requestId, runId, createdFrom, createdTo, cursor, limit))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{auditId}")
    public Mono<AuditDetailResponse> detail(@AuthenticationPrincipal AuthenticatedUser actor,
                                            @PathVariable @NotBlank @Size(max = 300) String auditId) {
        return Mono.fromCallable(() -> detailResponse(queries.detail(actor.userId().value(), auditId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public Mono<ResponseEntity<String>> export(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @Min(1) Long actorUserId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo) {
        return Mono.fromCallable(() -> {
            AuditQueryService.Export result = queries.export(actor.userId().value(), domain, action, actorUserId,
                    targetType, targetId, requestId, runId, createdFrom, createdTo);
            String csv = csv(result.items());
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-export.csv\"")
                    .header("X-Audit-Export-Count", Integer.toString(result.count()))
                    .header("X-Audit-Export-Maximum", Integer.toString(result.maximumRows()))
                    .header("X-Audit-Export-Complete", Boolean.toString(result.complete()))
                    .body(csv);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ExceptionHandler(AuditQueryService.AuditQueryException.class)
    public Mono<ResponseEntity<ApiError>> auditError(AuditQueryService.AuditQueryException error) {
        return switch (error.error()) {
            case INVALID_QUERY -> error(HttpStatus.BAD_REQUEST, "AUDIT_INVALID_QUERY", "审计查询参数无效。");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "AUDIT_NOT_FOUND", "审计记录不存在。");
            case EXPORT_TOO_LARGE -> error(HttpStatus.CONFLICT, "AUDIT_EXPORT_TOO_LARGE", "导出超过 10000 条，请缩小筛选范围。");
        };
    }

    @ExceptionHandler(DataAccessException.class)
    public Mono<ResponseEntity<ApiError>> unavailable(DataAccessException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_QUERY_UNAVAILABLE", "审计查询暂不可用。");
    }

    private static AuditDetailResponse detailResponse(AuditQueryService.Detail detail) {
        var summary = detail.summary();
        return new AuditDetailResponse(summary.auditId(), summary.domain(), summary.action(), summary.actorUserId(),
                summary.targetType(), summary.targetId(), summary.requestId(), summary.runId(), summary.outcome(),
                summary.safeSummary(), summary.createdAt(), detail.safeChanges(), detail.correlationIds(),
                detail.evidenceReferenceLabel(), detail.sourceCompleteness());
    }

    private static String csv(List<AuditQueryService.AuditSummary> rows) {
        StringBuilder output = new StringBuilder(CSV_HEADER.length() + rows.size() * 160);
        output.append(CSV_HEADER).append("\r\n");
        for (AuditQueryService.AuditSummary row : rows) {
            List<String> cells = new ArrayList<>(11);
            cells.add(row.auditId());
            cells.add(row.domain().name());
            cells.add(row.action());
            cells.add(row.actorUserId() == null ? "" : row.actorUserId().toString());
            cells.add(row.targetType());
            cells.add(row.targetId());
            cells.add(row.requestId());
            cells.add(row.runId());
            cells.add(row.outcome().name());
            cells.add(row.safeSummary());
            cells.add(row.createdAt().toString());
            output.append(cells.stream().map(AuditQueryController::csvCell)
                    .reduce((left, right) -> left + "," + right).orElse("")).append("\r\n");
        }
        return output.toString();
    }

    private static String csvCell(String raw) {
        String value = Objects.toString(raw, "");
        String leading = value.stripLeading();
        if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static Mono<ResponseEntity<ApiError>> error(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record AuditDetailResponse(String auditId, com.haizhuo.brain.platform.audit.AuditDomain domain,
                                      String action, Long actorUserId, String targetType, String targetId,
                                      String requestId, String runId,
                                      com.haizhuo.brain.platform.audit.AuditOutcome outcome, String safeSummary,
                                      java.time.Instant createdAt, List<AuditChange> safeChanges,
                                      List<AuditCorrelationId> correlationIds, String evidenceReferenceLabel,
                                      com.haizhuo.brain.platform.audit.AuditSourceCompleteness sourceCompleteness) { }
}
