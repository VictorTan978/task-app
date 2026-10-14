package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.security.RememberMeToken;

/** The V5 table and the custom SQL of {@link RememberMeTokenRepository}, against MySQL 8.4 with Flyway. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RememberMeTokenRepository.class, DatabaseRememberMeTokenStore.class})
class RememberMeTokenRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final String HASH = "$2a$10$" + "x".repeat(53);
    private static final Instant NOW = Instant.parse("2026-10-13T08:00:00.123456Z");

    @Autowired
    RememberMeTokenRepository tokens;
    @Autowired
    DatabaseRememberMeTokenStore store;
    @Autowired
    UserRepository users;
    @Autowired
    JdbcTemplate jdbc;

    private long bob;
    private long alice;

    @BeforeEach
    void seed() {
        bob = users.saveAndFlush(new User("bob@example.com", HASH, "Bob")).getId();
        alice = users.saveAndFlush(new User("alice@example.com", HASH, "Alice")).getId();
    }

    private static byte[] bytes(int fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    private RememberMeToken insert(int series, long userId, Instant expiresAt) {
        RememberMeToken token = new RememberMeToken(bytes(series), userId, bytes(series + 100), null, null, expiresAt);
        tokens.insert(token);
        return token;
    }

    private int count(long userId) {
        return jdbc.queryForObject("select count(*) from remember_me_tokens where user_id = ?", Integer.class, userId);
    }

    @Test
    void insertAndFindRoundTripHashesAndUtcInstantsExactly() {
        Instant expires = NOW.plus(Duration.ofDays(30));
        insert(1, bob, expires);

        RememberMeToken found = tokens.findBySeriesHash(bytes(1)).orElseThrow();

        assertThat(found.userId()).isEqualTo(bob);
        assertThat(found.seriesHash()).isEqualTo(bytes(1));
        assertThat(found.tokenHash()).isEqualTo(bytes(101));
        assertThat(found.previousTokenHash()).isNull();
        assertThat(found.rotatedAt()).isNull();
        assertThat(found.expiresAt()).isEqualTo(expires);
        assertThat(jdbc.queryForObject("select cast(expires_at as char) from remember_me_tokens", String.class))
                .isEqualTo("2026-11-12 08:00:00.123456");
        assertThat(jdbc.queryForObject("select created_at from remember_me_tokens", java.sql.Timestamp.class))
                .isNotNull();
        assertThat(tokens.findBySeriesHash(bytes(2))).isEmpty();
    }

    @Test
    void rotateIsACompareAndSetThatKeepsTheExpiry() {
        Instant expires = NOW.plus(Duration.ofDays(30));
        insert(1, bob, expires);
        Instant rotatedAt = NOW.plusSeconds(10);

        assertThat(tokens.rotate(bytes(1), bytes(101), bytes(201), rotatedAt)).isTrue();
        // A second request with the old secret loses: the secret is no longer bytes(101).
        assertThat(tokens.rotate(bytes(1), bytes(101), bytes(202), rotatedAt)).isFalse();

        RememberMeToken row = tokens.findBySeriesHash(bytes(1)).orElseThrow();
        assertThat(row.tokenHash()).isEqualTo(bytes(201));
        assertThat(row.previousTokenHash()).isEqualTo(bytes(101));
        assertThat(row.rotatedAt()).isEqualTo(rotatedAt);
        assertThat(row.expiresAt()).isEqualTo(expires);
        assertThat(tokens.rotate(bytes(9), bytes(101), bytes(203), rotatedAt)).isFalse();
    }

    @Test
    void deleteAllForUserExceptKeepsThisDeviceAndOtherUsers() {
        insert(1, bob, NOW.plus(Duration.ofDays(1)));
        insert(2, bob, NOW.plus(Duration.ofDays(1)));
        insert(3, bob, NOW.plus(Duration.ofDays(1)));
        insert(4, alice, NOW.plus(Duration.ofDays(1)));

        assertThat(tokens.deleteAllForUserExcept(bob, bytes(2))).isEqualTo(2);

        assertThat(tokens.findBySeriesHash(bytes(2))).isPresent();
        assertThat(count(bob)).isEqualTo(1);
        assertThat(count(alice)).isEqualTo(1);
        assertThat(tokens.deleteAllForUserExcept(bob, null)).isEqualTo(1);
        assertThat(count(bob)).isZero();
    }

    @Test
    void deletesWithNothingToDeleteReturnZeroAndLockOwnerFindsOnlyExistingUsers() {
        insert(1, alice, NOW.plus(Duration.ofDays(1)));

        assertThat(tokens.deleteExpiredForUser(bob, NOW)).isZero();
        assertThat(tokens.deleteAllForUser(bob)).isZero();
        assertThat(tokens.deleteAllForUserExcept(alice, bytes(1))).isZero();
        assertThat(count(alice)).isEqualTo(1);
        assertThat(tokens.lockOwner(bob)).isTrue();
        assertThat(tokens.lockOwner(-1)).isFalse();
    }

    @Test
    void createDeletesOnlyThatUsersExpiredLogins() {
        insert(1, bob, NOW);
        insert(2, bob, NOW.plusSeconds(1));
        insert(3, alice, NOW.minusSeconds(1));

        store.create(new RememberMeToken(bytes(5), bob, bytes(105), null, null, NOW.plus(Duration.ofDays(30))), NOW);

        assertThat(tokens.findBySeriesHash(bytes(1))).isEmpty();
        assertThat(tokens.findBySeriesHash(bytes(2))).isPresent();
        assertThat(tokens.findBySeriesHash(bytes(3))).isPresent();
        assertThat(tokens.findBySeriesHash(bytes(5))).isPresent();
    }

    @Test
    void endAllForUserDeletesTheirLoginsAndSetsTheSessionCutoff() {
        insert(1, bob, NOW.plus(Duration.ofDays(1)));
        insert(2, bob, NOW.plus(Duration.ofDays(1)));
        insert(3, alice, NOW.plus(Duration.ofDays(1)));

        store.endAllForUser(bob, NOW);

        assertThat(count(bob)).isZero();
        assertThat(count(alice)).isEqualTo(1);
        assertThat(users.findById(bob).orElseThrow().getSessionsValidAfter()).isEqualTo(NOW);
        assertThat(users.findById(alice).orElseThrow().getSessionsValidAfter()).isNull();
    }

    @Test
    void deletingTheUserCascadesToTheirLogins() {
        insert(1, bob, NOW.plus(Duration.ofDays(1)));
        insert(2, alice, NOW.plus(Duration.ofDays(1)));

        users.deleteById(bob);
        users.flush();

        assertThat(count(bob)).isZero();
        assertThat(count(alice)).isEqualTo(1);
    }

    @Test
    void sessionsValidAfterRoundTripsWithMicrosecondPrecision() {
        User user = users.findById(bob).orElseThrow();
        user.endSessionsBefore(NOW.truncatedTo(ChronoUnit.MICROS));
        users.saveAndFlush(user);

        assertThat(jdbc.queryForObject("select cast(sessions_valid_after as char) from users where id = ?",
                String.class, bob)).isEqualTo("2026-10-13 08:00:00.123456");
    }

    @Test
    void rotationColumnsMustBeSetTogether() {
        assertThatThrownBy(() -> tokens.insert(
                new RememberMeToken(bytes(7), bob, bytes(107), bytes(8), null, NOW)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_remember_me_tokens_rotation");
    }
}
