package com.taskapp.security;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.event.Level;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.rememberme.InvalidCookieException;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationException;

import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Persistent, rotating remember-me logins with hashed storage (specs/sessions.md AC2-AC6, AC12).
 * <p>
 * Cookie {@value #COOKIE_NAME} = {@code <series>.<secret>}, both 32 random bytes as base64url. The database keeps
 * only SHA-256 hashes of both ({@link RememberMeTokenStore}). On every automatic sign-in the secret is rotated with
 * an atomic compare-and-set; the expiry is never moved, and the re-sent cookie's Max-Age is the time left.
 * <ul>
 * <li>Previous secret within {@link #GRACE} of the rotation (parallel requests that left with the same cookie):
 * accepted, not rotated again.</li>
 * <li>Any other wrong secret for a known series (an old cookie used again, i.e. stolen): every remember-me login
 * and session of that user is ended, logged at WARN.</li>
 * <li>Malformed, unknown, expired, or the user's account is disabled (that login is deleted, and the 401 says
 * {@code ACCOUNT_DISABLED}): the cookie is expired on the response and the request continues anonymous.
 * Never an exception (no 500), unlike Spring's {@code CookieTheftException} path.</li>
 * </ul>
 * The store and the user lookup are resolved lazily, so MockMvc slices without a database still start.
 */
public class HashedRememberMeServices extends AbstractRememberMeServices {

    public static final String COOKIE_NAME = "remember-me";
    static final String COOKIE_PATH = "/api";
    /** How long the previous secret is still accepted after a rotation. */
    static final Duration GRACE = Duration.ofSeconds(60);

    private static final int RANDOM_BYTES = 32;
    /** base64url without padding of 32 bytes. */
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final Supplier<RememberMeTokenStore> store;
    private final Supplier<SessionUserLookup> users;
    private final Clock clock;
    private final Duration length;
    private final boolean requireHttps;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param key random per startup: it only ties the {@link RememberMeAuthenticationToken} to the provider, it signs
     *            nothing that outlives the process
     */
    public HashedRememberMeServices(String key, Supplier<RememberMeTokenStore> store,
            Supplier<SessionUserLookup> users, Clock clock, Duration length, boolean requireHttps) {
        super(key, username -> {
            throw new UsernameNotFoundException("Not used: users are loaded by id");
        });
        this.store = store;
        this.users = users;
        this.clock = clock;
        this.length = length;
        this.requireHttps = requireHttps;
        setCookieName(COOKIE_NAME);
        // Our principal is not a real UserDetails; account status is checked by the user lookup instead.
        setUserDetailsChecker(user -> { });
    }

    // ---- automatic sign-in (RememberMeAuthenticationFilter) ----

    /** The whole cookie value is parsed in {@link #processAutoLoginCookie}, which can log the reason. */
    @Override
    protected String[] decodeCookie(String cookieValue) {
        return new String[] {cookieValue};
    }

    @Override
    protected UserDetails processAutoLoginCookie(String[] cookieTokens, HttpServletRequest request,
            HttpServletResponse response) {
        Instant now = now();
        CookieValue cookie = CookieValue.parse(cookieTokens[0]);
        if (cookie == null) {
            throw reject(request, "malformed", null, Level.INFO);
        }
        RememberMeTokenStore tokens = store.get();
        RememberMeToken row = tokens.find(cookie.seriesHash()).orElse(null);
        if (row == null) {
            throw reject(request, "unknown", null, Level.INFO);
        }
        SessionUser user = users.get().findById(row.userId()).orElse(null);
        if (user == null) {
            tokens.delete(row.seriesHash());
            throw reject(request, "user_gone", null, Level.INFO);
        }
        String email = user.user().email();
        if (user.disabled()) {
            // Disabling deletes the account's logins; this one survived (e.g. disabled in the database by hand).
            tokens.delete(row.seriesHash());
            request.setAttribute(ProblemAuthenticationEntryPoint.ACCOUNT_DISABLED, Boolean.TRUE);
            throw reject(request, "account_disabled", email, Level.WARN);
        }
        if (!now.isBefore(row.expiresAt())) {
            tokens.delete(row.seriesHash());
            throw reject(request, "expired", email, Level.INFO);
        }

        if (MessageDigest.isEqual(cookie.tokenHash(), row.tokenHash())) {
            byte[] secret = randomBytes();
            if (tokens.rotate(row.seriesHash(), row.tokenHash(), sha256(secret), now)) {
                writeCookie(cookie.series() + "." + B64.encodeToString(secret), secondsLeft(row.expiresAt(), now),
                        request, response);
                return remembered(user, row);
            }
            // A parallel request with the same cookie rotated it a moment ago: judge against the new state.
            row = tokens.find(cookie.seriesHash()).orElse(null);
            if (row == null) {
                throw reject(request, "unknown", email, Level.INFO);
            }
        }
        if (isPreviousWithinGrace(row, cookie.tokenHash(), now)) {
            return remembered(user, row);
        }
        tokens.endAllForUser(row.userId(), now);
        throw reject(request, "reused", email, Level.WARN);
    }

    @Override
    protected Authentication createSuccessfulAuthentication(HttpServletRequest request, UserDetails user) {
        RememberedUser remembered = (RememberedUser) user;
        RememberMeAuthenticationToken authentication =
                new RememberMeAuthenticationToken(getKey(), remembered.principal(), remembered.getAuthorities());
        authentication.setDetails(remembered.login());
        return authentication;
    }

    /** Not used: logins with "remember me" call {@link #createLogin} explicitly (JSON body, not a parameter). */
    @Override
    protected void onLoginSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication successfulAuthentication) {
    }

    /** Expires the cookie with the same attributes it was set with. */
    @Override
    protected void cancelCookie(HttpServletRequest request, HttpServletResponse response) {
        writeCookie("", 0, request, response);
    }

    // ---- used by SessionAuthenticator (login, register, logout) ----

    /** Creates a new remember-me login for this device and sets the cookie (Max-Age = the full length). */
    public RememberMeLogin createLogin(long userId, HttpServletRequest request, HttpServletResponse response) {
        Instant now = now();
        byte[] series = randomBytes();
        byte[] secret = randomBytes();
        Instant expiresAt = now.plus(length);
        byte[] seriesHash = sha256(series);
        store.get().create(new RememberMeToken(seriesHash, userId, sha256(secret), null, null, expiresAt), now);
        writeCookie(B64.encodeToString(series) + "." + B64.encodeToString(secret), length.getSeconds(), request,
                response);
        return new RememberMeLogin(B64.encodeToString(seriesHash), expiresAt);
    }

    /**
     * Ends THIS device's remember-me login: the one the request's cookie belongs to (current or previous secret) and
     * the one the session was signed in with ({@code sessionSeries}, may be null). Other devices are not touched.
     * Never sets a cookie.
     */
    public void endThisDevicesLogin(HttpServletRequest request, String sessionSeries) {
        String value = extractRememberMeCookie(request);
        CookieValue cookie = value == null ? null : CookieValue.parse(value);
        if (cookie != null) {
            store.get().find(cookie.seriesHash())
                    .filter(row -> MessageDigest.isEqual(cookie.tokenHash(), row.tokenHash())
                            || (row.previousTokenHash() != null
                                    && MessageDigest.isEqual(cookie.tokenHash(), row.previousTokenHash())))
                    .ifPresent(row -> store.get().delete(row.seriesHash()));
        }
        byte[] seriesHash = decodeSeriesId(sessionSeries);
        if (seriesHash != null) {
            store.get().delete(seriesHash);
        }
    }

    /** Whether the request carries a {@value #COOKIE_NAME} cookie (any value). */
    public boolean hasCookie(HttpServletRequest request) {
        return extractRememberMeCookie(request) != null;
    }

    /** Sets {@code remember-me=; Max-Age=0}. */
    public void expireCookie(HttpServletRequest request, HttpServletResponse response) {
        cancelCookie(request, response);
    }

    /** The series hash for a {@link SessionAttributes#REMEMBER_ME_SERIES} value, or null if absent or invalid. */
    public static byte[] decodeSeriesId(String seriesId) {
        if (seriesId == null || !PART.matcher(seriesId).matches()) {
            return null;
        }
        return B64_DECODER.decode(seriesId);
    }

    // ---- helpers ----

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean isPreviousWithinGrace(RememberMeToken row, byte[] tokenHash, Instant now) {
        return row.previousTokenHash() != null && row.rotatedAt() != null
                && MessageDigest.isEqual(tokenHash, row.previousTokenHash())
                && now.isBefore(row.rotatedAt().plus(GRACE));
    }

    private static RememberedUser remembered(SessionUser user, RememberMeToken row) {
        return new RememberedUser(user.user(),
                new RememberMeLogin(B64.encodeToString(row.seriesHash()), row.expiresAt()), user.authorities());
    }

    private static long secondsLeft(Instant expiresAt, Instant now) {
        return Math.max(1, Duration.between(now, expiresAt).getSeconds());
    }

    private RememberMeAuthenticationException reject(HttpServletRequest request, String reason, String email,
            Level level) {
        SecurityEventLogger.log(SecurityEvent.REMEMBER_ME_REJECTED, level, "reason", reason, request, email);
        return new InvalidCookieException("Remember-me cookie rejected: " + reason);
    }

    private void writeCookie(String value, long maxAgeSeconds, HttpServletRequest request,
            HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, value)
                .path(COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .httpOnly(true)
                .sameSite("Lax")
                // Same rule as the session and XSRF cookies.
                .secure(requireHttps || request.isSecure())
                .build()
                .toString());
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** A syntactically valid cookie value: the raw series (as sent) plus the hashes used for lookups. */
    private record CookieValue(String series, byte[] seriesHash, byte[] tokenHash) {

        static CookieValue parse(String value) {
            if (value.length() != 87 || value.charAt(43) != '.') {
                return null;
            }
            String series = value.substring(0, 43);
            String secret = value.substring(44);
            if (!PART.matcher(series).matches() || !PART.matcher(secret).matches()) {
                return null;
            }
            try {
                return new CookieValue(series, sha256(B64_DECODER.decode(series)),
                        sha256(B64_DECODER.decode(secret)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return "CookieValue[****]";
        }
    }

    /** Carries the principal and the login through {@link AbstractRememberMeServices#autoLogin}. */
    private record RememberedUser(AuthenticatedUser principal, RememberMeLogin login,
            List<GrantedAuthority> authorities) implements UserDetails {

        /** From the user's current role, loaded with the user. */
        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return authorities;
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public String getUsername() {
            return principal.email();
        }
    }

    /**
     * A device's remember-me login as held by its session.
     *
     * @param seriesId  base64url of the series hash ({@link SessionAttributes#REMEMBER_ME_SERIES}); not a secret by
     *                  itself, but never logged either
     * @param expiresAt end of the login, and of any session signed in with it
     */
    public record RememberMeLogin(String seriesId, Instant expiresAt) implements Serializable {
    }
}
