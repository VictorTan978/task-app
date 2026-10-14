package com.taskapp.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.auth.dto.ChangeDisplayNameRequest;
import com.taskapp.auth.dto.ChangePasswordRequest;
import com.taskapp.auth.dto.DeleteAccountRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.HashedRememberMeServices;
import com.taskapp.security.SessionRenewal;
import com.taskapp.security.ratelimit.AuthRateLimiter;

/**
 * The signed-in user's own account. Request fields are already validated (and the display name trimmed).
 * A user id that no longer exists throws {@link UnauthenticatedException} (401).
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final RememberMeTokenRepository rememberMeTokens;

    public AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter, RememberMeTokenRepository rememberMeTokens) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.rememberMeTokens = rememberMeTokens;
    }

    /**
     * Uniqueness is checked against OTHER users only, so changing only the letter case or accents of my own name
     * works. The exact current name is a no-op ({@code changed=false}). The unique key
     * {@code uk_users_display_name} still catches a concurrent change or registration to the same name.
     */
    @Transactional
    public DisplayNameChange changeDisplayName(long userId, ChangeDisplayNameRequest request) {
        User user = load(userId);
        String displayName = request.displayName();
        if (displayName.equals(user.getDisplayName())) {
            return new DisplayNameChange(UserResponse.from(user), false);
        }
        if (userRepository.existsByDisplayNameAndIdNot(displayName, userId)) {
            throw new DisplayNameTakenException();
        }
        user.changeDisplayName(displayName);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (AuthService.UK_DISPLAY_NAME.equals(AuthService.violatedConstraint(e))) {
                throw new DisplayNameTakenException();
            }
            throw e;
        }
        return new DisplayNameChange(UserResponse.from(user), true);
    }

    /**
     * Returns the user (for the security log). The new password may equal the current one. On success all OTHER
     * sessions and remember-me logins of the user end, as with {@link #logoutOtherSessions}.
     */
    @Transactional
    public UserResponse changePassword(long userId, ChangePasswordRequest request, SessionRenewal renewal) {
        User user = loadForRenewal(userId, renewal);
        verifyPassword(user, request.currentPassword(), WrongPasswordException.Action.CHANGE_PASSWORD);
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        endOtherSessions(user, renewal);
        return UserResponse.from(user);
    }

    /**
     * Ends every session and remember-me login of the user except the caller's ({@code renewal}): sets
     * {@code sessions_valid_after} to {@code renewal.now()} and deletes the other devices' remember-me logins.
     * Returns the user (for the security log). Idempotent.
     */
    @Transactional
    public UserResponse logoutOtherSessions(long userId, SessionRenewal renewal) {
        User user = loadForRenewal(userId, renewal);
        endOtherSessions(user, renewal);
        return UserResponse.from(user);
    }

    private void endOtherSessions(User user, SessionRenewal renewal) {
        user.endSessionsBefore(renewal.now());
        userRepository.saveAndFlush(user);
        rememberMeTokens.deleteAllForUserExcept(user.getId(),
                HashedRememberMeServices.decodeSeriesId(renewal.keepSeries()));
    }

    /**
     * Like {@link #load}, and also 401 if the caller's session was itself ended by a cutoff set after its last
     * check (e.g. "log out other sessions" on another device a moment ago): it must not outlive that by renewing.
     */
    private User loadForRenewal(long userId, SessionRenewal renewal) {
        User user = load(userId);
        if (user.getSessionsValidAfter() != null && renewal.previousValidFrom().isBefore(user.getSessionsValidAfter())) {
            throw new UnauthenticatedException();
        }
        return user;
    }

    /**
     * Hard-deletes the user; the database deletes their tasks ({@code fk_tasks_owner ON DELETE CASCADE}) and
     * remember-me logins ({@code fk_remember_me_tokens_user ON DELETE CASCADE}).
     * Returns the deleted user (for the security log).
     */
    @Transactional
    public UserResponse deleteAccount(long userId, DeleteAccountRequest request) {
        User user = load(userId);
        verifyPassword(user, request.password(), WrongPasswordException.Action.DELETE_ACCOUNT);
        UserResponse deleted = UserResponse.from(user);
        userRepository.delete(user);
        userRepository.flush();
        return deleted;
    }

    /**
     * Per-user limit first (429 even for the right password), then the BCrypt check. A wrong password counts one
     * failure (shared by both endpoints); a right one resets the count.
     */
    private void verifyPassword(User user, String rawPassword, WrongPasswordException.Action action) {
        rateLimiter.checkPasswordCheckAllowed(user.getId(), user.getEmail());
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            rateLimiter.recordPasswordCheckFailure(user.getId());
            throw new WrongPasswordException(action, user.getEmail());
        }
        rateLimiter.recordPasswordCheckSuccess(user.getId());
    }

    private User load(long userId) {
        return userRepository.findById(userId).orElseThrow(UnauthenticatedException::new);
    }
}
