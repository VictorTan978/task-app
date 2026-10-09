package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLIntegrityConstraintViolationException;
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

class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder);
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

        assertThat(result).isEqualTo(new UserResponse(7L, "bob@example.com", "Bob"));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2").hasSize(60).isNotEqualTo("correct-horse");
        assertThat(passwordEncoder.matches("correct-horse", saved.getValue().getPasswordHash())).isTrue();
        assertThat(saved.getValue().getEmail()).isEqualTo("bob@example.com");
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

        assertThat(result).isEqualTo(new UserResponse(1L, "bob@example.com", "Bob"));
    }

    @Test
    void loginFailsWithWrongPassword() {
        User bob = persisted(1L, "bob@example.com", passwordEncoder.encode("correct-horse"), "Bob");
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob@example.com", "wrong-horse")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginFailsWithUnknownEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void getUserThrowsWhenUserNoLongerExists() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getUser(9L)).isInstanceOf(UnauthenticatedException.class);
    }
}
