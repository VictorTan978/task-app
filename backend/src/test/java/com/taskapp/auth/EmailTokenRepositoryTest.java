package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.email.EmailProperties;
import com.taskapp.security.RememberMeToken;
import com.taskapp.security.ratelimit.MutableClock;

/**
 * The V6 table, the custom SQL of {@link EmailTokenRepository} and the token rules of {@link EmailTokenService}
 * (specs/email-auth.md AC4, AC5, AC10, AC11, AC16), against MySQL 8.4 with Flyway.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EmailTokenRepository.class, EmailTokenService.class, RememberMeTokenRepository.class,
        DatabaseRememberMeTokenStore.class, EmailTokenRepositoryTest.Config.class})
class EmailTokenRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String HASH = "$2a$10$" + "x".repeat(53);
    private static final Instant START = Instant.parse("2026-10-14T08:00:00.123456Z");

    @TestConfiguration
    static class Config {
        @Bean
        MutableClock clock() {
            return new MutableClock(START);
        }

        @Bean
        EmailProperties emailProperties() {
            return new EmailProperties("https://tasks.example.com", "Task App <a@example.com>",
                    Duration.ofHours(24), Duration.ofHours(1));
        }
    }

    @Autowired
    EmailTokenRepository tokens;
    @Autowired
    EmailTokenService service;
    @Autowired
    RememberMeTokenRepository rememberMe;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;

    private long bob;
    private long alice;

    @BeforeEach
    void seed() {
        bob = users.saveAndFlush(new User("bob@example.com", HASH, "Bob")).getId();
        alice = users.saveAndFlush(new User("alice@example.com", HASH, "Alice")).getId();
    }

    private Instant now() {
        return clock.instant();
    }

    private static byte[] bytes(int fill) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    private List<String> purposes(long userId) {
        return jdbc.queryForList("select purpose from email_tokens where user_id = ? order by created_at",
                String.class, userId);
    }

    private int count(long userId) {
        return jdbc.queryForObject("select count(*) from email_tokens where user_id = ?", Integer.class, userId);
    }

    // ---- schema and SQL ----

    @Test
    void insertAndFindRoundTripTheHashPurposeAndUtcInstantsExactly() {
        Instant expires = now().plus(Duration.ofHours(1));
        tokens.insert(bytes(1), bob, EmailTokenPurpose.RESET_PASSWORD, expires, now());

        EmailToken found = tokens.find(bytes(1)).orElseThrow();
        assertThat(found.tokenHash()).isEqualTo(bytes(1));
        assertThat(found.userId()).isEqualTo(bob);
        assertThat(found.purpose()).isEqualTo(EmailTokenPurpose.RESET_PASSWORD);
        assertThat(found.expiresAt()).isEqualTo(expires);
        assertThat(found.usedAt()).isNull();
        assertThat(tokens.find(bytes(2))).isEmpty();
    }

    @Test
    void purposeIsRestrictedByACheckConstraint() {
        assertThatThrownBy(() -> jdbc.update("insert into email_tokens (token_hash, user_id, purpose, expires_at)"
                + " values (?, ?, 'LOGIN', ?)", bytes(3), bob, Timestamp.from(now())))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void tokensAreDeletedWithTheirUser() {
        tokens.insert(bytes(1), bob, EmailTokenPurpose.VERIFY_EMAIL, now().plusSeconds(60), now());

        jdbc.update("delete from users where id = ?", bob);

        assertThat(tokens.find(bytes(1))).isEmpty();
    }

    @Test
    void markUsedSucceedsOnlyOnce() {
        tokens.insert(bytes(1), bob, EmailTokenPurpose.VERIFY_EMAIL, now().plusSeconds(60), now());

        assertThat(tokens.markUsed(bytes(1), now())).isTrue();
        assertThat(tokens.markUsed(bytes(1), now())).isFalse();
        assertThat(tokens.find(bytes(1)).orElseThrow().usedAt()).isEqualTo(now());
    }

    @Test
    void deleteReplacedAndExpiredKeepsUsedOtherPurposeAndOtherUsersTokens() {
        Instant later = now().plusSeconds(600);
        tokens.insert(bytes(1), bob, EmailTokenPurpose.VERIFY_EMAIL, later, now());        // unused, replaced
        tokens.insert(bytes(2), bob, EmailTokenPurpose.VERIFY_EMAIL, later, now());        // used, kept
        tokens.markUsed(bytes(2), now());
        tokens.insert(bytes(3), bob, EmailTokenPurpose.RESET_PASSWORD, later, now());      // other purpose, kept
        tokens.insert(bytes(4), bob, EmailTokenPurpose.RESET_PASSWORD, now(), now());      // expired, deleted
        tokens.insert(bytes(5), alice, EmailTokenPurpose.VERIFY_EMAIL, later, now());      // other user, kept

        assertThat(tokens.deleteReplacedAndExpired(bob, EmailTokenPurpose.VERIFY_EMAIL, now())).isEqualTo(2);

        assertThat(tokens.find(bytes(1))).isEmpty();
        assertThat(tokens.find(bytes(2))).isPresent();
        assertThat(tokens.find(bytes(3))).isPresent();
        assertThat(tokens.find(bytes(4))).isEmpty();
        assertThat(tokens.find(bytes(5))).isPresent();
        assertThat(tokens.deleteReplacedAndExpired(bob, EmailTokenPurpose.VERIFY_EMAIL, now())).isZero();
    }

    @Test
    void lockUserReturnsTheCurrentStateOrEmpty() {
        assertThat(tokens.lockUser(bob)).contains(
                new EmailTokenRepository.LockedUser(bob, "bob@example.com", "Bob", false));
        users.markEmailVerified(bob, now());
        assertThat(tokens.lockUser(bob).orElseThrow().emailVerified()).isTrue();
        assertThat(tokens.lockUser(-1)).isEmpty();
    }

    // ---- EmailTokenService ----

    @Test
    void issueStoresOnlyTheSha256OfA256BitTokenWithTheConfiguredLifetime() {
        EmailTokenService.IssuedToken issued = service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow();

        assertThat(issued.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.email()).isEqualTo("bob@example.com");
        assertThat(issued.displayName()).isEqualTo("Bob");
        assertThat(issued.validFor()).isEqualTo(Duration.ofHours(24));
        assertThat(issued.toString()).doesNotContain(issued.token());
        EmailToken row = tokens.find(EmailTokenService.hashOf(issued.token())).orElseThrow();
        assertThat(row.expiresAt()).isEqualTo(now().plus(Duration.ofHours(24)));
        byte[] stored = jdbc.queryForObject("select token_hash from email_tokens where user_id = ?", byte[].class, bob);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(issued.token());

        EmailTokenService.IssuedToken reset = service.issue(bob, EmailTokenPurpose.RESET_PASSWORD).orElseThrow();
        assertThat(reset.validFor()).isEqualTo(Duration.ofHours(1));
        assertThat(reset.token()).isNotEqualTo(issued.token());
    }

    @Test
    void aNewTokenReplacesTheOlderUnusedOneOfTheSamePurposeOnly() {
        String firstVerify = service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();
        String reset = service.issue(bob, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        String secondVerify = service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();

        assertThatThrownBy(() -> service.check(firstVerify, EmailTokenPurpose.VERIFY_EMAIL))
                .isInstanceOfSatisfying(EmailTokenInvalidException.class,
                        e -> assertThat(e.reason()).isEqualTo("unknown"));
        assertThat(service.check(secondVerify, EmailTokenPurpose.VERIFY_EMAIL).userId()).isEqualTo(bob);
        assertThat(service.check(reset, EmailTokenPurpose.RESET_PASSWORD).userId()).isEqualTo(bob);
        assertThat(purposes(bob)).containsExactlyInAnyOrder("VERIFY_EMAIL", "RESET_PASSWORD");
    }

    @Test
    void noConfirmationTokenForAConfirmedOrDeletedAccount() {
        users.markEmailVerified(bob, now());

        assertThat(service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL)).isEmpty();
        assertThat(service.issue(bob, EmailTokenPurpose.RESET_PASSWORD)).isPresent();
        assertThat(service.issue(-1, EmailTokenPurpose.RESET_PASSWORD)).isEmpty();
    }

    @Test
    void checkGivesTheReasonForEveryInvalidToken() {
        String verify = service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();

        assertReason(null, "missing");
        assertReason("  ", "missing");
        assertReason("not-a-token", "malformed");
        assertReason(verify + "x", "malformed");
        assertReason("A".repeat(43), "unknown");
        assertThatThrownBy(() -> service.check(verify, EmailTokenPurpose.RESET_PASSWORD))
                .isInstanceOfSatisfying(EmailTokenInvalidException.class, e -> {
                    assertThat(e.reason()).isEqualTo("wrong_purpose");
                    assertThat(e.email()).isEqualTo("bob@example.com");
                    assertThat(e.getMessage()).doesNotContain(verify);
                });
    }

    private void assertReason(String token, String reason) {
        assertThatThrownBy(() -> service.check(token, EmailTokenPurpose.VERIFY_EMAIL))
                .isInstanceOfSatisfying(EmailTokenInvalidException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void aTokenExpiresAtItsLifetime() {
        String reset = service.issue(bob, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        clock.advance(Duration.ofHours(1).minusNanos(1000));
        service.check(reset, EmailTokenPurpose.RESET_PASSWORD);

        clock.advance(Duration.ofNanos(1000));

        assertThatThrownBy(() -> service.check(reset, EmailTokenPurpose.RESET_PASSWORD))
                .isInstanceOfSatisfying(EmailTokenInvalidException.class,
                        e -> assertThat(e.reason()).isEqualTo("expired"));
    }

    @Test
    void verifyEmailUsesTheTokenOnceAndConfirmsTheEmail() {
        String verify = service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();

        assertThat(service.verifyEmail(service.check(verify, EmailTokenPurpose.VERIFY_EMAIL)))
                .isEqualTo("bob@example.com");

        assertThat(users.findById(bob).orElseThrow().getEmailVerifiedAt()).isEqualTo(now());
        assertReason(verify, "used");
        assertThat(users.findById(alice).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    void aCheckedTokenUsedMeanwhileIsRejectedWhenConsumed() {
        String verify = service.issue(bob, EmailTokenPurpose.VERIFY_EMAIL).orElseThrow().token();
        EmailToken checked = service.check(verify, EmailTokenPurpose.VERIFY_EMAIL);
        EmailToken alsoChecked = service.check(verify, EmailTokenPurpose.VERIFY_EMAIL);
        service.verifyEmail(checked);

        assertThatThrownBy(() -> service.verifyEmail(alsoChecked))
                .isInstanceOfSatisfying(EmailTokenInvalidException.class,
                        e -> assertThat(e.reason()).isEqualTo("used"));
    }

    @Test
    void resetPasswordChangesTheHashConfirmsTheEmailAndEndsAllSessions() {
        rememberMe.insert(new RememberMeToken(bytes(7), bob, bytes(8), null, null, now().plusSeconds(3600)));
        rememberMe.insert(new RememberMeToken(bytes(9), alice, bytes(10), null, null, now().plusSeconds(3600)));
        String reset = service.issue(bob, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        clock.advance(Duration.ofMinutes(5));

        String email = service.resetPassword(service.check(reset, EmailTokenPurpose.RESET_PASSWORD), "$2a$10$new");

        assertThat(email).isEqualTo("bob@example.com");
        User user = users.findById(bob).orElseThrow();
        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$new");
        assertThat(user.getEmailVerifiedAt()).isEqualTo(now());
        assertThat(user.getSessionsValidAfter()).isEqualTo(now());
        assertThat(rememberMe.findBySeriesHash(bytes(7))).isEmpty();
        assertThat(rememberMe.findBySeriesHash(bytes(9))).isPresent();
        assertThatThrownBy(() -> service.check(reset, EmailTokenPurpose.RESET_PASSWORD))
                .isInstanceOf(EmailTokenInvalidException.class);
    }

    @Test
    void resetPasswordKeepsAnEarlierConfirmationTime() {
        Instant confirmed = now();
        users.markEmailVerified(bob, confirmed);
        String reset = service.issue(bob, EmailTokenPurpose.RESET_PASSWORD).orElseThrow().token();
        clock.advance(Duration.ofMinutes(5));

        service.resetPassword(service.check(reset, EmailTokenPurpose.RESET_PASSWORD), "$2a$10$new");

        assertThat(users.findById(bob).orElseThrow().getEmailVerifiedAt()).isEqualTo(confirmed);
        assertThat(count(bob)).isEqualTo(1);
    }
}
