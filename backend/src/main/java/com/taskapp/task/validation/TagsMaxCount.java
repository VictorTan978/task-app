package com.taskapp.task.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import com.taskapp.task.TagNames;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** Field code TOO_MANY: more than 10 different names (ignoring case and accents). A {@code null} list is valid (= leave the tags unchanged). */
@Documented
@Constraint(validatedBy = TagsMaxCount.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TagsMaxCount {

    String message() default "A task can have at most 10 tags";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<TagsMaxCount, List<String>> {
        @Override
        public boolean isValid(List<String> tags, ConstraintValidatorContext context) {
            return tags == null || TagNames.countDistinct(tags) <= TagNames.MAX_PER_TASK;
        }
    }
}
