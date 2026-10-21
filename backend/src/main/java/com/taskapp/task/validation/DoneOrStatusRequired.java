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

/** Field code REQUIRED on {@code done} (PATCH): neither {@code done} nor {@code status} sent (or both null). */
@Documented
@Constraint(validatedBy = DoneOrStatusRequired.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DoneOrStatusRequired {

    String message() default "Done or status is required";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<DoneOrStatusRequired, StatusAndDone> {
        @Override
        public boolean isValid(StatusAndDone request, ConstraintValidatorContext context) {
            if (request == null || request.hasChanges()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("done").addConstraintViolation();
            return false;
        }
    }
}
