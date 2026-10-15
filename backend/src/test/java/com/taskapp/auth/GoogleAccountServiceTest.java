package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.sql.Timestamp;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.taskapp.auth.dto.ConnectionResponse;
import com.taskapp.auth.dto.UserResponse;

/**
 * The account matching matrix of specs/google-sign-in.md (AC2-AC5, AC12-AC13, AC17) against MySQL 8.4 with the
 * real migrations: emails match with the column collation (case- and accent-insensitive), {@code sub} exactly.
 * Not in a test transaction: the service runs its own READ COMMITTED transactions.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({GoogleAccountService.class, GoogleAccountServiceTest.FixedClock.class})
class GoogleAccountServiceTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    private static final String HASH = "$2a$10$" + "x".repeat(53);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    GoogleAccountService service;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from users");
    }

    private long passwordAccount(String email, String name, boolean confirmed) {
        long id = users.saveAndFlush(new User(email, HASH, name)).getId();
        if (confirmed) {
            jdbc.update("update users set email_verified_at = ? where id = ?",
                    Timestamp.from(NOW.minusSeconds(3600)), id);
        }
        return id;
    }

    private void connection(long userId, String sub, String email) {
        jdbc.update("insert into user_connections (user_id, provider, provider_subject, provider_email) "
                + "values (?, 'google', ?, ?)", userId, sub, email);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static GoogleIdentity google(String sub, String email, boolean verified, String name) {
        return new GoogleIdentity(sub, email, verified, name);
    }

    // --- login intent

    @Test
    void newVerifiedGoogleAccountCreatesAConfirmedAccountWithoutPassword() {
        GoogleLogin result = service.signIn(google("111", " Ana@Gmail.com ", true, "Ana López"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.CREATED);
        UserResponse user = result.user();
        assertThat(user.email()).isEqualTo("ana@gmail.com");
        assertThat(user.displayName()).isEqualTo("Ana López");
        assertThat(user.emailVerified()).isTrue();
        assertThat(user.hasPassword()).isFalse();
        assertThat(user.connections()).containsExactly(new ConnectionResponse("google", "ana@gmail.com"));
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, user.id()))
                .isNull();
        assertThat(jdbc.queryForObject("select email_verified_at from users where id = ?", Timestamp.class,
                user.id()).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select provider_subject from user_connections where user_id = ?",
                String.class, user.id())).isEqualTo("111");
    }

    @Test
    void sameGoogleAccountSignsInToTheSameAccountEvenWithANewEmail() {
        long id = service.signIn(google("222", "bo@gmail.com", true, "Bo")).user().id();

        GoogleLogin again = service.signIn(google("222", "bo.new@gmail.com", false, "Someone Else"));

        assertThat(again.outcome()).isEqualTo(GoogleLogin.Outcome.EXISTING);
        assertThat(again.user().id()).isEqualTo(id);
        assertThat(again.user().email()).isEqualTo("bo@gmail.com");
        assertThat(again.user().displayName()).isEqualTo("Bo");
        assertThat(again.user().connections()).containsExactly(new ConnectionResponse("google", "bo.new@gmail.com"));
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void subjectIsMatchedExactly() {
        service.signIn(google("abc", "c1@gmail.com", true, "C1"));

        GoogleLogin other = service.signIn(google("ABC", "c2@gmail.com", true, "C2"));

        assertThat(other.outcome()).isEqualTo(GoogleLogin.Outcome.CREATED);
        assertThat(count("user_connections")).isEqualTo(2);
    }

    @Test
    void verifiedGoogleEmailLinksAConfirmedAccountMatchedIgnoringCaseAndAccents() {
        long id = passwordAccount("jose@example.com", "José", true);

        GoogleLogin result = service.signIn(google("333", "José@Example.COM", true, "José G"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.LINKED);
        assertThat(result.user().id()).isEqualTo(id);
        assertThat(result.user().hasPassword()).isTrue();
        assertThat(result.user().displayName()).isEqualTo("José");
        assertThat(result.user().connections()).containsExactly(new ConnectionResponse("google", "josé@example.com"));
    }

    @Test
    void unverifiedGoogleEmailNeverLinksAnExistingAccount() {
        passwordAccount("dan@example.com", "Dan", true);

        GoogleLogin result = service.signIn(google("444", "dan@example.com", false, "Dan"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.UNVERIFIED);
        assertThat(result.reason()).isEqualTo("google_email_unverified");
        assertThat(count("user_connections")).isZero();
    }

    @Test
    void unconfirmedLocalAccountIsNeverLinked() {
        passwordAccount("eve@example.com", "Eve", false);

        GoogleLogin result = service.signIn(google("555", "eve@example.com", true, "Eve"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.UNVERIFIED);
        assertThat(result.reason()).isEqualTo("account_email_unconfirmed");
        assertThat(count("user_connections")).isZero();
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void accountWithAnotherGoogleConnectedIsNeverLinked() {
        long id = passwordAccount("fay@example.com", "Fay", true);
        connection(id, "other-sub", "fay.other@gmail.com");

        GoogleLogin result = service.signIn(google("666", "fay@example.com", true, "Fay"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.UNVERIFIED);
        assertThat(result.reason()).isEqualTo("other_google_connected");
        assertThat(jdbc.queryForList("select provider_subject from user_connections", String.class))
                .containsExactly("other-sub");
    }

    // --- disabled accounts (specs/roles-admin.md AC18)

    private void disable(long id) {
        jdbc.update("update users set disabled_at = ? where id = ?", Timestamp.from(NOW.minusSeconds(60)), id);
    }

    @Test
    void disabledAccountMatchedByGoogleIdIsRefusedAndNothingChanges() {
        long id = service.signIn(google("d-1", "dee@gmail.com", true, "Dee")).user().id();
        disable(id);

        GoogleLogin result = service.signIn(google("d-1", "dee.new@gmail.com", true, "Dee"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.DISABLED);
        assertThat(result.outcome().signsIn()).isFalse();
        assertThat(result.reason()).isEqualTo("account_disabled");
        assertThat(result.user()).isNull();
        assertThat(jdbc.queryForObject("select provider_email from user_connections where user_id = ?", String.class,
                id)).isEqualTo("dee@gmail.com");
    }

    @Test
    void disabledAccountThatWouldBeLinkedByEmailIsRefusedAndNothingIsLinkedOrCreated() {
        long id = passwordAccount("dora@example.com", "Dora", true);
        disable(id);

        GoogleLogin result = service.signIn(google("d-2", "DORA@example.com", true, "Dora"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.DISABLED);
        assertThat(result.reason()).isEqualTo("account_disabled");
        assertThat(count("user_connections")).isZero();
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void disabledAccountThatCantBeLinkedAnywayGetsTheUsualAnswer() {
        long id = passwordAccount("dirk@example.com", "Dirk", false);
        disable(id);

        assertThat(service.signIn(google("d-3", "dirk@example.com", true, "Dirk")).reason())
                .isEqualTo("account_email_unconfirmed");
        assertThat(service.signIn(google("d-3", "dirk@example.com", false, "Dirk")).reason())
                .isEqualTo("google_email_unverified");
    }

    @Test
    void connectForADisabledUserConnectsNothing() {
        long id = passwordAccount("dina@example.com", "Dina", false);
        disable(id);

        GoogleConnect result = service.connect(id, google("d-4", "dina@example.com", true, "Dina"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.DISABLED);
        assertThat(result.outcome().connected()).isFalse();
        assertThat(count("user_connections")).isZero();
        assertThat(jdbc.queryForObject("select email_verified_at from users where id = ?", Timestamp.class, id))
                .isNull();
    }

    @Test
    void unverifiedGoogleEmailWithoutAccountCreatesNothing() {
        GoogleLogin result = service.signIn(google("777", "gil@gmail.com", false, "Gil"));

        assertThat(result.outcome()).isEqualTo(GoogleLogin.Outcome.UNVERIFIED);
        assertThat(count("users")).isZero();
    }

    @Test
    void takenDisplayNameGetsANumber() {
        passwordAccount("other@example.com", "Hana", false);

        GoogleLogin result = service.signIn(google("888", "hana@gmail.com", true, "HANA"));

        assertThat(result.user().displayName()).isEqualTo("HANA 2");
    }

    @Test
    void missingEmailOrSubjectIsInvalidAndChangesNothing() {
        assertThat(service.signIn(google("999", null, true, "X")).outcome()).isEqualTo(GoogleLogin.Outcome.INVALID);
        assertThat(service.signIn(google(null, "x@gmail.com", true, "X")).outcome())
                .isEqualTo(GoogleLogin.Outcome.INVALID);
        assertThat(service.signIn(google("bad sub", "x@gmail.com", true, "X")).outcome())
                .isEqualTo(GoogleLogin.Outcome.INVALID);
        assertThat(count("users")).isZero();
    }

    // --- connect intent

    @Test
    void connectWorksWithAnyGoogleEmailAndKeepsEmailVerifiedUnchanged() {
        long id = passwordAccount("ivy@example.com", "Ivy", false);

        GoogleConnect result = service.connect(id, google("c-1", "ivy.personal@gmail.com", true, "Ivy"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.CONNECTED);
        assertThat(result.user().emailVerified()).isFalse();
        assertThat(result.user().connections())
                .containsExactly(new ConnectionResponse("google", "ivy.personal@gmail.com"));
    }

    @Test
    void connectWithTheSameVerifiedEmailConfirmsTheAccountEmail() {
        long id = passwordAccount("jon@example.com", "Jon", false);

        GoogleConnect result = service.connect(id, google("c-2", "JON@example.com", true, "Jon"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.CONNECTED);
        assertThat(result.user().emailVerified()).isTrue();
    }

    @Test
    void connectWithTheSameUnverifiedEmailDoesNotConfirm() {
        long id = passwordAccount("kim@example.com", "Kim", false);

        GoogleConnect result = service.connect(id, google("c-3", "kim@example.com", false, "Kim"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.CONNECTED);
        assertThat(result.user().emailVerified()).isFalse();
    }

    @Test
    void connectingTheSameGoogleAccountAgainIsASuccess() {
        long id = passwordAccount("lea@example.com", "Lea", true);
        service.connect(id, google("c-4", "lea@gmail.com", true, "Lea"));

        GoogleConnect again = service.connect(id, google("c-4", "lea2@gmail.com", true, "Lea"));

        assertThat(again.outcome()).isEqualTo(GoogleConnect.Outcome.ALREADY_CONNECTED);
        assertThat(again.user().connections()).containsExactly(new ConnectionResponse("google", "lea2@gmail.com"));
    }

    @Test
    void googleAccountOfAnotherUserIsInUse() {
        long owner = passwordAccount("max@example.com", "Max", true);
        connection(owner, "c-5", "max@gmail.com");
        long id = passwordAccount("ned@example.com", "Ned", true);

        GoogleConnect result = service.connect(id, google("c-5", "max@gmail.com", true, "Max"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.IN_USE);
        assertThat(jdbc.queryForList("select user_id from user_connections", Long.class)).containsExactly(owner);
    }

    @Test
    void userWithADifferentGoogleConnectedCantConnectAnother() {
        long id = passwordAccount("oli@example.com", "Oli", true);
        connection(id, "c-6", "oli@gmail.com");

        GoogleConnect result = service.connect(id, google("c-7", "oli2@gmail.com", true, "Oli"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.OTHER_GOOGLE_CONNECTED);
        assertThat(jdbc.queryForList("select provider_subject from user_connections", String.class))
                .containsExactly("c-6");
    }

    @Test
    void connectForADeletedUserChangesNothing() {
        GoogleConnect result = service.connect(424242L, google("c-8", "p@gmail.com", true, "P"));

        assertThat(result.outcome()).isEqualTo(GoogleConnect.Outcome.USER_GONE);
        assertThat(count("user_connections")).isZero();
    }

    @Test
    void deletingTheUserDeletesItsConnection() {
        long id = service.signIn(google("d-1", "quinn@gmail.com", true, "Quinn")).user().id();

        users.deleteById(id);

        assertThat(count("user_connections")).isZero();
        assertThat(count("users")).isZero();
    }
}
