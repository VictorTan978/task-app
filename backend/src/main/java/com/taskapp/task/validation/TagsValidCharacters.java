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

/** Field code INVALID_CHARACTERS: an element contains a comma or a control character. A {@code null} list is valid (= leave the tags unchanged). */
@Documented
@Constraint(validatedBy = TagsValidCharacters.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TagsValidCharacters {

    String message() default "Tags can't contain commas or control characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<TagsValidCharacters, List<String>> {
        @Override
        public boolean isValid(List<String> tags, ConstraintValidatorContext context) {
            return tags == null || tags.stream().noneMatch(TagNames::hasInvalidCharacters);
        }
    }
}
