package com.taskapp.task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Keyset position of {@code GET /api/tasks}: the sort-key values of the last task of a page (contract 0.11.0
 * {@code nextCursor}). Self-contained, so the next page is exact even if that task was edited or deleted meanwhile.
 * Only the fields the sort uses are set ({@code done} always: it is the group of filter {@code all}).
 * <p>
 * Wire format: base64url WITHOUT padding (only {@code A-Z a-z 0-9 - _}) of a binary record: version, sort, the
 * {@link TaskListQuery#fingerprint(long) fingerprint} of the request it was issued for, done, the sort's own key
 * (due date, priority or title), created_at in epoch microseconds, id; then a CRC32 of all that, so a damaged
 * cursor is rejected. Not signed: the list is always checked against the session's user first, so a forged cursor
 * can only move inside one of the caller's own lists. At most 1024 characters (the longest, a 200-character title of 3-byte UTF-8 characters,
 * is about 845).
 */
public record TaskCursor(boolean done, LocalDate dueDate, Priority priority, String title, Instant createdAt,
        long id) {

    public static final int MAX_LENGTH = 1024;
    static final int FINGERPRINT_LENGTH = 8;

    /** 2 since 0.12.0: the fingerprint includes the list, so every version 1 cursor is rejected once. */
    private static final int VERSION = 2;
    private static final int CHECKSUM_LENGTH = 4;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final Pattern BASE64URL_NO_PADDING = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** The position right after {@code task} in {@code sort} order. */
    public static TaskCursor after(Task task, TaskSort sort) {
        return new TaskCursor(task.isDone(),
                sort == TaskSort.DUE ? task.getDueDate() : null,
                sort == TaskSort.PRIORITY ? task.getPriority() : null,
                sort == TaskSort.TITLE ? task.getTitle() : null,
                task.getCreatedAt(), task.getId());
    }

    public String encode(TaskSort sort, byte[] fingerprint) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(sort.ordinal());
            out.write(fingerprint, 0, FINGERPRINT_LENGTH);
            out.writeBoolean(done);
            switch (sort) {
                case DUE -> {
                    out.writeBoolean(dueDate != null);
                    if (dueDate != null) {
                        out.writeLong(dueDate.toEpochDay());
                    }
                }
                case PRIORITY -> out.writeByte(priority.ordinal());
                case TITLE -> {
                    byte[] utf8 = title.getBytes(StandardCharsets.UTF_8);
                    out.writeShort(utf8.length);
                    out.write(utf8);
                }
                case NEWEST, OLDEST -> {
                }
            }
            out.writeLong(ChronoUnit.MICROS.between(Instant.EPOCH, createdAt));
            out.writeLong(id);
        } catch (IOException e) {
            throw new IllegalStateException("Writing to memory cannot fail", e);
        }
        byte[] payload = bytes.toByteArray();
        return ENCODER.encodeToString(ByteBuffer.allocate(payload.length + CHECKSUM_LENGTH)
                .put(payload).putInt(checksum(payload, payload.length)).array());
    }

    /**
     * The position {@code raw} holds, if it is a well-formed cursor of this version issued for {@code sort} and
     * {@code fingerprint}. Empty for anything else (altered, truncated, padded, too long, other request).
     */
    public static Optional<TaskCursor> decode(String raw, TaskSort sort, byte[] fingerprint) {
        if (raw == null || raw.length() > MAX_LENGTH || !BASE64URL_NO_PADDING.matcher(raw).matches()) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = DECODER.decode(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // Only the exact string we issue: rejects other encodings of the same bytes (unused trailing bits).
        if (!ENCODER.encodeToString(bytes).equals(raw) || bytes.length <= CHECKSUM_LENGTH) {
            return Optional.empty();
        }
        int payloadLength = bytes.length - CHECKSUM_LENGTH;
        if (ByteBuffer.wrap(bytes, payloadLength, CHECKSUM_LENGTH).getInt() != checksum(bytes, payloadLength)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payloadLength))) {
            if (in.readUnsignedByte() != VERSION || in.readUnsignedByte() != sort.ordinal()) {
                return Optional.empty();
            }
            byte[] issuedFor = in.readNBytes(FINGERPRINT_LENGTH);
            if (!MessageDigest.isEqual(issuedFor, fingerprint)) {
                return Optional.empty();
            }
            boolean done = readBoolean(in);
            LocalDate due = null;
            Priority priority = null;
            String title = null;
            switch (sort) {
                case DUE -> {
                    if (readBoolean(in)) {
                        due = LocalDate.ofEpochDay(in.readLong());
                    }
                }
                case PRIORITY -> {
                    int ordinal = in.readUnsignedByte();
                    if (ordinal >= Priority.values().length) {
                        return Optional.empty();
                    }
                    priority = Priority.values()[ordinal];
                }
                case TITLE -> {
                    title = strictUtf8(in.readNBytes(in.readUnsignedShort()));
                    if (title == null || title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
                        return Optional.empty();
                    }
                }
                case NEWEST, OLDEST -> {
                }
            }
            Instant createdAt = Instant.EPOCH.plus(in.readLong(), ChronoUnit.MICROS);
            long id = in.readLong();
            if (in.available() != 0 || !inSqlRange(due) || !inSqlRange(LocalDate.ofInstant(createdAt, ZoneOffset.UTC))) {
                return Optional.empty();
            }
            return Optional.of(new TaskCursor(done, due, priority, title, createdAt, id));
        } catch (IOException | RuntimeException e) {
            // EOF (truncated), a date or instant out of range, ...
            return Optional.empty();
        }
    }

    /** CRC32 of the payload: catches accidental damage (a changed or lost character). Not a signature. */
    private static int checksum(byte[] bytes, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }

    /** Years MySQL DATE/DATETIME can hold, so a forged value never reaches the database as an error. */
    private static boolean inSqlRange(LocalDate date) {
        return date == null || (date.getYear() >= 1 && date.getYear() <= 9999);
    }

    /** {@code readBoolean} accepts any non-zero byte; only the 0/1 we write is valid. */
    private static boolean readBoolean(DataInputStream in) throws IOException {
        int b = in.readUnsignedByte();
        if (b > 1) {
            throw new IOException("Not a boolean");
        }
        return b == 1;
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
