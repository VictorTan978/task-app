package com.taskapp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NotificationCursorTest {

    private static final NotificationCursor CURSOR = new NotificationCursor(
            Instant.parse("2026-10-21T06:30:01.123456Z"), 41L);

    @Test
    void roundTripsAsBase64UrlWithoutPadding() {
        String encoded = CURSOR.encode();
        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(NotificationCursor.decode(encoded)).contains(CURSOR);
    }

    @Test
    void aDamagedAlteredOrPaddedCursorIsRejected() {
        String encoded = CURSOR.encode();
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        raw[3] ^= 1;
        assertThat(NotificationCursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(raw))).isEmpty();
        assertThat(NotificationCursor.decode(encoded + "A")).isEmpty();
        assertThat(NotificationCursor.decode(encoded.substring(1))).isEmpty();
        assertThat(NotificationCursor.decode(Base64.getUrlEncoder().encodeToString(Base64.getUrlDecoder().decode(encoded))
                + "=")).isEmpty();
    }

    @Test
    void aCursorOfAnotherVersionIsRejected() {
        byte[] raw = Base64.getUrlDecoder().decode(CURSOR.encode());
        raw[0] = 2;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(raw, 0, 17);
        java.nio.ByteBuffer.wrap(raw, 17, 4).putInt((int) crc.getValue());
        assertThat(NotificationCursor.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(raw))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "AAAA", "not base64!", "%%%"})
    void nonsenseIsRejected(String raw) {
        assertThat(NotificationCursor.decode(raw)).isEmpty();
    }

    @Test
    void nullAndOverlongAreRejected() {
        assertThat(NotificationCursor.decode(null)).isEmpty();
        assertThat(NotificationCursor.decode("A".repeat(65))).isEmpty();
    }
}
