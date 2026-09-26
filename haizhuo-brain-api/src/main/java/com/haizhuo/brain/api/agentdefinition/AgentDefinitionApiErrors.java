package com.haizhuo.brain.api.agentdefinition;

import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将草稿并发冲突和发布校验结果映射为可由管理客户端直接处理的 HTTP 响应。 */
@RestControllerAdvice(assignableTypes = AgentDefinitionManagementController.class)
@Profile("meeting-mock | test")
public class AgentDefinitionApiErrors {
    @ExceptionHandler(AgentDefinitionManagementService.DefinitionNotPublishableException.class)
    public ResponseEntity<ValidationError> notPublishable(AgentDefinitionManagementService.DefinitionNotPublishableException error) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ValidationError("DEFINITION_NOT_PUBLISHABLE", error.validation()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("CONFLICT", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", error.getMessage()));
    }

    public record ApiError(String code, String message) {}
    public record ValidationError(String code, AgentDefinitionManagementService.ValidationResult validation) {}
}
