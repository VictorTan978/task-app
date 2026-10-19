package com.taskapp.auth;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.DirectFieldBindingResult;

import com.taskapp.auth.dto.ChangeDisplayNameRequest;
import com.taskapp.auth.dto.ChangePasswordRequest;
import com.taskapp.auth.dto.DeleteAccountRequest;
import com.taskapp.auth.dto.UserResponse;
import com.taskapp.security.HashedRememberMeServices;
import com.taskapp.security.SessionRenewal;
import com.taskapp.security.ratelimit.AuthRateLimiter;
import com.taskapp.task.DeadlockRetry;
import com.taskapp.task.ListService;

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
    private final UserConnectionRepository connections;
    private final ListService listService;
    private final TransactionOperations transaction;

    @Autowired
    public AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter, RememberMeTokenRepository rememberMeTokens,
            UserConnectionRepository connections, ListService listService,
            PlatformTransactionManager transactions) {
        this(userRepository, passwordEncoder, rateLimiter, rememberMeTokens, connections, listService,
                new TransactionTemplate(transactions));
    }

    AccountService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthRateLimiter rateLimiter, RememberMeTokenRepository rememberMeTokens,
            UserConnectionRepository connections, ListService listService, TransactionOperations transaction) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.rememberMeTokens = rememberMeTokens;
        this.connections = connections;
        this.listService = listService;
        this.transaction = transaction;
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserResponse changePassword(long userId, ChangePasswordRequest request, SessionRenewal renewal) {
        User user = loadForRenewal(userId, renewal);
        if (!user.hasPassword()) {
            // Before the rate limit: nothing is checked or counted.
            throw new PasswordRequiredException(PasswordRequiredException.Action.CHANGE_PASSWORD);
        }
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserResponse logoutOtherSessions(long userId, SessionRenewal renewal) {
        User user = loadForRenewal(userId, renewal);
        endOtherSessions(user, renewal);
        return UserResponse.from(user);
    }

    /**
     * READ COMMITTED (callers) and the users row UPDATE before the token delete: the lock order of every multi-row
     * remember-me write, so this cannot deadlock with parallel logins (bug B1). See RememberMeTokenRepository.
     */
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
     * Hard-deletes the user (see {@link #deleteNow} for what goes and what stays). Returns the deleted user (for the
     * security log).
     * <p>
     * An account with a password confirms with {@code password}; one without a password with {@code email}. Order:
     * the confirming field is required (400 VALIDATION_FAILED, nothing counted) > per-user limit (429) > the check
     * (400 WRONG_PASSWORD / EMAIL_MISMATCH, counted as one failure on the shared counter).
     * <p>
     * 0.13.0: one transaction whose first statement locks the user's row FOR UPDATE (so everything read after it is
     * current, see {@link #deleteNow}), run again if it lost a deadlock ({@link DeadlockRetry}).
     */
    public UserResponse deleteAccount(long userId, DeleteAccountRequest request) {
        return DeadlockRetry.run(() -> transaction.execute(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElseThrow(UnauthenticatedException::new);
            if (user.hasPassword()) {
                requireField(request, "password", request.password(), "Password is required");
                verifyPassword(user, request.password(), WrongPasswordException.Action.DELETE_ACCOUNT);
            } else {
                requireField(request, "email", request.email(), "Email is required");
                verifyEmail(user, request.email());
            }
            UserResponse deleted = UserResponse.from(user);
            deleteNow(user);
            return deleted;
        }));
    }

    /**
     * The delete itself, shared by {@link #deleteAccount} (after its checks) and the admin delete
     * ({@code com.taskapp.admin.AdminUserService}). Checks nothing. The caller's transaction holds {@code user}'s row
     * FOR UPDATE and read nothing before taking it (or runs READ COMMITTED), and is run again on a deadlock.
     * <p>
     * First every list the deletion touches is locked FOR UPDATE, ascending, and the invitations to the user's email
     * are deleted ({@link ListService#lockForAccountDeletion}), so the cascade below never meets a write in progress.
     * Then the user is deleted and the database: deletes the lists they own with all their tasks, tags, members and
     * invitations ({@code fk_lists_owner}), their memberships ({@code fk_list_members_user}), remember-me logins and
     * connections; and keeps the tasks they added to other people's lists, with {@code owner_id} NULL
     * ({@code fk_tasks_owner ON DELETE SET NULL}, V11), and those lists' tags (owned by the list owner).
     */
    public void deleteNow(User user) {
        listService.lockForAccountDeletion(user.getId(), user.getEmail());
        userRepository.delete(user);
        userRepository.flush();
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

    /**
     * Removes the user's Google connection and returns the updated user. 404 when the user has none, 409
     * {@code PASSWORD_REQUIRED} when the account has no password (it would lose its only way to sign in). Locks the
     * user's row first and deletes by primary key (see {@link UserConnectionRepository}).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserResponse disconnectGoogle(long userId) {
        User user = userRepository.findByIdForUpdate(userId).orElseThrow(UnauthenticatedException::new);
        UserConnection connection = connections.findByUserIdAndProvider(userId, UserConnection.GOOGLE)
                .orElseThrow(() -> new ConnectionNotFoundException("No Google account is connected"));
        if (!user.hasPassword()) {
            throw new PasswordRequiredException(PasswordRequiredException.Action.DISCONNECT_GOOGLE);
        }
        connections.deleteByIdNow(connection.getId());
        return UserResponse.from(userRepository.findById(userId).orElseThrow(UnauthenticatedException::new));
    }

    /** 400 VALIDATION_FAILED ({@code field} / REQUIRED) unless {@code value} is non-blank. Nothing is counted. */
    private static void requireField(DeleteAccountRequest request, String field, String value, String message) {
        if (value == null || value.isBlank()) {
            DirectFieldBindingResult errors = new DirectFieldBindingResult(request, "deleteAccountRequest");
            errors.rejectValue(field, "NotBlank", message);
            throw new InvalidFieldsException(errors);
        }
    }

    /**
     * Like {@link #verifyPassword}, for accounts without a password: the typed email must be the account's, compared
     * like login (ignoring letter case and accents, by the database collation). Same per-user counter.
     */
    private void verifyEmail(User user, String typedEmail) {
        rateLimiter.checkPasswordCheckAllowed(user.getId(), user.getEmail());
        if (!userRepository.existsByIdAndEmail(user.getId(), typedEmail.toLowerCase(Locale.ROOT))) {
            rateLimiter.recordPasswordCheckFailure(user.getId());
            throw new EmailMismatchException(user.getEmail());
        }
        rateLimiter.recordPasswordCheckSuccess(user.getId());
    }

    private User load(long userId) {
        return userRepository.findById(userId).orElseThrow(UnauthenticatedException::new);
    }
}
