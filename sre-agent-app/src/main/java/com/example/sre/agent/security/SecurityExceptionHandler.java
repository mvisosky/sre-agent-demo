package com.example.sre.agent.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.bind.MethodArgumentNotValidException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(PromptInjectionException.class)
    public ResponseEntity<Map<String, Object>> handlePromptInjection(PromptInjectionException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", "PROMPT_INJECTION_BLOCKED",
            "message", "The request was rejected because it appears to contain instructions aimed at the AI model.",
            "timestamp", Instant.now().toString()
        ));
    }

    /** Consistent 400 body for @Valid failures (incl. @SafeText/@Size). Reports field names only, never rejected values. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField()).distinct().sorted().toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
            "error", "VALIDATION_FAILED",
            "message", "The request was rejected because one or more fields are invalid.",
            "fields", fields,
            "timestamp", Instant.now().toString()
        ));
    }
}
