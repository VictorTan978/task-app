package com.taskapp.security.audit;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.taskapp.security.AuthenticatedUser;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Writes one line per security event to the dedicated logger {@value #LOGGER_NAME}, e.g.
 * <pre>SECURITY event=LOGIN_FAILURE reason=bad_password email=alice@example.com ip=203.0.113.7 path=/api/auth/login</pre>
 * Fields are always in this order; a missing value is {@code -}. Every value is escaped (see {@link #sanitize}),
 * so a value is always a single token without spaces and can't start a new log line.
 * <p>
 * Never pass passwords, hashes, session IDs, CSRF tokens or remember-me cookie values. Only the email, the client IP
 * ({@code request.getRemoteAddr()}, already resolved through trusted proxies) and the request path (without
 * query string or {@code ;} path parameters) are taken from the request.
 */
public final class SecurityEventLogger {

    public static final String LOGGER_NAME = "security.audit";
    public static final String PREFIX = "SECURITY";
    static final int MAX_VALUE_LENGTH = 256;
    private static final String NONE = "-";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private SecurityEventLogger() {
    }

    public static void log(SecurityEvent event, HttpServletRequest request, String email) {
        write(event, event.level(), null, null, email, request);
    }

    /** With one extra field after the event name, e.g. {@code reason=bad_password} or {@code limit=login-ip}. */
    public static void log(SecurityEvent event, String field, String value, HttpServletRequest request,
            String email) {
        write(event, event.level(), field, value, email, request);
    }

    /** Like {@link #log(SecurityEvent, String, String, HttpServletRequest, String)}, at a level other than the event's. */
    public static void log(SecurityEvent event, Level level, String field, String value, HttpServletRequest request,
            String email) {
        write(event, level, field, value, email, request);
    }

    /**
     * From a background job, with the origin captured on the request thread. {@code fields} are name/value pairs
     * written after the event name in this order, e.g. {@code "kind", "verify_email", "reason", "smtp"}.
     */
    public static void log(SecurityEvent event, AuditOrigin origin, String email, String... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("fields must be name/value pairs");
        }
        AuditOrigin from = origin == null ? AuditOrigin.NONE : origin;
        write(event, event.level(), fields, email, from.ip(), from.path());
    }

    /** Email of the signed-in user of the current request, or null when anonymous. */
    public static String currentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.email();
        }
        return null;
    }

    private static void write(SecurityEvent event, Level level, String field, String value, String email,
            HttpServletRequest request) {
        write(event, level, field == null ? new String[0] : new String[] {field, value}, email,
                request == null ? null : request.getRemoteAddr(), request == null ? null : path(request));
    }

    private static void write(SecurityEvent event, Level level, String[] fields, String email, String ip,
            String path) {
        StringBuilder line = new StringBuilder(160).append(PREFIX).append(" event=").append(event.name());
        for (int i = 0; i < fields.length; i += 2) {
            line.append(' ').append(fields[i]).append('=').append(sanitize(fields[i + 1]));
        }
        line.append(" email=").append(sanitize(email))
                .append(" ip=").append(sanitize(ip))
                .append(" path=").append(sanitize(path));
        log.atLevel(level).log(line.toString());
    }

    /** Request URI without {@code ;params} (e.g. a {@code ;jsessionid=...} in the URL). Never the query string. */
    static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? null : uri.replaceAll(";[^/]*", "");
    }

    /**
     * Makes a user-controlled value safe for a single key=value token: control characters (CR, LF, tab, ...,
     * C1), Unicode line/paragraph separators, invisible format characters (e.g. bidi overrides), spaces and
     * {@code %} itself are percent-encoded as UTF-8 bytes, so the original stays recoverable. Values longer
     * than {@value #MAX_VALUE_LENGTH} characters are cut and end with {@code ...}. Null or empty gives {@code -}.
     */
    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return NONE;
        }
        String input = value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value;
        StringBuilder out = new StringBuilder(input.length() + 8);
        input.codePoints().forEach(cp -> {
            if (needsEscape(cp)) {
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            } else {
                out.appendCodePoint(cp);
            }
        });
        if (input.length() < value.length()) {
            out.append("...");
        }
        return out.toString();
    }

    private static boolean needsEscape(int cp) {
        if (cp == '%' || Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE
                || type == Character.UNASSIGNED;
    }
}
