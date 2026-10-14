package com.taskapp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.Authentication;

import com.taskapp.security.HashedRememberMeServices.RememberMeLogin;
import com.taskapp.security.audit.SecurityLogCapture;
import com.taskapp.security.ratelimit.MutableClock;

import jakarta.servlet.http.Cookie;

/** Remember-me logic (specs/sessions.md AC2, AC4, AC5, AC6, AC12, AC14) against an in-memory store. */
class HashedRememberMeServicesTest {

    private static final Instant T0 = Instant.parse("2026-10-13T08:00:00Z");
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);
    private static final AuthenticatedUser BOB = new AuthenticatedUser(1L, "bob@example.com", "Bob");
    private static final AuthenticatedUser ALICE = new AuthenticatedUser(2L, "alice@example.com", "Alice");

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryStore store = new InMemoryStore();
    private final SessionUserLookup users = id -> Optional.ofNullable(
            id == 1L ? new SessionUser(BOB, null) : id == 2L ? new SessionUser(ALICE, null) : null);
    private final HashedRememberMeServices services =
            new HashedRememberMeServices("test-key", () -> store, () -> users, clock, THIRTY_DAYS, false);
    private final SecurityLogCapture capture = new SecurityLogCapture().start();

    @AfterEach
    void stop() {
        capture.stop();
    }

    // --- helpers ---

    private String login(long userId) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        services.createLogin(userId, new MockHttpServletRequest(), response);
        return cookieValue(response);
    }

    private record Result(Authentication authentication, MockHttpServletResponse response) {
        String setCookie() {
            List<String> headers = response.getHeaders("Set-Cookie");
            assertThat(headers).hasSizeLessThanOrEqualTo(1);
            return headers.isEmpty() ? null : headers.get(0);
        }
    }

    private Result autoLogin(String cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setCookies(new Cookie("remember-me", cookie));
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new Result(services.autoLogin(request, response), response);
    }

    private static String cookieValue(MockHttpServletResponse response) {
        String header = response.getHeader("Set-Cookie");
        return header.substring("remember-me=".length(), header.indexOf(';'));
    }

    private static byte[] sha256(byte[] bytes) {
        return HashedRememberMeServices.sha256(bytes);
    }

    private static long maxAge(String setCookie) {
        int start = setCookie.indexOf("Max-Age=") + "Max-Age=".length();
        int end = setCookie.indexOf(';', start);
        return Long.parseLong(setCookie.substring(start, end < 0 ? setCookie.length() : end));
    }

    // --- creating a login (AC12) ---

    @Test
    void createLoginSetsAnHttpOnlyLaxCookieOnApiForTheFullLengthAndStoresOnlyHashes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RememberMeLogin login = services.createLogin(1L, new MockHttpServletRequest(), response);

        String header = response.getHeader("Set-Cookie");
        assertThat(header).startsWith("remember-me=").contains("; Path=/api", "; Max-Age=2592000", "; HttpOnly",
                "; SameSite=Lax").doesNotContain("Secure", "Domain");
        String value = cookieValue(response);
        assertThat(value).matches("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}");
        byte[] series = Base64.getUrlDecoder().decode(value.substring(0, 43));
        byte[] secret = Base64.getUrlDecoder().decode(value.substring(44));

        RememberMeToken row = store.only();
        assertThat(row.seriesHash()).isEqualTo(sha256(series)).isNotEqualTo(series);
        assertThat(row.tokenHash()).isEqualTo(sha256(secret)).isNotEqualTo(secret);
        assertThat(row.previousTokenHash()).isNull();
        assertThat(row.rotatedAt()).isNull();
        assertThat(row.expiresAt()).isEqualTo(T0.plus(THIRTY_DAYS));
        assertThat(login.expiresAt()).isEqualTo(T0.plus(THIRTY_DAYS));
        assertThat(HashedRememberMeServices.decodeSeriesId(login.seriesId())).isEqualTo(row.seriesHash());
    }

    @Test
    void cookieIsSecureWhenHttpsIsRequiredOrTheRequestIsSecure() {
        HashedRememberMeServices https =
                new HashedRememberMeServices("k", () -> store, () -> users, clock, THIRTY_DAYS, true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        https.createLogin(1L, new MockHttpServletRequest(), response);
        assertThat(response.getHeader("Set-Cookie")).contains("; Secure");

        MockHttpServletRequest secureRequest = new MockHttpServletRequest();
        secureRequest.setSecure(true);
        MockHttpServletResponse expired = new MockHttpServletResponse();
        services.expireCookie(secureRequest, expired);
        assertThat(expired.getHeader("Set-Cookie")).startsWith("remember-me=;").contains("; Path=/api",
                "; Max-Age=0", "; Secure", "; HttpOnly", "; SameSite=Lax");
    }

    // --- automatic sign-in and rotation (AC2, AC4) ---

    @Test
    void validCookieSignsInRotatesTheSecretAndNeverExtendsTheExpiry() {
        String cookie = login(1L);
        byte[] firstTokenHash = store.only().tokenHash();
        clock.advance(Duration.ofDays(10));

        Result result = autoLogin(cookie);

        assertThat(result.authentication()).isInstanceOf(RememberMeAuthenticationToken.class);
        assertThat(result.authentication().getPrincipal()).isEqualTo(BOB);
        RememberMeLogin login = (RememberMeLogin) result.authentication().getDetails();
        assertThat(login.expiresAt()).isEqualTo(T0.plus(THIRTY_DAYS));
        String rotated = result.setCookie();
        assertThat(rotated).contains("; Path=/api", "; HttpOnly", "; SameSite=Lax");
        assertThat(maxAge(rotated)).isEqualTo(Duration.ofDays(20).getSeconds());
        String newValue = rotated.substring("remember-me=".length(), rotated.indexOf(';'));
        assertThat(newValue.substring(0, 43)).isEqualTo(cookie.substring(0, 43));
        assertThat(newValue).isNotEqualTo(cookie);

        RememberMeToken row = store.only();
        assertThat(row.previousTokenHash()).isEqualTo(firstTokenHash);
        assertThat(row.tokenHash()).isNotEqualTo(firstTokenHash);
        assertThat(row.rotatedAt()).isEqualTo(T0.plus(Duration.ofDays(10)));
        assertThat(row.expiresAt()).isEqualTo(T0.plus(THIRTY_DAYS));

        // The rotated cookie works next time, and again only for the time left.
        clock.advance(Duration.ofDays(19));
        Result again = autoLogin(newValue);
        assertThat(again.authentication()).isNotNull();
        assertThat(maxAge(again.setCookie())).isEqualTo(Duration.ofDays(1).getSeconds());
    }

    @Test
    void cookieIsRejectedFromTheExpiryOnAndItsLoginDeleted() {
        String cookie = login(1L);
        clock.advance(THIRTY_DAYS);

        Result result = autoLogin(cookie);

        assertThat(result.authentication()).isNull();
        assertThat(result.setCookie()).startsWith("remember-me=;").contains("Max-Age=0");
        assertThat(store.rows).isEmpty();
        assertThat(capture.lines()).containsExactly("SECURITY event=REMEMBER_ME_REJECTED reason=expired"
                + " email=bob@example.com ip=127.0.0.1 path=/api/auth/me");
        assertThat(capture.events().get(0).getLevel().toString()).isEqualTo("INFO");
    }

    // --- parallel requests (grace) and theft (AC6) ---

    @Test
    void previousSecretIsAcceptedWithoutRotationFor60Seconds() {
        String cookie = login(1L);
        autoLogin(cookie);
        byte[] currentHash = store.only().tokenHash();

        clock.advance(Duration.ofSeconds(59));
        Result parallel = autoLogin(cookie);

        assertThat(parallel.authentication().getPrincipal()).isEqualTo(BOB);
        assertThat(parallel.setCookie()).isNull();
        assertThat(store.only().tokenHash()).isEqualTo(currentHash);
        assertThat(store.endedUsers).isEmpty();
        assertThat(capture.lines()).isEmpty();
    }

    @Test
    void reusingAnOldSecretEndsAllOfThatUsersLoginsAndSessions() {
        String stolen = login(1L);
        String otherDevice = login(1L);
        login(2L);
        Result first = autoLogin(stolen);
        assertThat(first.authentication()).isNotNull();

        clock.advance(Duration.ofSeconds(60));
        Result reuse = autoLogin(stolen);

        assertThat(reuse.authentication()).isNull();
        assertThat(reuse.setCookie()).startsWith("remember-me=;").contains("Max-Age=0");
        assertThat(store.rows.values()).extracting(RememberMeToken::userId).containsExactly(2L);
        assertThat(store.endedUsers).containsExactly(Map.entry(1L, T0.plusSeconds(60)));
        assertThat(capture.lines()).containsExactly("SECURITY event=REMEMBER_ME_REJECTED reason=reused"
                + " email=bob@example.com ip=127.0.0.1 path=/api/auth/me");
        assertThat(capture.events().get(0).getLevel().toString()).isEqualTo("WARN");
        // The other device of the same user is logged out too.
        assertThat(autoLogin(otherDevice).authentication()).isNull();
    }

    @Test
    void secretThatWasNeverIssuedForAKnownSeriesIsTreatedAsTheft() {
        String cookie = login(1L);
        String forged = cookie.substring(0, 44) + "B".repeat(42) + "A";

        assertThat(autoLogin(forged).authentication()).isNull();

        assertThat(store.rows).isEmpty();
        assertThat(store.endedUsers).containsKey(1L);
    }

    @Test
    void losingTheRotationRaceToAParallelRequestIsNotTheft() {
        String cookie = login(1L);
        store.rotateBeforeNextCompareAndSet = true;

        Result result = autoLogin(cookie);

        assertThat(result.authentication().getPrincipal()).isEqualTo(BOB);
        assertThat(result.setCookie()).isNull();
        assertThat(store.endedUsers).isEmpty();
    }

    // --- rejected cookies never fail the request (guarantee 5) ---

    @Test
    void malformedAndUnknownCookiesAreExpiredWithoutAnException() {
        String unknown = "A".repeat(42) + "Q." + "B".repeat(42) + "A";
        List<String> values = List.of("abc", "%zz", "x".repeat(5000), "A".repeat(43) + ":" + "B".repeat(43),
                "A".repeat(43) + "." + "B".repeat(42) + "=", "é".repeat(87), unknown);

        for (String value : values) {
            Result result = autoLogin(value);
            assertThat(result.authentication()).as(value).isNull();
            assertThat(result.setCookie()).as(value).startsWith("remember-me=;").contains("Max-Age=0");
        }
        assertThat(capture.lines()).hasSize(values.size());
        assertThat(capture.lines().subList(0, values.size() - 1))
                .allMatch(line -> line.startsWith("SECURITY event=REMEMBER_ME_REJECTED reason=malformed email=-"));
        assertThat(capture.lines().get(values.size() - 1))
                .startsWith("SECURITY event=REMEMBER_ME_REJECTED reason=unknown email=-");
        assertThat(String.join("\n", capture.lines())).doesNotContain(unknown, "%zz");
    }

    @Test
    void cookieOfADeletedUserIsRejected() {
        String cookie = login(9L);

        assertThat(autoLogin(cookie).authentication()).isNull();
        assertThat(store.rows).isEmpty();
        assertThat(capture.lines()).singleElement().asString().contains("reason=user_gone");
    }

    @Test
    void noCookieMeansNothingHappens() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(services.autoLogin(new MockHttpServletRequest(), response)).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    // --- ending this device's login (AC5) ---

    @Test
    void endThisDevicesLoginDeletesOnlyTheMatchingLogin() {
        String mine = login(1L);
        String other = login(1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("remember-me", mine));

        services.endThisDevicesLogin(request, null);

        assertThat(store.rows).hasSize(1);
        assertThat(autoLogin(other).authentication()).isNotNull();
    }

    @Test
    void endThisDevicesLoginAcceptsThePreviousSecretAndTheSessionsSeries() {
        String cookie = login(1L);
        autoLogin(cookie);
        MockHttpServletRequest withPrevious = new MockHttpServletRequest();
        withPrevious.setCookies(new Cookie("remember-me", cookie));
        services.endThisDevicesLogin(withPrevious, null);
        assertThat(store.rows).isEmpty();

        MockHttpServletResponse response = new MockHttpServletResponse();
        RememberMeLogin login = services.createLogin(1L, new MockHttpServletRequest(), response);
        services.endThisDevicesLogin(new MockHttpServletRequest(), login.seriesId());
        assertThat(store.rows).isEmpty();
    }

    @Test
    void endThisDevicesLoginIgnoresAWrongSecret() {
        String cookie = login(1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("remember-me", cookie.substring(0, 44) + "C".repeat(42) + "A"));

        services.endThisDevicesLogin(request, "not-a-series");

        assertThat(store.rows).hasSize(1);
        assertThat(store.endedUsers).isEmpty();
    }

    @Test
    void createLoginDeletesTheUsersExpiredLogins() {
        login(1L);
        login(2L);
        clock.advance(THIRTY_DAYS);

        login(1L);

        assertThat(store.rows.values()).extracting(RememberMeToken::userId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.rows.values()).filteredOn(r -> r.userId() == 1L).singleElement()
                .extracting(RememberMeToken::expiresAt).isEqualTo(T0.plus(THIRTY_DAYS).plus(THIRTY_DAYS));
    }

    /** Behaves like the database store, including the compare-and-set rotation. */
    static final class InMemoryStore implements RememberMeTokenStore {

        final Map<String, RememberMeToken> rows = new LinkedHashMap<>();
        final Map<Long, Instant> endedUsers = new LinkedHashMap<>();
        /** Simulates a parallel request rotating the same login between our read and our compare-and-set. */
        boolean rotateBeforeNextCompareAndSet;

        private static String key(byte[] hash) {
            return HexFormat.of().formatHex(hash);
        }

        RememberMeToken only() {
            assertThat(rows).hasSize(1);
            return rows.values().iterator().next();
        }

        @Override
        public Optional<RememberMeToken> find(byte[] seriesHash) {
            return Optional.ofNullable(rows.get(key(seriesHash)));
        }

        @Override
        public void create(RememberMeToken token, Instant now) {
            rows.values().removeIf(r -> r.userId() == token.userId() && !r.expiresAt().isAfter(now));
            rows.put(key(token.seriesHash()), token);
        }

        @Override
        public boolean rotate(byte[] seriesHash, byte[] currentTokenHash, byte[] newTokenHash, Instant now) {
            RememberMeToken row = rows.get(key(seriesHash));
            if (rotateBeforeNextCompareAndSet && row != null) {
                rotateBeforeNextCompareAndSet = false;
                row = new RememberMeToken(row.seriesHash(), row.userId(), sha256(new byte[] {42}), row.tokenHash(),
                        now, row.expiresAt());
                rows.put(key(seriesHash), row);
            }
            if (row == null || !Arrays.equals(row.tokenHash(), currentTokenHash)) {
                return false;
            }
            rows.put(key(seriesHash), new RememberMeToken(row.seriesHash(), row.userId(), newTokenHash,
                    row.tokenHash(), now, row.expiresAt()));
            return true;
        }

        @Override
        public void delete(byte[] seriesHash) {
            rows.remove(key(seriesHash));
        }

        @Override
        public void endAllForUser(long userId, Instant now) {
            new ArrayList<>(rows.values()).stream().filter(r -> r.userId() == userId)
                    .forEach(r -> rows.remove(key(r.seriesHash())));
            endedUsers.put(userId, now);
        }
    }
}
