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

/** Field code INVALID_REPEAT on {@code repeat} (contract 0.15.0, see {@link com.taskapp.task.dto.RepeatInput}). */
@Documented
@Constraint(validatedBy = ValidRepeat.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidRepeat {

    String message() default "Repeat is not valid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidRepeat, RepeatFields> {
        @Override
        public boolean isValid(RepeatFields request, ConstraintValidatorContext context) {
            if (request == null || request.repeat() == null || !request.repeat().invalid()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("repeat").addConstraintViolation();
            return false;
        }
    }
}
