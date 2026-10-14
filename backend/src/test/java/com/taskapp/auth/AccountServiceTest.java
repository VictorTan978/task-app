package com.taskapp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.taskapp.auth.dto.ChangeDisplayNameRequest;
import com.taskapp.auth.dto.ChangePasswordRequest;
import com.taskapp.auth.dto.DeleteAccountRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.SessionRenewal;
import com.taskapp.security.ratelimit.AuthRateLimiter;
import com.taskapp.security.ratelimit.RateLimitProperties;
import com.taskapp.security.ratelimit.RateLimitedException;

class AccountServiceTest {

    private static final String PASSWORD = "correct-horse";
    private static final Instant SIGNED_IN = Instant.parse("2026-10-13T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00.123456Z");
    /** A normal session (no remember-me login) signed in at SIGNED_IN, ending the others at NOW. */
    private static final SessionRenewal RENEWAL = new SessionRenewal(SIGNED_IN, NOW, null);
    /** base64url of a 32-byte series hash, as stored in the session. */
    private static final String SERIES_ID = "A".repeat(42) + "Q";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RememberMeTokenRepository rememberMeTokens = mock(RememberMeTokenRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private AccountService accountService;
    private User bob;
    private User alice;

    @BeforeEach
    void setUp() {
        AuthRateLimiter rateLimiter = new AuthRateLimiter(new RateLimitProperties(
                5, Duration.ofMinutes(15), 20, 5, 100_000, 5, Duration.ofMinutes(15)), Clock.systemUTC());
        accountService = new AccountService(userRepository, passwordEncoder, rateLimiter, rememberMeTokens);
        bob = persisted(1L, "bob@example.com", "Bob");
        alice = persisted(2L, "alice@example.com", "Alice");
        when(userRepository.findById(1L)).thenReturn(Optional.of(bob));
        when(userRepository.findById(2L)).thenReturn(Optional.of(alice));
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private User persisted(long id, String email, String name) {
        User user = new User(email, passwordEncoder.encode(PASSWORD), name);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // --- display name ---

    @Test
    void changeDisplayNameSavesTrimmedName() {
        DisplayNameChange change = accountService.changeDisplayName(1L, new ChangeDisplayNameRequest("  Bobby "));

        assertThat(change.changed()).isTrue();
        assertThat(change.user()).isEqualTo(new UserResponse(1L, "bob@example.com", "Bobby", false));
        assertThat(bob.getDisplayName()).isEqualTo("Bobby");
        verify(userRepository).existsByDisplayNameAndIdNot("Bobby", 1L);
        verify(userRepository).saveAndFlush(bob);
    }

    @Test
    void ownNameWithDifferentCaseIsAChangeAndNeverTaken() {
        // AC5: only OTHER users are checked; the repository answers false for my own row.
        when(userRepository.existsByDisplayNameAndIdNot("bob", 1L)).thenReturn(false);

        DisplayNameChange change = accountService.changeDisplayName(1L, new ChangeDisplayNameRequest("bob"));

        assertThat(change.changed()).isTrue();
        assertThat(bob.getDisplayName()).isEqualTo("bob");
    }

    @Test
    void exactCurrentNameIsANoOp() {
        DisplayNameChange change = accountService.changeDisplayName(1L, new ChangeDisplayNameRequest(" Bob "));

        assertThat(change.changed()).isFalse();
        assertThat(change.user().displayName()).isEqualTo("Bob");
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void nameOfAnotherUserIsTaken() {
        when(userRepository.existsByDisplayNameAndIdNot("alice", 1L)).thenReturn(true);

        assertThatThrownBy(() -> accountService.changeDisplayName(1L, new ChangeDisplayNameRequest("alice")))
                .isInstanceOf(DisplayNameTakenException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentChangeToTheSameNameMapsUniqueKeyTo409() {
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLIntegrityConstraintViolationException(
                        "Duplicate entry 'Carol' for key 'users.uk_users_display_name'"), null)));

        assertThatThrownBy(() -> accountService.changeDisplayName(1L, new ChangeDisplayNameRequest("Carol")))
                .isInstanceOf(DisplayNameTakenException.class);
    }

    @Test
    void otherIntegrityViolationsAreRethrown() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("other");
        when(userRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> accountService.changeDisplayName(1L, new ChangeDisplayNameRequest("Carol")))
                .isSameAs(other);
    }

    @Test
    void missingUserIsUnauthenticated() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.changeDisplayName(9L, new ChangeDisplayNameRequest("X")))
                .isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> accountService.changePassword(9L, new ChangePasswordRequest(PASSWORD, "new-password"), RENEWAL))
                .isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> accountService.deleteAccount(9L, new DeleteAccountRequest(PASSWORD)))
                .isInstanceOf(UnauthenticatedException.class);
    }

    // --- password ---

    @Test
    void changePasswordStoresNewBcryptHash() {
        UserResponse result = accountService.changePassword(1L, new ChangePasswordRequest(PASSWORD, "battery-staple-42"), RENEWAL);

        assertThat(result.email()).isEqualTo("bob@example.com");
        assertThat(bob.getPasswordHash()).startsWith("$2").hasSize(60);
        assertThat(passwordEncoder.matches("battery-staple-42", bob.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, bob.getPasswordHash())).isFalse();
        verify(userRepository).saveAndFlush(bob);
    }

    @Test
    void newPasswordMayEqualTheCurrentOne() {
        accountService.changePassword(1L, new ChangePasswordRequest(PASSWORD, PASSWORD), RENEWAL);

        assertThat(passwordEncoder.matches(PASSWORD, bob.getPasswordHash())).isTrue();
    }

    @Test
    void wrongCurrentPasswordChangesNothing() {
        String hashBefore = bob.getPasswordHash();

        assertThatThrownBy(() -> accountService.changePassword(1L, new ChangePasswordRequest("wrong-horse", "new-password"), RENEWAL))
                .isInstanceOfSatisfying(WrongPasswordException.class, e -> {
                    assertThat(e.action()).isEqualTo(WrongPasswordException.Action.CHANGE_PASSWORD);
                    assertThat(e.action().field()).isEqualTo("currentPassword");
                    assertThat(e.email()).isEqualTo("bob@example.com");
                    assertThat(e.getMessage()).doesNotContain("wrong-horse");
                });
        assertThat(bob.getPasswordHash()).isEqualTo(hashBefore);
        verify(userRepository, never()).saveAndFlush(any());
        assertThat(bob.getSessionsValidAfter()).isNull();
        verifyNoInteractions(rememberMeTokens);
    }

    // --- other sessions (specs/sessions.md AC10, AC11) ---

    @Test
    void changePasswordEndsAllOtherSessionsAndRememberMeLogins() {
        accountService.changePassword(1L, new ChangePasswordRequest(PASSWORD, "battery-staple-42"), RENEWAL);

        assertThat(bob.getSessionsValidAfter()).isEqualTo(NOW);
        verify(rememberMeTokens).deleteAllForUserExcept(1L, null);
    }

    @Test
    void logoutOtherSessionsSetsTheCutoffAndKeepsThisDevicesRememberMeLogin() {
        UserResponse result = accountService.logoutOtherSessions(1L, new SessionRenewal(SIGNED_IN, NOW, SERIES_ID));

        assertThat(result.email()).isEqualTo("bob@example.com");
        assertThat(bob.getSessionsValidAfter()).isEqualTo(NOW);
        verify(userRepository).saveAndFlush(bob);
        ArgumentCaptor<byte[]> kept = ArgumentCaptor.forClass(byte[].class);
        verify(rememberMeTokens).deleteAllForUserExcept(eq(1L), kept.capture());
        assertThat(kept.getValue()).hasSize(32);
    }

    @Test
    void logoutOtherSessionsIsIdempotent() {
        accountService.logoutOtherSessions(1L, RENEWAL);
        // Same session again: its validity time is now the cutoff itself, which is not "before" it.
        accountService.logoutOtherSessions(1L, new SessionRenewal(NOW, NOW.plusSeconds(5), null));

        assertThat(bob.getSessionsValidAfter()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void aSessionAlreadyEndedByANewerCutoffCannotRenewItself() {
        bob.endSessionsBefore(SIGNED_IN.plusSeconds(1));

        assertThatThrownBy(() -> accountService.logoutOtherSessions(1L, RENEWAL))
                .isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> accountService.changePassword(1L,
                new ChangePasswordRequest(PASSWORD, "battery-staple-42"), RENEWAL))
                .isInstanceOf(UnauthenticatedException.class);
        assertThat(bob.getSessionsValidAfter()).isEqualTo(SIGNED_IN.plusSeconds(1));
        verifyNoInteractions(rememberMeTokens);
    }

    @Test
    void logoutOtherSessionsOfAMissingUserIsUnauthenticated() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.logoutOtherSessions(9L, RENEWAL))
                .isInstanceOf(UnauthenticatedException.class);
    }

    // --- delete ---

    @Test
    void deleteAccountDeletesTheUser() {
        UserResponse deleted = accountService.deleteAccount(1L, new DeleteAccountRequest(PASSWORD));

        assertThat(deleted).isEqualTo(new UserResponse(1L, "bob@example.com", "Bob", false));
        verify(userRepository).delete(bob);
        verify(userRepository).flush();
    }

    @Test
    void wrongPasswordDeletesNothing() {
        assertThatThrownBy(() -> accountService.deleteAccount(1L, new DeleteAccountRequest("wrong-horse")))
                .isInstanceOfSatisfying(WrongPasswordException.class, e -> {
                    assertThat(e.action()).isEqualTo(WrongPasswordException.Action.DELETE_ACCOUNT);
                    assertThat(e.action().field()).isEqualTo("password");
                });
        verify(userRepository, never()).delete(any());
    }

    // --- shared per-user password-check limit (AC15) ---

    @Test
    void fiveWrongPasswordsAcrossBothEndpointsBlockBothEvenWithTheRightPassword() {
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> accountService.changePassword(1L, new ChangePasswordRequest("wrong", "new-password"), RENEWAL))
                    .isInstanceOf(WrongPasswordException.class);
        }
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> accountService.deleteAccount(1L, new DeleteAccountRequest("wrong")))
                    .isInstanceOf(WrongPasswordException.class);
        }

        assertThatThrownBy(() -> accountService.changePassword(1L, new ChangePasswordRequest(PASSWORD, "new-password"), RENEWAL))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.limit()).isEqualTo(RateLimitedException.Limit.PASSWORD_CHECK_USER);
                    assertThat(e.email()).isEqualTo("bob@example.com");
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 900L);
                });
        assertThatThrownBy(() -> accountService.deleteAccount(1L, new DeleteAccountRequest(PASSWORD)))
                .isInstanceOf(RateLimitedException.class);
        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).saveAndFlush(any());

        // Other users are not affected.
        accountService.changePassword(2L, new ChangePasswordRequest(PASSWORD, "new-password"), RENEWAL);
    }

    @Test
    void rightPasswordResetsTheCounter() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> accountService.deleteAccount(1L, new DeleteAccountRequest("wrong")))
                    .isInstanceOf(WrongPasswordException.class);
        }
        accountService.changePassword(1L, new ChangePasswordRequest(PASSWORD, PASSWORD), RENEWAL);
        // This session's validity time is now the cutoff it set.
        SessionRenewal renewed = new SessionRenewal(NOW, NOW, null);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> accountService.changePassword(1L, new ChangePasswordRequest("wrong", PASSWORD), renewed))
                    .isInstanceOf(WrongPasswordException.class);
        }

        accountService.deleteAccount(1L, new DeleteAccountRequest(PASSWORD));
        verify(userRepository).delete(bob);
    }
}
