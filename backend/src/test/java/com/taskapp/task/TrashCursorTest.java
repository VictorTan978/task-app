package com.taskapp.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrashCursorTest {

    private static final Instant AT = Instant.parse("2026-10-19T08:00:00.123456Z");

    @Test
    void roundTripsTheKeysetPosition() {
        byte[] fingerprint = TrashCursor.fingerprint(null);
        String encoded = new TrashCursor(AT, 42L).encode(fingerprint);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(TrashCursor.decode(encoded, fingerprint)).contains(new TrashCursor(AT, 42L));
    }

    @Test
    void isBoundToTheListItWasIssuedFor() {
        String encoded = new TrashCursor(AT, 42L).encode(TrashCursor.fingerprint(8L));

        assertThat(TrashCursor.decode(encoded, TrashCursor.fingerprint(8L))).isPresent();
        assertThat(TrashCursor.decode(encoded, TrashCursor.fingerprint(9L))).isEmpty();
        assertThat(TrashCursor.decode(encoded, TrashCursor.fingerprint(null))).isEmpty();
    }

    @Test
    void aTaskListCursorIsNotATrashCursor() {
        byte[] fingerprint = TrashCursor.fingerprint(null);
        String taskCursor = new TaskCursor(false, null, null, null, null, AT, 42L)
                .encode(TaskSort.NEWEST, fingerprint);

        assertThat(TrashCursor.decode(taskCursor, fingerprint)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "!!!", "AAAA", "dHJhc2g", "not a cursor", "====="})
    void rejectsGarbage(String raw) {
        assertThat(TrashCursor.decode(raw, TrashCursor.fingerprint(null))).isEmpty();
    }

    @Test
    void rejectsADamagedTruncatedOrPaddedCursor() {
        byte[] fingerprint = TrashCursor.fingerprint(null);
        String encoded = new TrashCursor(AT, 42L).encode(fingerprint);
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);

        bytes[10] ^= 1;
        assertThat(TrashCursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), fingerprint))
                .isEmpty();
        assertThat(TrashCursor.decode(encoded.substring(0, encoded.length() - 4), fingerprint)).isEmpty();
        assertThat(TrashCursor.decode(encoded + "=", fingerprint)).isEmpty();
        assertThat(TrashCursor.decode("A".repeat(TrashCursor.MAX_LENGTH + 1), fingerprint)).isEmpty();
        assertThat(TrashCursor.decode(null, fingerprint)).isEmpty();
    }
}
