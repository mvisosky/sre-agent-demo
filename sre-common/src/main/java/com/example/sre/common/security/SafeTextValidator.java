package com.example.sre.common.security;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Map;

public class SafeTextValidator implements ConstraintValidator<SafeText, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().allMatch(e -> isSafe(e.getKey()) && isSafe(e.getValue()));
        }
        return isSafe(value);
    }

    private static boolean isSafe(Object o) {
        return o == null || PromptInjectionDetector.detect(o.toString()).isEmpty();
    }
}
