package com.taskapp.task;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tag name rules from the contract ("Tags"). Whether two valid names are the same tag is decided by the database
 * (utf8mb4_0900_ai_ci on {@code tags.name}); {@link #key(String)} is only a close approximation used to count and
 * group names before the database is involved.
 */
public final class TagNames {

    public static final int MAX_LENGTH = 30;
    public static final int MAX_PER_TASK = 10;
    /** Different tags per list (0.12.0: was per user). */
    public static final int MAX_PER_LIST = 200;

    /** Combining diacritical marks (U+0300-U+036F): accents, which utf8mb4_0900_ai_ci ignores. */
    private static final Pattern DIACRITICS = Pattern.compile("[\\u0300-\\u036F]+");

    private TagNames() {
    }

    /**
     * Removes leading/trailing whitespace and collapses every inner run to one U+0020. Whitespace = Java
     * {@code Character.isWhitespace} or a Unicode space separator (so U+00A0 counts). {@code null} stays
     * {@code null}; a blank name becomes {@code ""}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (isSpace(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** A comma or a control character (Unicode category Cc) anywhere in the raw value. */
    public static boolean hasInvalidCharacters(String raw) {
        return raw != null && raw.codePoints().anyMatch(cp -> cp == ',' || Character.getType(cp) == Character.CONTROL);
    }

    /** {@code null} or blank after normalising. */
    public static boolean isBlank(String raw) {
        return raw == null || normalize(raw).isEmpty();
    }

    /** Longer than 30 UTF-16 code units after normalising. */
    public static boolean isTooLong(String raw) {
        return raw != null && normalize(raw).length() > MAX_LENGTH;
    }

    /** Every rule except the per-task and per-list counts. */
    public static boolean isValid(String raw) {
        return !isBlank(raw) && !hasInvalidCharacters(raw) && !isTooLong(raw);
    }

    /** Approximation of "equal ignoring case and accents" for a normalised name. */
    public static String key(String normalized) {
        String decomposed = Normalizer.normalize(normalized, Normalizer.Form.NFKD);
        return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** Number of different names (by {@link #key(String)}) among the non-blank elements. */
    public static int countDistinct(List<String> raw) {
        Set<String> keys = new LinkedHashSet<>();
        for (String name : raw) {
            if (!isBlank(name)) {
                keys.add(key(normalize(name)));
            }
        }
        return keys.size();
    }

    /** Normalised names, first spelling per {@link #key(String)}, in request order. Input must be valid. */
    public static List<String> distinctNormalized(List<String> raw) {
        Set<String> keys = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String name : raw) {
            String normalized = normalize(name);
            if (keys.add(key(normalized))) {
                names.add(normalized);
            }
        }
        return names;
    }

    private static boolean isSpace(int cp) {
        return Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }
}
