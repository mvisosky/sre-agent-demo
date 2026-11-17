package com.example.sre.common.security;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Rejects text with control characters or known prompt-injection / delimiter-spoofing patterns. */
@Documented
@Constraint(validatedBy = SafeTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {
    String message() default "contains disallowed control characters or suspected prompt-injection content";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
