package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** specs/google-sign-in.md AC4: display names of accounts created with Google. */
class GoogleDisplayNamesTest {

    private static final Predicate<String> NONE_TAKEN = name -> false;

    /** Taken names, compared ignoring case (the database also ignores accents). */
    private static Predicate<String> taken(String... names) {
        Set<String> set = new HashSet<>();
        for (String name : names) {
            set.add(name.toLowerCase(Locale.ROOT));
        }
        return name -> set.contains(name.toLowerCase(Locale.ROOT));
    }

    @Test
    void usesTheTrimmedGoogleName() {
        assertThat(GoogleDisplayNames.generate("  José Pérez \t", "jose@gmail.com", NONE_TAKEN))
                .isEqualTo("José Pérez");
    }

    @Test
    void emptyOrBlankNameUsesTheEmailLocalPart() {
        assertThat(GoogleDisplayNames.generate(null, "ana.lopez@gmail.com", NONE_TAKEN)).isEqualTo("ana.lopez");
        assertThat(GoogleDisplayNames.generate("   ", "ana.lopez@gmail.com", NONE_TAKEN)).isEqualTo("ana.lopez");
        assertThat(GoogleDisplayNames.generate("  ", "ana@gmail.com", NONE_TAKEN)).isEqualTo("ana");
    }

    @Test
    void longNameIsCutTo50Characters() {
        String name = GoogleDisplayNames.generate("a".repeat(80), "a@gmail.com", NONE_TAKEN);

        assertThat(name).isEqualTo("a".repeat(50));
    }

    @Test
    void cutNeverSplitsASurrogatePairAndIsTrimmedAgain() {
        // 49 letters + an emoji (2 UTF-16 chars): cutting at 50 would leave half of it.
        String emoji = "😀";
        String name = GoogleDisplayNames.generate("b".repeat(49) + emoji + "tail", "b@gmail.com", NONE_TAKEN);
        assertThat(name).isEqualTo("b".repeat(49));
        assertThat(Character.isHighSurrogate(name.charAt(name.length() - 1))).isFalse();

        // A cut that ends right after a space is trimmed.
        assertThat(GoogleDisplayNames.generate("c".repeat(49) + " dddd", "c@gmail.com", NONE_TAKEN))
                .isEqualTo("c".repeat(49));
    }

    @Test
    void takenNameGetsTheFirstFreeNumber() {
        assertThat(GoogleDisplayNames.generate("José", "jose@gmail.com", taken("josé"))).isEqualTo("José 2");
        assertThat(GoogleDisplayNames.generate("José", "jose@gmail.com", taken("José", "José 2", "JOSÉ 3")))
                .isEqualTo("José 4");
    }

    @Test
    void suffixedNameStaysWithin50Characters() {
        String long50 = "x".repeat(50);
        Predicate<String> taken = taken(long50, "x".repeat(48) + " 2");

        String name = GoogleDisplayNames.generate("x".repeat(70), "x@gmail.com", taken);

        assertThat(name).isEqualTo("x".repeat(48) + " 3").hasSize(50);
    }

    @Test
    void suffixCutsBeforeASurrogatePair() {
        String emoji = "😀";
        String base = "y".repeat(47) + emoji + "z"; // 50 UTF-16 chars, emoji at 47..48

        String name = GoogleDisplayNames.generate(base, "y@gmail.com", taken(base));

        // Room for 48 chars before " 2": cutting at 48 would split the emoji, so it goes.
        assertThat(name).isEqualTo("y".repeat(47) + " 2");
    }

    @Test
    void givesUpAfterTheLastSuffix() {
        assertThatThrownBy(() -> GoogleDisplayNames.generate("Ana", "ana@gmail.com", name -> true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverEmpty() {
        assertThat(GoogleDisplayNames.base(" ", "@weird")).isEqualTo("User");
        assertThat(GoogleDisplayNames.base(null, null)).isEqualTo("User");
    }
}
