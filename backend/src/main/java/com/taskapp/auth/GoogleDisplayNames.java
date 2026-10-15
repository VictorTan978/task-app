package com.taskapp.auth;

import java.util.function.Predicate;

/**
 * Display name of an account created with Google (specs/google-sign-in.md AC4), following the register rules: 1-50
 * characters after trimming, unique ignoring letter case and accents.
 * <ul>
 * <li>The Google name, trimmed; when empty, the part of the email before {@code @}.</li>
 * <li>Cut to 50 characters (UTF-16, as validated on change) without splitting a surrogate pair, then trimmed.</li>
 * <li>If taken, {@code " 2"}, {@code " 3"}, ... is appended, cutting the name further so the result stays within 50
 * characters.</li>
 * </ul>
 */
final class GoogleDisplayNames {

    static final int MAX_LENGTH = 50;
    /** Suffixes tried before giving up (the sign-in then fails and can be retried). */
    static final int MAX_SUFFIX = 1000;
    private static final String FALLBACK = "User";

    private GoogleDisplayNames() {
    }

    /**
     * The first free name. {@code taken} answers whether another account already has a name (the database compares
     * ignoring letter case and accents).
     *
     * @throws IllegalStateException if the name and every suffix up to {@value #MAX_SUFFIX} are taken
     */
    static String generate(String googleName, String email, Predicate<String> taken) {
        String base = base(googleName, email);
        if (!taken.test(base)) {
            return base;
        }
        for (int n = 2; n <= MAX_SUFFIX; n++) {
            String candidate = withSuffix(base, n);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free display name");
    }

    /** The name before any suffix: never empty, at most {@value #MAX_LENGTH} characters, trimmed. */
    static String base(String googleName, String email) {
        String name = clean(googleName);
        if (name.isEmpty() && email != null) {
            int at = email.indexOf('@');
            name = clean(at < 0 ? email : email.substring(0, at));
        }
        if (name.isEmpty()) {
            name = FALLBACK;
        }
        return cut(name, MAX_LENGTH);
    }

    static String withSuffix(String base, int n) {
        String suffix = " " + n;
        return cut(base, MAX_LENGTH - suffix.length()) + suffix;
    }

    /** At most {@code max} UTF-16 characters, never ending in half a surrogate pair, trimmed. */
    static String cut(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        String cut = clean(value.substring(0, end));
        return cut.isEmpty() ? FALLBACK : cut;
    }

    /** Trimmed like the register form ({@code String.trim()}), and of Unicode spaces (e.g. no-break space) too. */
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return c <= ' ' || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
