package com.taskapp.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordLengthValidatorTest {

    private final PasswordLengthValidator validator = new PasswordLengthValidator();

    @ParameterizedTest
    @CsvSource({
            "7, a, false",
            "8, a, true",
            "72, a, true",
            "73, a, false",
            "36, é, true",   // 72 bytes
            "37, é, false",  // 74 bytes, 37 chars
    })
    void enforcesCharacterAndByteLimits(int repeat, String unit, boolean valid) {
        assertThat(validator.isValid(unit.repeat(repeat), null)).isEqualTo(valid);
    }

    @ParameterizedTest
    @CsvSource({"'        '"})
    void doesNotTrim(String eightSpaces) {
        assertThat(validator.isValid(eightSpaces, null)).isTrue();
    }
}
