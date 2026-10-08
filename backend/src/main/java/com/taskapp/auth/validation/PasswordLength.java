package com.taskapp.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Password must be 8-72 characters and at most 72 UTF-8 bytes (BCrypt ignores everything past 72 bytes).
 * Null/empty values are left to {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = PasswordLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordLength {

    String message() default "Password must be 8 to 72 characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
