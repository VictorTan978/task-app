package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

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
                .isInstanceOf(EmailTakenException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerMapsUniqueConstraintRaceToEmailTaken() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("Bob", "bob@example.com", "correct-horse")))
                .isInstanceOf(EmailTakenException.class);
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
