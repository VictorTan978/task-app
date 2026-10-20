package com.taskapp.task.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.taskapp.reminder.ReminderOffset;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Field code INVALID_REMINDER on {@code reminder}: null (unchanged), {@code NONE} or one of the offsets, case-sensitive.
 * Whether an offset fits the task (timed vs date-only) needs the stored task, so the service checks that.
 */
@Documented
@Constraint(validatedBy = ValidReminderValue.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidReminderValue {

    String MESSAGE = "Reminder is not valid for this task";

    String message() default MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidReminderValue, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || ReminderOffset.isRequestValue(value);
        }
    }
}
