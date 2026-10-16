package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TagNamesTest {

    @Test
    void normalizeStripsAndCollapsesEveryKindOfWhitespaceToOneSpace() {
        assertThat(TagNames.normalize("  Work ")).isEqualTo("Work");
        assertThat(TagNames.normalize("a \t\n b")).isEqualTo("a b");
        assertThat(TagNames.normalize(" a   b　")).isEqualTo("a b");
        assertThat(TagNames.normalize("a b c d")).isEqualTo("a b c d");
        assertThat(TagNames.normalize("é 😀")).isEqualTo("é 😀");
        assertThat(TagNames.normalize("   ")).isEmpty();
        assertThat(TagNames.normalize(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {",", "a,b", "a\tb", "a\u0000", "\u0007", "\u007f", "\u0085", "\u009f", "x\u001c"})
    void commasAndControlCharactersAreInvalid(String raw) {
        assertThat(TagNames.hasInvalidCharacters(raw)).isTrue();
        assertThat(TagNames.isValid(raw)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Work", "wörk", "😀 fun", "a b", "C++/C#", "​", "­", "中文"})
    void otherCharactersAreAllowed(String raw) {
        assertThat(TagNames.hasInvalidCharacters(raw)).isFalse();
    }

    @Test
    void lengthIsUtf16UnitsAfterNormalising() {
        assertThat(TagNames.isTooLong("  " + "x".repeat(30) + "  ")).isFalse();
        assertThat(TagNames.isTooLong("x".repeat(31))).isTrue();
        assertThat(TagNames.isTooLong("😀".repeat(15))).isFalse();
        assertThat(TagNames.isTooLong("😀".repeat(15) + "x")).isTrue();
        assertThat(TagNames.isTooLong("a" + " ".repeat(40) + "b")).isFalse();
    }

    @Test
    void blankMeansNullOrNothingAfterNormalising() {
        assertThat(TagNames.isBlank(null)).isTrue();
        assertThat(TagNames.isBlank("")).isTrue();
        assertThat(TagNames.isBlank("  \n")).isTrue();
        assertThat(TagNames.isBlank(" x ")).isFalse();
    }

    @Test
    void keyIgnoresCaseAndAccents() {
        assertThat(TagNames.key("Work")).isEqualTo(TagNames.key("wörk")).isEqualTo(TagNames.key("WÖRK"));
        assertThat(TagNames.key("café")).isEqualTo(TagNames.key("CAFE"));
        assertThat(TagNames.key("Work")).isNotEqualTo(TagNames.key("Works"));
        // Only Latin-style diacritics are dropped: a Devanagari vowel sign is part of the letter.
        assertThat(TagNames.key("कि")).isNotEqualTo(TagNames.key("क"));
    }

    @Test
    void distinctKeepsTheFirstSpellingInRequestOrder() {
        assertThat(TagNames.distinctNormalized(List.of(" Home ", "Work", "home", "WÖRK", "Errands")))
                .containsExactly("Home", "Work", "Errands");
        assertThat(TagNames.countDistinct(Arrays.asList("a", "A", " ", null, "b"))).isEqualTo(2);
    }
}
