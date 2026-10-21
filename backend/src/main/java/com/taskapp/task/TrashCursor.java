package com.taskapp.task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Keyset position of {@code GET /api/trash} (contract 0.16.0): {@code deleted_at} (epoch microseconds) and id of the
 * last item of a page, plus a fingerprint of the {@code list} it was issued for. Base64url without padding of a
 * binary record with a CRC32, like {@link TaskCursor}. Its first byte is {@link #MARK}, which no {@code TaskCursor}
 * starts with, so a task-list cursor is rejected here and the reverse.
 */
public record TrashCursor(Instant deletedAt, long id) {

    static final int MAX_LENGTH = 128;
    private static final int MARK = 0x54;
    private static final int FINGERPRINT_LENGTH = 8;
    private static final int CHECKSUM_LENGTH = 4;
    private static final Pattern BASE64URL_NO_PADDING = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** What the cursor is bound to: the {@code list} filter ({@code null} = all my lists). */
    public static byte[] fingerprint(Long listId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("trash:" + (listId == null ? "all" : listId)).getBytes(StandardCharsets.UTF_8));
            return java.util.Arrays.copyOf(hash, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static TrashCursor after(Task task) {
        return new TrashCursor(task.getDeletedAt(), task.getId());
    }

    public String encode(byte[] fingerprint) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(MARK);
            out.write(fingerprint, 0, FINGERPRINT_LENGTH);
            out.writeLong(ChronoUnit.MICROS.between(Instant.EPOCH, deletedAt));
            out.writeLong(id);
        } catch (IOException e) {
            throw new IllegalStateException("Writing to memory cannot fail", e);
        }
        byte[] payload = bytes.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        return ENCODER.encodeToString(ByteBuffer.allocate(payload.length + CHECKSUM_LENGTH).put(payload)
                .putInt((int) crc.getValue()).array());
    }

    /** The position {@code raw} holds, if it is a well-formed trash cursor issued for {@code fingerprint}. */
    public static Optional<TrashCursor> decode(String raw, byte[] fingerprint) {
        if (raw == null || raw.length() > MAX_LENGTH || !BASE64URL_NO_PADDING.matcher(raw).matches()) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!ENCODER.encodeToString(bytes).equals(raw) || bytes.length <= CHECKSUM_LENGTH) {
            return Optional.empty();
        }
        int payloadLength = bytes.length - CHECKSUM_LENGTH;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, payloadLength);
        if (ByteBuffer.wrap(bytes, payloadLength, CHECKSUM_LENGTH).getInt() != (int) crc.getValue()) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadLength))) {
            if (in.readUnsignedByte() != MARK
                    || !MessageDigest.isEqual(in.readNBytes(FINGERPRINT_LENGTH), fingerprint)) {
                return Optional.empty();
            }
            Instant deletedAt = Instant.EPOCH.plus(in.readLong(), ChronoUnit.MICROS);
            long id = in.readLong();
            int year = deletedAt.atOffset(ZoneOffset.UTC).getYear();
            if (in.available() != 0 || year < 1 || year > 9999) {
                return Optional.empty();
            }
            return Optional.of(new TrashCursor(deletedAt, id));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
