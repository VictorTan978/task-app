package com.taskapp.task.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** A text that may be absent (null) but, when sent, must not be blank. Field code REQUIRED (PATCH {@code title}). */
@Documented
@Constraint(validatedBy = NotBlankIfPresent.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotBlankIfPresent {

    String message() default "Title is required";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<NotBlankIfPresent, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || !value.isBlank();
        }
    }
}
