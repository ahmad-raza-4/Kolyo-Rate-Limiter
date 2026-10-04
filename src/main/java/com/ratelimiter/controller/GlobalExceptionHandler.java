package com.ratelimiter.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Change 3: Global exception handler — prevents raw stacktraces leaking to clients.
 * Uses RFC 7807 ProblemDetail (built into Spring 6+) for structured error responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Validation failures on @Valid request bodies (e.g. blank key, tokens < 1). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("urn:ratelimiter:error:validation"));
        problem.setTitle("Validation Failed");
        log.debug("Validation error: {}", detail);
        return ResponseEntity.badRequest().body(problem);
    }

    /** Bad algorithm type or unsupported config (thrown by AlgorithmFactory or RateLimitConfig.validate()). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("urn:ratelimiter:error:bad-request"));
        problem.setTitle("Bad Request");
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    /** Catch-all for unexpected runtime errors — returns 500 without exposing internal details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.");
        problem.setType(URI.create("urn:ratelimiter:error:internal"));
        problem.setTitle("Internal Server Error");
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(problem);
    }
}
