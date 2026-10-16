package com.taskapp.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The parameters of {@code GET /api/tasks} after the contract's normalising: unknown {@code filter}/{@code sort} are
 * already the defaults, {@code q} is trimmed ({@code ""} = no search). {@code tag} and {@code cursor} are the raw
 * first values (null when absent). Validated by {@link TaskService#list}.
 */
public record TaskListQuery(TaskFilter filter, TaskSort sort, String tag, String q, String cursor) {

    /** {@code q} at most this many UTF-16 code units after trimming. */
    public static final int MAX_SEARCH_LENGTH = 100;

    public TaskListQuery {
        q = q == null ? "" : q;
    }

    public static TaskListQuery of(String filter, String sort, String tag, String q, String cursor) {
        return new TaskListQuery(TaskFilter.parse(filter), TaskSort.parse(sort), tag, trim(q), cursor);
    }

    /** The search text, or null for no search. */
    public String search() {
        return q.isEmpty() ? null : q;
    }

    /** Whether a cursor was sent ({@code ?cursor=} empty counts as none: first page). */
    public boolean hasCursor() {
        return cursor != null && !cursor.isEmpty();
    }

    /**
     * 8 bytes identifying what the list shows (filter, sort, tag compared ignoring case/accents/spacing like the tag
     * lookup, trimmed q). A cursor only fits a request with the same fingerprint.
     */
    public byte[] fingerprint() {
        String tagKey = TagNames.isBlank(tag) ? "" : TagNames.key(TagNames.normalize(tag));
        String canonical = filter.name() + '\n' + sort.name() + '\n' + tagKey + '\n' + q;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, TaskCursor.FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /**
     * Removes leading and trailing whitespace like JavaScript's {@code String.prototype.trim} (the frontend counts
     * the length the same way): Java whitespace, Unicode space separators (U+00A0 included) and U+FEFF.
     */
    static String trim(String raw) {
        if (raw == null) {
            return "";
        }
        int start = 0;
        int end = raw.length();
        while (start < end && isSpace(raw.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(raw.charAt(end - 1))) {
            end--;
        }
        return raw.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.getType(c) == Character.SPACE_SEPARATOR || c == '﻿';
    }
}
