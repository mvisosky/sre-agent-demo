package com.example.sre.agent.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
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
}
