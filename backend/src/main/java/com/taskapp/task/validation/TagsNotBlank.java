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

/** Field code REQUIRED: an element is null, empty or blank after normalising. A {@code null} list is valid (= leave the tags unchanged). */
@Documented
@Constraint(validatedBy = TagsNotBlank.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TagsNotBlank {

    String message() default "Tags can't be blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<TagsNotBlank, List<String>> {
        @Override
        public boolean isValid(List<String> tags, ConstraintValidatorContext context) {
            return tags == null || tags.stream().noneMatch(TagNames::isBlank);
        }
    }
}
