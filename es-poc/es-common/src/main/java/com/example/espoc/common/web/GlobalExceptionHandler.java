package com.example.espoc.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e, HttpServletRequest req) {
        if (e.status().is5xxServerError()) {
            log.error("[{}] {} {} → {}", req.getMethod(), req.getRequestURI(), e.code(), e.getMessage(), e);
        } else {
            log.warn("[{}] {} {} → {}", req.getMethod(), req.getRequestURI(), e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage(), req));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("validation error");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", msg, req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e, HttpServletRequest req) {
        log.error("[{}] {} unhandled error", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL_ERROR", e.getMessage(), req));
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest req) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "path", req.getRequestURI(),
                "code", code,
                "message", message == null ? "" : message
        );
    }
}
