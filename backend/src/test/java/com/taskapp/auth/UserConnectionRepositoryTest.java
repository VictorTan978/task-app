package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V7 and the custom queries of {@link UserConnectionRepository} / {@link UserRepository} against MySQL 8.4 with the
 * real migrations: {@code sub} compared exactly, emails ignoring case and accents, the unique keys, the provider
 * CHECK, nullable passwords and the cascade with the user.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserConnectionRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    UserConnectionRepository connections;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private User ana;

    @BeforeEach
    void seed() {
        ana = users.saveAndFlush(User.withoutPassword("ana@example.com", "Ana", NOW));
        connections.saveAndFlush(new UserConnection(ana, UserConnection.GOOGLE, "Sub-123", "ana@gmail.com", NOW));
    }

    @Test
    void passwordHashIsNullable() {
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, ana.getId()))
                .isNull();
        assertThat(users.findById(ana.getId())).get().extracting(User::hasPassword).isEqualTo(false);
    }

    @Test
    void subjectIsMatchedExactly() {
        assertThat(connections.findByProviderAndProviderSubject("google", "Sub-123")).isPresent();
        assertThat(connections.findByProviderAndProviderSubject("google", "sub-123")).isEmpty();
        assertThat(connections.findByProviderAndProviderSubject("google", "Sub-12")).isEmpty();
        // ascii_bin pads with spaces ('Sub-123 ' = 'Sub-123'); GoogleAccountService never accepts a sub with spaces.
    }

    @Test
    void findByUserIdAndProvider() {
        assertThat(connections.findByUserIdAndProvider(ana.getId(), "google")).get()
                .extracting(UserConnection::getProviderEmail).isEqualTo("ana@gmail.com");
        assertThat(connections.findByUserIdAndProvider(ana.getId() + 1, "google")).isEmpty();
    }

    @Test
    void aGoogleAccountBelongsToOneUserAndAUserHasOneGoogleAccount() {
        User bob = users.saveAndFlush(User.withoutPassword("bob@example.com", "Bob", NOW));

        assertThatThrownBy(() -> connections.saveAndFlush(
                new UserConnection(bob, UserConnection.GOOGLE, "Sub-123", "x@gmail.com", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(AuthService.violatedConstraint(e))
                        .isEqualTo("uk_user_connections_provider_subject"));
    }

    @Test
    void secondGoogleAccountForTheSameUserIsRejected() {
        assertThatThrownBy(() -> connections.saveAndFlush(
                new UserConnection(ana, UserConnection.GOOGLE, "Sub-456", "y@gmail.com", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(AuthService.violatedConstraint(e))
                        .isEqualTo("uk_user_connections_user_provider"));
    }

    @Test
    void onlyKnownProvidersAreAccepted() {
        assertThatThrownBy(() -> jdbc.update("insert into user_connections (user_id, provider, provider_subject,"
                + " provider_email) values (?, 'github', 'x', 'x@x.com')", ana.getId()))
                .hasMessageContaining("ck_user_connections_provider");
    }

    @Test
    void deleteByIdNowAndCascadeWithTheUser() {
        long id = connections.findByUserIdAndProvider(ana.getId(), "google").orElseThrow().getId();
        assertThat(connections.deleteByIdNow(id)).isEqualTo(1);
        assertThat(connections.count()).isZero();

        User bob = users.saveAndFlush(User.withoutPassword("bob@example.com", "Bob", NOW));
        connections.saveAndFlush(new UserConnection(bob, UserConnection.GOOGLE, "Sub-9", "bob@gmail.com", NOW));
        jdbc.update("delete from users where id = ?", bob.getId());
        assertThat(jdbc.queryForObject("select count(*) from user_connections", Integer.class)).isZero();
    }

    @Test
    void emailLookupsIgnoreCaseAndAccents() {
        assertThat(users.findIdByEmail("ána@example.com")).contains(ana.getId());
        assertThat(users.findIdByEmail("anna@example.com")).isEmpty();
        assertThat(users.existsByIdAndEmail(ana.getId(), "ANA@example.com".toLowerCase())).isTrue();
        assertThat(users.existsByIdAndEmail(ana.getId(), "ána@example.com")).isTrue();
        assertThat(users.existsByIdAndEmail(ana.getId() + 1, "ana@example.com")).isFalse();
    }

    @Test
    void findByIdForUpdateLoadsTheUser() {
        assertThat(users.findByIdForUpdate(ana.getId())).get().extracting(User::getEmail)
                .isEqualTo("ana@example.com");
        assertThat(users.findByIdForUpdate(-1L)).isEmpty();
    }
}
