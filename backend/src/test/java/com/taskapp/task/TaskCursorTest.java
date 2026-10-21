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

    private static final byte[] FINGERPRINT =
            TaskListQuery.of(null, "all", "title", "Work", "milk", null).fingerprint(3L);
    private static final Instant CREATED = Instant.parse("2026-10-08T09:15:02.123456Z");

    private static TaskCursor cursor(TaskSort sort, String title) {
        if (sort == TaskSort.MANUAL) {
            return TaskCursor.manual(true, 123_456_789L);
        }
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
        byte[] otherFingerprint = TaskListQuery.of(null, "all", "title", "Work", "milky", null).fingerprint(3L);

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

        // A well-formed cursor of another version (1 = issued by 0.11.0).
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        byte[] otherVersion = Arrays.copyOf(raw, raw.length);
        otherVersion[0] = 1;
        assertThat(TaskCursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(otherVersion),
                TaskSort.TITLE, FINGERPRINT)).isEmpty();
    }

    /** 0.12.0: the resolved list is part of the fingerprint, whatever the raw {@code list} value was. */
    @Test
    void fingerprintIncludesTheResolvedListOnly() {
        TaskListQuery query = TaskListQuery.of(null, "all", "title", "Work", "milk", null);

        assertThat(query.fingerprint(4L)).isNotEqualTo(query.fingerprint(3L));
        assertThat(TaskListQuery.of("3", "all", "title", "Work", "milk", null).fingerprint(3L))
                .isEqualTo(query.fingerprint(3L));
        String encoded = cursor(TaskSort.TITLE, "Buy milk").encode(TaskSort.TITLE, query.fingerprint(3L));
        assertThat(TaskCursor.decode(encoded, TaskSort.TITLE, query.fingerprint(4L))).isEmpty();
    }

    /** 0.18.0 writes version 4; older cursors are rejected once (every sort), see otherRequestsAndFormatsAreRejected. */
    @ParameterizedTest
    @EnumSource(TaskSort.class)
    void cursorsAreVersion4AndAVersion3CursorIsRejected(TaskSort sort) {
        String encoded = cursor(sort, "Buy milk").encode(sort, FINGERPRINT);
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        assertThat(raw[0]).isEqualTo((byte) 4);

        // Same bytes with version 3 and a valid checksum: well-formed, but from 0.14.0 - 0.17.0.
        byte[] v3 = Arrays.copyOf(raw, raw.length);
        v3[0] = 3;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(v3, 0, v3.length - 4);
        java.nio.ByteBuffer.wrap(v3).putInt(v3.length - 4, (int) crc.getValue());
        assertThat(TaskCursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(v3), sort, FINGERPRINT))
                .isEmpty();
    }

    /** The manual cursor is the group and the anchor id only (0.18.0): nothing else of the task is in it. */
    @Test
    void manualCursorHoldsOnlyTheGroupAndTheAnchorId() {
        TaskCursor manual = TaskCursor.manual(false, 42L);
        String encoded = manual.encode(TaskSort.MANUAL, FINGERPRINT);

        assertThat(TaskCursor.decode(encoded, TaskSort.MANUAL, FINGERPRINT)).contains(manual);
        assertThat(TaskCursor.decode(encoded, TaskSort.NEWEST, FINGERPRINT)).isEmpty();
        // version + sort + 8 fingerprint + done + id + 4 checksum
        assertThat(Base64.getUrlDecoder().decode(encoded)).hasSize(1 + 1 + 8 + 1 + 8 + 4);
    }

    @Test
    void fingerprintFollowsTheRequestNormalising() {
        byte[] base = TaskListQuery.of(null, null, null, "Deep Work", "milk", null).fingerprint(3L);

        assertThat(TaskListQuery.of(null, "all", "newest", " deep   WÖRK ", "  milk ", "c").fingerprint(3L)).isEqualTo(base);
        assertThat(TaskListQuery.of(null, "foo", "Due", "Deep Work", "milk", null).fingerprint(3L)).isEqualTo(base);
        assertThat(TaskListQuery.of(null, "active", null, "Deep Work", "milk", null).fingerprint(3L)).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, "due", "Deep Work", "milk", null).fingerprint(3L)).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, null, "Work", "milk", null).fingerprint(3L)).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, null, "Deep Work", "MILK", null).fingerprint(3L)).isNotEqualTo(base);
        assertThat(TaskListQuery.of(null, null, null, "  ", "milk", null).fingerprint(3L))
                .isEqualTo(TaskListQuery.of(null, null, null, null, "milk", null).fingerprint(3L));
    }

    @org.junit.jupiter.api.Test
    void dueCursorCarriesTheDueTime() {
        TaskCursor timed = new TaskCursor(false, LocalDate.parse("2026-10-21"), java.time.LocalTime.of(9, 30), null,
                null, CREATED, 7L);
        assertThat(TaskCursor.decode(timed.encode(TaskSort.DUE, FINGERPRINT), TaskSort.DUE, FINGERPRINT))
                .contains(timed);
        TaskCursor dateOnly = new TaskCursor(false, LocalDate.parse("2026-10-21"), null, null, null, CREATED, 8L);
        assertThat(TaskCursor.decode(dateOnly.encode(TaskSort.DUE, FINGERPRINT), TaskSort.DUE, FINGERPRINT))
                .contains(dateOnly);
        TaskCursor noDate = new TaskCursor(true, null, null, null, null, CREATED, 9L);
        assertThat(TaskCursor.decode(noDate.encode(TaskSort.DUE, FINGERPRINT), TaskSort.DUE, FINGERPRINT))
                .contains(noDate);
    }
}
