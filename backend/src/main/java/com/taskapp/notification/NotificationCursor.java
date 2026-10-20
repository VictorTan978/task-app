package com.taskapp.notification;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Keyset position of {@code GET /api/notifications}: the (createdAt, id) of the last item of a page. Wire format:
 * base64url without padding of version (1 byte), created_at in epoch microseconds, id, then a CRC32, so a damaged
 * cursor is rejected. Not signed: the query always filters by the session's user, so a forged cursor can only move
 * inside the caller's own notifications.
 */
record NotificationCursor(Instant createdAt, long id) {

    static final int MAX_LENGTH = 64;
    private static final int VERSION = 1;
    private static final Pattern BASE64URL_NO_PADDING = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    String encode() {
        ByteBuffer payload = ByteBuffer.allocate(1 + 8 + 8);
        payload.put((byte) VERSION).putLong(ChronoUnit.MICROS.between(Instant.EPOCH, createdAt)).putLong(id);
        byte[] bytes = payload.array();
        return ENCODER.encodeToString(ByteBuffer.allocate(bytes.length + 4).put(bytes).putInt(checksum(bytes)).array());
    }

    static Optional<NotificationCursor> decode(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH || !BASE64URL_NO_PADDING.matcher(raw).matches()) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (bytes.length != 1 + 8 + 8 + 4 || !ENCODER.encodeToString(bytes).equals(raw)) {
            return Optional.empty();
        }
        byte[] payload = java.util.Arrays.copyOf(bytes, 17);
        if (ByteBuffer.wrap(bytes, 17, 4).getInt() != checksum(payload) || payload[0] != VERSION) {
            return Optional.empty();
        }
        ByteBuffer in = ByteBuffer.wrap(payload, 1, 16);
        long micros = in.getLong();
        long id = in.getLong();
        try {
            Instant createdAt = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
            // Years MySQL DATETIME can hold, so a forged value never reaches the database as an error.
            if (createdAt.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                    || createdAt.isAfter(Instant.parse("9999-12-31T00:00:00Z"))) {
                return Optional.empty();
            }
            return Optional.of(new NotificationCursor(createdAt, id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, 17);
        return (int) crc.getValue();
    }
}
