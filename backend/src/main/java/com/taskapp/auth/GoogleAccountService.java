package com.taskapp.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.auth.dto.UserResponse;

/**
 * Account matching for "Sign in with Google" (specs/google-sign-in.md, contract 0.8.0 "Account matching"). Works
 * only with the identity from Google's verified ID token.
 * <p>
 * Concurrency (bug B1 rules): each attempt is one READ COMMITTED transaction that locks the matched user's
 * {@code users} row before writing that user's connections; rows are only inserted or changed by primary key. The
 * unique keys decide races (two callbacks for the same new Google account, a Google sign-up racing a register, two
 * new accounts wanting the same display name): the loser gets a unique-key violation, and the whole attempt is
 * retried from the start, at most {@value #MAX_ATTEMPTS} attempts, so it then sees the winner's committed rows.
 */
@Service
public class GoogleAccountService {

    static final int MAX_ATTEMPTS = 3;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_SUBJECT_LENGTH = 255;
    /** {@code sub} is stored as ascii: printable ASCII only. */
    private static final Pattern SUBJECT = Pattern.compile("[\\x21-\\x7E]+");
    private static final Set<String> RACE_CONSTRAINTS = Set.of(AuthService.UK_EMAIL, AuthService.UK_DISPLAY_NAME,
            "uk_user_connections_provider_subject", "uk_user_connections_user_provider");

    private static final Logger log = LoggerFactory.getLogger(GoogleAccountService.class);

    private final UserRepository users;
    private final UserConnectionRepository connections;
    private final Clock clock;
    private final TransactionTemplate readCommitted;

    public GoogleAccountService(UserRepository users, UserConnectionRepository connections, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.connections = connections;
        this.clock = clock;
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Login intent, in this order:
     * <ol>
     * <li>a connection with this {@code sub}: its user (the stored Google email is updated if it changed), or
     * {@code DISABLED} if that account is disabled;</li>
     * <li>an account with this email (ignoring case and accents): linked only if Google says the email is verified,
     * the account's own email is confirmed and it has no Google connection yet; otherwise {@code UNVERIFIED}; a
     * disabled account that passes those checks gives {@code DISABLED} (nothing linked);</li>
     * <li>no account: created (no password, email confirmed) and connected if Google says the email is verified;
     * otherwise {@code UNVERIFIED}, nothing created.</li>
     * </ol>
     */
    public GoogleLogin signIn(GoogleIdentity identity) {
        String subject = subject(identity);
        String email = email(identity);
        if (subject == null || email == null) {
            return GoogleLogin.refused(GoogleLogin.Outcome.INVALID, "invalid_identity");
        }
        return withRetry("signIn", () -> signInOnce(subject, email, identity.emailVerified(), identity.name()));
    }

    private GoogleLogin signInOnce(String subject, String email, boolean googleVerified, String googleName) {
        Optional<UserConnection> bySubject = connections.findByProviderAndProviderSubject(UserConnection.GOOGLE,
                subject);
        if (bySubject.isPresent()) {
            UserConnection connection = bySubject.get();
            User user = lockOrRetry(connection.getUser().getId());
            if (user.isDisabled()) {
                return GoogleLogin.refused(GoogleLogin.Outcome.DISABLED, "account_disabled");
            }
            updateEmail(connection, email);
            return GoogleLogin.signedIn(GoogleLogin.Outcome.EXISTING, response(user));
        }

        // Only the id, so the user is first loaded by the locking read (fresh state, READ COMMITTED).
        Optional<Long> byEmail = users.findIdByEmail(email);
        if (byEmail.isPresent()) {
            User user = lockOrRetry(byEmail.get());
            if (!googleVerified) {
                return GoogleLogin.refused(GoogleLogin.Outcome.UNVERIFIED, "google_email_unverified");
            }
            if (!user.isEmailVerified()) {
                return GoogleLogin.refused(GoogleLogin.Outcome.UNVERIFIED, "account_email_unconfirmed");
            }
            if (connections.findByUserIdAndProvider(user.getId(), UserConnection.GOOGLE).isPresent()) {
                return GoogleLogin.refused(GoogleLogin.Outcome.UNVERIFIED, "other_google_connected");
            }
            if (user.isDisabled()) {
                // Only once it would otherwise be linked: an unlinkable match says nothing about the account.
                return GoogleLogin.refused(GoogleLogin.Outcome.DISABLED, "account_disabled");
            }
            link(user, subject, email);
            return GoogleLogin.signedIn(GoogleLogin.Outcome.LINKED, response(user));
        }

        if (!googleVerified) {
            return GoogleLogin.refused(GoogleLogin.Outcome.UNVERIFIED, "google_email_unverified");
        }
        String displayName = GoogleDisplayNames.generate(googleName, email, users::existsByDisplayName);
        User user = users.saveAndFlush(User.withoutPassword(email, displayName, now()));
        link(user, subject, email);
        return GoogleLogin.signedIn(GoogleLogin.Outcome.CREATED, response(user));
    }

    /**
     * Connect intent: connects this Google account to user {@code userId}, whatever its email says. If Google says
     * the email is verified and it equals the account's email (ignoring case and accents), the account's email is
     * confirmed too.
     */
    public GoogleConnect connect(long userId, GoogleIdentity identity) {
        String subject = subject(identity);
        String email = email(identity);
        if (subject == null || email == null) {
            return new GoogleConnect(GoogleConnect.Outcome.INVALID, null);
        }
        return withRetry("connect", () -> connectOnce(userId, subject, email, identity.emailVerified()));
    }

    private GoogleConnect connectOnce(long userId, String subject, String email, boolean googleVerified) {
        Optional<User> locked = users.findByIdForUpdate(userId);
        if (locked.isEmpty()) {
            return new GoogleConnect(GoogleConnect.Outcome.USER_GONE, null);
        }
        User user = locked.get();
        if (user.isDisabled()) {
            return new GoogleConnect(GoogleConnect.Outcome.DISABLED, null);
        }
        Optional<UserConnection> bySubject = connections.findByProviderAndProviderSubject(UserConnection.GOOGLE,
                subject);
        GoogleConnect.Outcome outcome;
        if (bySubject.isPresent()) {
            if (bySubject.get().getUser().getId() != userId) {
                return new GoogleConnect(GoogleConnect.Outcome.IN_USE, null);
            }
            updateEmail(bySubject.get(), email);
            outcome = GoogleConnect.Outcome.ALREADY_CONNECTED;
        } else if (connections.findByUserIdAndProvider(userId, UserConnection.GOOGLE).isPresent()) {
            return new GoogleConnect(GoogleConnect.Outcome.OTHER_GOOGLE_CONNECTED, null);
        } else {
            link(user, subject, email);
            outcome = GoogleConnect.Outcome.CONNECTED;
        }
        if (googleVerified && !user.isEmailVerified() && users.existsByIdAndEmail(userId, email)) {
            // Flushes pending changes and clears the persistence context: read the user again below.
            users.markEmailVerified(userId, now());
            user = users.findById(userId).orElseThrow();
        }
        return new GoogleConnect(outcome, response(user));
    }

    private void link(User user, String subject, String email) {
        UserConnection connection = connections.saveAndFlush(
                new UserConnection(user, UserConnection.GOOGLE, subject, email, now()));
        // A collection not loaded yet will be read from the database, which already has the new row.
        if (Hibernate.isInitialized(user.getConnections())) {
            user.getConnections().add(connection);
        }
    }

    private static void updateEmail(UserConnection connection, String email) {
        if (!email.equals(connection.getProviderEmail())) {
            connection.changeProviderEmail(email);
        }
    }

    /** Locks the user's row; a user deleted since it was read is a race: the attempt is retried. */
    private User lockOrRetry(long userId) {
        return users.findByIdForUpdate(userId).orElseThrow(RaceLost::new);
    }

    /** Builds the response inside the transaction (reads the connections). */
    private static UserResponse response(User user) {
        return UserResponse.from(user);
    }

    /**
     * Runs {@code work} in its own READ COMMITTED transaction, again (from the start) when it lost a race on one of
     * the unique keys, a row lock, or a deleted row, at most {@value #MAX_ATTEMPTS} attempts in all.
     */
    private <T> T withRetry(String operation, Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return readCommitted.execute(status -> work.get());
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException | RaceLost e) {
                String what = e instanceof DataIntegrityViolationException d ? AuthService.violatedConstraint(d)
                        : e.getClass().getSimpleName();
                boolean retryable = !(e instanceof DataIntegrityViolationException) || RACE_CONSTRAINTS.contains(what);
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Google {} lost a race ({}), retrying ({}/{})", operation, what, attempt + 1, MAX_ATTEMPTS);
            }
        }
    }

    private static String subject(GoogleIdentity identity) {
        String subject = identity.subject();
        return subject != null && subject.length() <= MAX_SUBJECT_LENGTH && SUBJECT.matcher(subject).matches()
                ? subject : null;
    }

    /** Trimmed and lowercased like the register form; null if missing or unusable. */
    private static String email(GoogleIdentity identity) {
        if (identity.email() == null) {
            return null;
        }
        String email = identity.email().trim().toLowerCase(Locale.ROOT);
        int at = email.indexOf('@');
        boolean usable = at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1
                && email.length() <= MAX_EMAIL_LENGTH && email.chars().noneMatch(Character::isWhitespace);
        return usable ? email : null;
    }

    private Instant now() {
        // Same precision as the DATETIME(6) columns.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** A row read in this attempt was deleted before it could be locked. */
    private static final class RaceLost extends RuntimeException {

        RaceLost() {
            super("A concurrent change won", null, false, false);
        }
    }
}
