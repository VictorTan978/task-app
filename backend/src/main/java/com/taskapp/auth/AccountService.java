package com.taskapp.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskapp.auth.dto.ChangeDisplayNameRequest;
import com.taskapp.auth.dto.ChangePasswordRequest;
import com.taskapp.auth.dto.DeleteAccountRequest;
import com.taskapp.auth.dto.UserResponse;
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

    public AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
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

    /** Returns the user (for the security log). The new password may equal the current one. */
    @Transactional
    public UserResponse changePassword(long userId, ChangePasswordRequest request) {
        User user = load(userId);
        verifyPassword(user, request.currentPassword(), WrongPasswordException.Action.CHANGE_PASSWORD);
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);
        return UserResponse.from(user);
    }

    /**
     * Hard-deletes the user; the database deletes their tasks ({@code fk_tasks_owner ON DELETE CASCADE}).
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
