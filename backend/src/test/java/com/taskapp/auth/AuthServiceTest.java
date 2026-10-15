package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.taskapp.auth.dto.LoginRequest;
import com.taskapp.auth.dto.RegisterRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.ratelimit.AuthRateLimiter;
import com.taskapp.security.ratelimit.RateLimitProperties;
import com.taskapp.security.ratelimit.RateLimitedException;

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final EmailJobs emailJobs = mock(EmailJobs.class);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthRateLimiter rateLimiter = new AuthRateLimiter(
                new RateLimitProperties(5, Duration.ofMinutes(15), 20, 5, 100_000, 5, Duration.ofMinutes(15)), Clock.systemUTC());
        authService = new AuthService(userRepository, passwordEncoder, rateLimiter, emailJobs);
    }

    private static User persisted(long id, String email, String hash, String name) {
        User user = new User(email, hash, name);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void registerStoresBcryptHashAndNormalizedEmail() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 7L);
            return u;
        });

        UserResponse result = authService.register(new RegisterRequest(" Bob ", " Bob@Example.COM ", "correct-horse"));

        assertThat(result).isEqualTo(new UserResponse(7L, "bob@example.com", "Bob", false, true, List.of()));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2").hasSize(60).isNotEqualTo("correct-horse");
        assertThat(passwordEncoder.matches("correct-horse", saved.getValue().getPasswordHash())).isTrue();
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@example.com");
        // specs/email-auth.md AC1: a confirmation email is queued (sent after the response).
        verify(emailJobs).sendVerification(7L, "bob@example.com", EmailJobs.Trigger.REGISTER);
    }

    @Test
    void registerRejectsExistingEmail() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Bob", "BOB@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isTrue();
                    assertThat(e.displayNameTaken()).isFalse();
                });
        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(emailJobs);
    }

    @Test
    void registerChecksTrimmedDisplayNameAndRejectsTakenName() {
        when(userRepository.existsByDisplayName("José")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("  José ", "new@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isFalse();
                    assertThat(e.displayNameTaken()).isTrue();
                });
        verify(userRepository).existsByDisplayName("José");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerReportsBothTakenFields() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(true);
        when(userRepository.existsByDisplayName("Bob")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Bob", "bob@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isTrue();
                    assertThat(e.displayNameTaken()).isTrue();
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerMapsEmailUniqueKeyRaceToEmailTaken() {
        when(userRepository.saveAndFlush(any())).thenThrow(raceOn("users.uk_users_email", "bob@example.com"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Bob", "bob@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isTrue();
                    assertThat(e.displayNameTaken()).isFalse();
                });
    }

    @Test
    void registerMapsDisplayNameUniqueKeyRaceToDisplayNameTaken() {
        when(userRepository.saveAndFlush(any())).thenThrow(raceOn("users.uk_users_display_name", "Bob"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Bob", "bob@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isFalse();
                    assertThat(e.displayNameTaken()).isTrue();
                });
    }

    @Test
    void registerMapsRaceFromRawMysqlMessageAndIgnoresConstraintNamesInsideTheValue() {
        // No Hibernate exception in the chain: the key is parsed from MySQL's message. The duplicate value
        // itself mentions the other constraint and must not be mistaken for it.
        SQLIntegrityConstraintViolationException sql = new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'uk_users_display_name@example.com' for key 'users.uk_users_email'", "23000", 1062);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute", sql));

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("Bob", "uk_users_display_name@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RegistrationConflictException.class, e -> {
                    assertThat(e.emailTaken()).isTrue();
                    assertThat(e.displayNameTaken()).isFalse();
                });
    }

    @Test
    void registerRethrowsIntegrityViolationOfUnknownConstraint() {
        DataIntegrityViolationException other = raceOn("users.some_other_key", "x");
        when(userRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Bob", "bob@example.com", "correct-horse")))
                .isSameAs(other);
    }

    /** Mirrors what Spring + Hibernate + MySQL Connector/J raise for a duplicate key on insert. */
    private static DataIntegrityViolationException raceOn(String key, String value) {
        String message = "Duplicate entry '" + value + "' for key '" + key + "'";
        SQLIntegrityConstraintViolationException sql = new SQLIntegrityConstraintViolationException(message, "23000", 1062);
        ConstraintViolationException hibernate = new ConstraintViolationException(
                "could not execute statement [" + message + "]", sql, "insert into users ...", key);
        return new DataIntegrityViolationException("could not execute statement", hibernate);
    }

    @Test
    void loginSucceedsWithCorrectPasswordAnyEmailCase() {
        User bob = persisted(1L, "bob@example.com", passwordEncoder.encode("correct-horse"), "Bob");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));

        UserResponse result = authService.login(new LoginRequest("BoB@Example.com ", "correct-horse"));

        assertThat(result).isEqualTo(new UserResponse(1L, "bob@example.com", "Bob", false, true, List.of()));
    }

    @Test
    void loginFailsWithWrongPassword() {
        User bob = persisted(1L, "bob@example.com", passwordEncoder.encode("correct-horse"), "Bob");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong-horse")))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCredentialsException.Reason.BAD_PASSWORD);
                    assertThat(e.email()).isEqualTo("bob@example.com");
                });
    }

    @Test
    void loginFailsWithUnknownEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCredentialsException.Reason.UNKNOWN_EMAIL);
                    assertThat(e.email()).isEqualTo("nobody@example.com");
                });
    }

    @Test
    void sixthLoginAfterFiveFailuresIsRateLimitedBeforePasswordCheckEvenIfCorrect() {
        User bob = persisted(1L, "bob@example.com", passwordEncoder.encode("correct-horse"), "Bob");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong-horse")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        clearInvocations(userRepository);

        assertThatThrownBy(() -> authService.login(new LoginRequest(" BOB@example.com", "correct-horse")))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.limit()).isEqualTo(RateLimitedException.Limit.LOGIN_EMAIL);
                    assertThat(e.email()).isEqualTo("bob@example.com");
                });
        verifyNoInteractions(userRepository);
    }

    @Test
    void unknownEmailFailuresCountTooAndOtherEmailsAreUnaffected() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        User bob = persisted(1L, "bob@example.com", passwordEncoder.encode("correct-horse"), "Bob");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(RateLimitedException.class);
        assertThat(authService.login(new LoginRequest("bob@example.com", "correct-horse")).id()).isEqualTo(1L);
    }

    @Test
    void successfulLoginResetsFailureCount() {
        User bob = persisted(1L, "bob@example.com", passwordEncoder.encode("correct-horse"), "Bob");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong-horse")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        authService.login(new LoginRequest("bob@example.com", "correct-horse"));

        // 4 more failures would hit the limit without the reset; with it, the 5th attempt is still checked.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong-horse")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(authService.login(new LoginRequest("bob@example.com", "correct-horse")).id()).isEqualTo(1L);
    }

    @Test
    void getUserThrowsWhenUserNoLongerExists() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getUser(9L)).isInstanceOf(UnauthenticatedException.class);
    }

    // --- 0.8.0: accounts without a password (specs/google-sign-in.md AC8)

    @Test
    void accountWithoutPasswordFailsLikeAWrongPasswordAndChecksTheDummyHash() {
        PasswordEncoder spy = org.mockito.Mockito.spy(new BCryptPasswordEncoder(4));
        AuthService service = new AuthService(userRepository, spy, new AuthRateLimiter(
                new RateLimitProperties(5, Duration.ofMinutes(15), 20, 5, 100_000, 5, Duration.ofMinutes(15)),
                Clock.systemUTC()), emailJobs);
        String dummyHash = (String) ReflectionTestUtils.getField(service, "dummyHash");
        User google = User.withoutPassword("gina@gmail.com", "Gina", java.time.Instant.now());
        ReflectionTestUtils.setField(google, "id", 3L);
        when(userRepository.findByEmail("gina@gmail.com")).thenReturn(Optional.of(google));

        assertThatThrownBy(() -> service.login(new LoginRequest("Gina@Gmail.com", "any-password")))
                .isInstanceOfSatisfying(InvalidCredentialsException.class, e -> {
                    assertThat(e.reason()).isEqualTo(InvalidCredentialsException.Reason.NO_PASSWORD);
                    assertThat(e.getMessage()).isEqualTo("Invalid email or password");
                });
        // Exactly one BCrypt comparison, against the dummy hash: same cost as a wrong password.
        verify(spy).matches("any-password", dummyHash);
        verify(spy, org.mockito.Mockito.times(1)).matches(any(), any());
    }

    @Test
    void accountWithoutPasswordCountsAsAFailedLogin() {
        User google = User.withoutPassword("gina@gmail.com", "Gina", java.time.Instant.now());
        when(userRepository.findByEmail("gina@gmail.com")).thenReturn(Optional.of(google));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("gina@gmail.com", "x")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        assertThatThrownBy(() -> authService.login(new LoginRequest("gina@gmail.com", "x")))
                .isInstanceOf(RateLimitedException.class);
    }
}
