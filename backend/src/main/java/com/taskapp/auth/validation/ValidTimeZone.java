package com.taskapp.auth.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.taskapp.reminder.FireTimes;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Field code INVALID_TIME_ZONE: an IANA region id the JDK knows (case-sensitive, at most 64 characters), not an
 * offset or abbreviation. A null or blank value is valid here (the {@code NotBlank} rule reports it).
 */
@Documented
@Constraint(validatedBy = ValidTimeZone.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimeZone {

    String message() default "Unknown time zone";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidTimeZone, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || FireTimes.isRegionId(value);
        }
    }
}
