package com.haizhuo.brain.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 参数约束失败必须被翻译成 400：若放任它冒泡，安全过滤链的兜底分支会把它报成 503，
 * 调用方就无法区分「参数写错了」与「依赖不可用」。
 */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void parameterViolationIsBadRequest() {
        ResponseEntity<ApiError> response = handler.handleParameterValidation(new ConstraintViolationException(Set.of())).block();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST", response.getBody().code());
    }

    @Test
    void dependencyFailureRemainsServiceUnavailable() {
        ResponseEntity<ApiError> response = handler
                .handleDependencyFailure(new DataAccessResourceFailureException("connection refused")).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("IDENTITY_STORE_UNAVAILABLE", response.getBody().code());
    }
}
