package com.haizhuo.brain.api.error;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import reactor.core.publisher.Mono;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<String> handleIllegalArgument(IllegalArgumentException error) {
        return Mono.just(error.getMessage());
    }
}
