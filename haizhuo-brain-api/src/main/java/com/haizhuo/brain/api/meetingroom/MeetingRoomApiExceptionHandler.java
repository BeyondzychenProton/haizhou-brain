package com.haizhuo.brain.api.meetingroom;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MeetingRoomController.class)
@Profile("meeting-mock | test")
public class MeetingRoomApiExceptionHandler {
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError forbidden(SecurityException exception) { return new ApiError("FORBIDDEN", "本次请求没有相应的动作权限"); }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError conflict(IllegalStateException exception) { return new ApiError("CONFLICT", safeMessage(exception.getMessage())); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        if ("Session not found".equals(exception.getMessage()) || "Run not found".equals(exception.getMessage()))
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", safeMessage(exception.getMessage()), HttpStatus.NOT_FOUND.value()));
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", safeMessage(exception.getMessage())));
    }

    private static String safeMessage(String value) {
        if (value == null || value.isBlank()) return "请求无法处理";
        return value.replaceAll("(?i)(api[-_ ]?key|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[已隐藏]");
    }

    public record ApiError(String code, String message, int status) {
        public ApiError(String code, String message) { this(code, message, code.equals("CONFLICT") ? 409 : code.equals("FORBIDDEN") ? 403 : 400); }
    }
}
