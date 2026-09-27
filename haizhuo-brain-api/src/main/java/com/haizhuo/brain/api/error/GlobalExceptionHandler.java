package com.haizhuo.brain.api.error;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.security.identity.AuthenticationRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(AuthenticationRejectedException.class)
    public Mono<ResponseEntity<ApiError>> handleAuthentication(AuthenticationRejectedException error) {
        return response(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "认证失败或会话已失效");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Mono<ResponseEntity<ApiError>> handleForbidden(AccessDeniedException error) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "没有访问该资源的权限");
    }

    @ExceptionHandler({IllegalArgumentException.class, WebExchangeBindException.class})
    public Mono<ResponseEntity<ApiError>> handleBadRequest(Exception error) {
        String message = error instanceof IllegalArgumentException ? error.getMessage() : "请求参数不正确";
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<ApiError>> handleConflict(IllegalStateException error) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", error.getMessage());
    }

    @ExceptionHandler(AgentDefinitionManagementService.DefinitionNotPublishableException.class)
    public Mono<ResponseEntity<DefinitionValidationError>> handleDefinitionNotPublishable(
            AgentDefinitionManagementService.DefinitionNotPublishableException error) {
        return Mono.just(ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new DefinitionValidationError("DEFINITION_NOT_PUBLISHABLE", error.validation())));
    }

    @ExceptionHandler(DataAccessException.class)
    public Mono<ResponseEntity<ApiError>> handleDependencyFailure(DataAccessException error) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_STORE_UNAVAILABLE", "身份服务暂不可用");
    }

    private static Mono<ResponseEntity<ApiError>> response(HttpStatus status, String code, String message) {
        return Mono.just(ResponseEntity.status(status).body(new ApiError(code, message)));
    }

    public record DefinitionValidationError(String code, AgentDefinitionManagementService.ValidationResult validation) {
    }
}
