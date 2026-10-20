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
 * Field code REQUIRES_DUE_DATE on {@code dueTime} (a time is being set) and on {@code reminder} (an offset other than
 * NONE is being set) when the request has no {@code dueDate} (contract 0.14.0).
 */
@Documented
@Constraint(validatedBy = RequiresDueDate.Validator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresDueDate {

    String TIME_MESSAGE = "A due time needs a due date";
    String REMINDER_MESSAGE = "A reminder needs a due date";

    String message() default TIME_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<RequiresDueDate, DueFields> {
        @Override
        public boolean isValid(DueFields request, ConstraintValidatorContext context) {
            if (request == null || request.dueDate() != null) {
                return true;
            }
            boolean time = request.setsDueTime();
            // An unknown reminder value is reported by ValidReminderValue; here only a real offset counts.
            boolean reminder = ReminderOffset.parse(request.reminder()).isPresent();
            if (!time && !reminder) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            if (time) {
                context.buildConstraintViolationWithTemplate(TIME_MESSAGE).addPropertyNode("dueTime")
                        .addConstraintViolation();
            }
            if (reminder) {
                context.buildConstraintViolationWithTemplate(REMINDER_MESSAGE).addPropertyNode("reminder")
                        .addConstraintViolation();
            }
            return false;
        }
    }
}
