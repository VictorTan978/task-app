package com.taskapp.task.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.taskapp.task.TaskStatus;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Field code STATUS_CONFLICT on {@code status}: both sent and {@code done != (status == DONE)}. */
@Documented
@Constraint(validatedBy = StatusMatchesDone.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StatusMatchesDone {

    String message() default "Status and done don't match";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StatusMatchesDone, StatusAndDone> {
        @Override
        public boolean isValid(StatusAndDone request, ConstraintValidatorContext context) {
            if (request == null || request.status() == null || request.done() == null
                    || request.done() == (request.status() == TaskStatus.DONE)) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("status").addConstraintViolation();
            return false;
        }
    }
}
