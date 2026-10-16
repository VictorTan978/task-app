package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TaskCursorTest {

    private static final byte[] FINGERPRINT = TaskListQuery.of("all", "title", "Work", "milk", null).fingerprint();
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");

    private static TaskCursor cursor(TaskSort sort, String title) {
        return new TaskCursor(true,
                sort == TaskSort.DUE ? LocalDate.parse("2026-10-10") : null,
                sort == TaskSort.PRIORITY ? Priority.MEDIUM : null,
                sort == TaskSort.TITLE ? title : null,
                CREATED, 123_456_789L);
    }

    @ParameterizedTest
    @EnumSource(TaskSort.class)
    void roundTripsEverySortAsBase64UrlWithoutPadding(TaskSort sort) {
        TaskCursor cursor = cursor(sort, "Café ☕ 😀");
        String encoded = cursor.encode(sort, FINGERPRINT);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(TaskCursor.decode(encoded, sort, FINGERPRINT)).contains(cursor);
    }

    @Test
    void dueCursorWithoutDateRoundTrips() {
        TaskCursor noDate = new TaskCursor(false, null, null, null, CREATED, 1L);
        assertThat(TaskCursor.decode(noDate.encode(TaskSort.DUE, FINGERPRINT), TaskSort.DUE, FINGERPRINT))
                .contains(noDate);
    }

    @Test
    void longestTitleFitsIn1024Characters() {
        TaskCursor longest = cursor(TaskSort.TITLE, "語".repeat(200));
        String encoded = longest.encode(TaskSort.TITLE, FINGERPRINT);

        assertThat(encoded.length()).isLessThanOrEqualTo(TaskCursor.MAX_LENGTH);
        assertThat(TaskCursor.decode(encoded, TaskSort.TITLE, FINGERPRINT)).contains(longest);
    }

    @Test
    void anyChangedCharacterIsRejected() {
        String encoded = cursor(TaskSort.TITLE, "Buy milk").encode(TaskSort.TITLE, FINGERPRINT);
        for (int i = 0; i < encoded.length(); i++) {
            char[] chars = encoded.toCharArray();
            chars[i] = chars[i] == 'A' ? 'B' : 'A';
            assertThat(TaskCursor.decode(new String(chars), TaskSort.TITLE, FINGERPRINT)).as("position " + i).isEmpty();
            assertThat(TaskCursor.decode(encoded.substring(0, i), TaskSort.TITLE, FINGERPRINT)).as("cut " + i).isEmpty();
        }
    }

    @Test
    void otherRequestsAndFormatsAreRejected() {
        String encoded = cursor(TaskSort.TITLE, "Buy milk").encode(TaskSort.TITLE, FINGERPRINT);
        byte[] otherFingerprint = TaskListQuery.of("all", "title", "Work", "milky", null).fingerprint();

        assertThat(TaskCursor.decode(encoded, TaskSort.TITLE, otherFingerprint)).isEmpty();
        assertThat(TaskCursor.decode(encoded, TaskSort.NEWEST, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode(encoded + "=", TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode(encoded + "AA", TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode(encoded.substring(1) + "+", TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode(encoded.substring(1) + "/", TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode("", TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode(null, TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode("A".repeat(1025), TaskSort.TITLE, FINGERPRINT)).isEmpty();
        assertThat(TaskCursor.decode("eyJ2IjoxfQ", TaskSort.TITLE, FINGERPRINT)).isEmpty();

        // A well-formed cursor of another version.
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        byte[] otherVersion = Arrays.copyOf(raw, raw.length);
        otherVersion[0] = 2;
        assertThat(TaskCursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(otherVersion),
                TaskSort.TITLE, FINGERPRINT)).isEmpty();
    }

    @Test
    void fingerprintFollowsTheRequestNormalising() {
        byte[] base = TaskListQuery.of(null, null, "Deep Work", "milk", null).fingerprint();

        assertThat(TaskListQuery.of("all", "newest", " deep   WÖRK ", "  milk ", "c").fingerprint()).isEqualTo(base);
        assertThat(TaskListQuery.of("foo", "Due", "Deep Work", "milk", null).fingerprint()).isEqualTo(base);
        assertThat(TaskListQuery.of("active", null, "Deep Work", "milk", null).fingerprint()).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, "due", "Deep Work", "milk", null).fingerprint()).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, "Work", "milk", null).fingerprint()).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, "Deep Work", "MILK", null).fingerprint()).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, "  ", "milk", null).fingerprint())
                .isEqualTo(TaskListQuery.of(null, null, null, "milk", null).fingerprint());
    }
}
