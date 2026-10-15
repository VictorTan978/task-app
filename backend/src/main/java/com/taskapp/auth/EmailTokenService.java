package com.taskapp.auth;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.email.EmailProperties;
import com.taskapp.security.RememberMeTokenStore;

/**
 * Emailed link tokens (specs/email-auth.md AC16): 32 random bytes from {@link SecureRandom} as base64url (43
 * characters); only the SHA-256 of the raw bytes is stored. Single-use. Issuing a new token replaces the account's
 * unused tokens of the same purpose.
 * <p>
 * Concurrency (bug B1 rules): every write runs READ COMMITTED and locks the user's {@code users} row first, then
 * touches token rows by primary key only (see {@link EmailTokenRepository}). Consuming a token re-reads its row
 * after taking the user lock, so two parallel requests with one token can't both use it. A transaction that still
 * loses a lock conflict is retried once (safety net only, as in {@code DatabaseRememberMeTokenStore}).
 */
@Service
public class EmailTokenService {

    private static final Logger log = LoggerFactory.getLogger(EmailTokenService.class);

    private static final int TOKEN_BYTES = 32;
    /** base64url without padding of 32 bytes. */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final EmailTokenRepository tokens;
    private final UserRepository users;
    private final RememberMeTokenStore rememberMeTokens;
    private final EmailProperties properties;
    private final Clock clock;
    private final TransactionTemplate readCommitted;
    private final SecureRandom random = new SecureRandom();

    public EmailTokenService(EmailTokenRepository tokens, UserRepository users, RememberMeTokenStore rememberMeTokens,
            EmailProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.users = users;
        this.rememberMeTokens = rememberMeTokens;
        this.properties = properties;
        this.clock = clock;
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * A new token, ready to be emailed. {@link #toString()} is redacted: never log it.
     *
     * @param token the raw token for the link
     */
    public record IssuedToken(String token, String email, String displayName, Duration validFor) {

        @Override
        public String toString() {
            return "IssuedToken[token=****, email=" + email + "]";
        }
    }

    /**
     * Creates a token for {@code userId} and replaces their unused tokens of the same purpose (expired tokens of
     * any purpose are deleted too). Empty if the user no longer exists, for {@link EmailTokenPurpose#VERIFY_EMAIL}
     * if their email is already confirmed, or for {@link EmailTokenPurpose#RESET_PASSWORD} if the account is disabled
     * (nothing is changed then).
     */
    public Optional<IssuedToken> issue(long userId, EmailTokenPurpose purpose) {
        return inTransaction("issue", () -> {
            Instant now = now();
            EmailTokenRepository.LockedUser user = tokens.lockUser(userId).orElse(null);
            if (user == null || (purpose == EmailTokenPurpose.VERIFY_EMAIL && user.emailVerified())
                    || (purpose == EmailTokenPurpose.RESET_PASSWORD && user.disabled())) {
                return Optional.empty();
            }
            tokens.deleteReplacedAndExpired(userId, purpose, now);
            byte[] raw = new byte[TOKEN_BYTES];
            random.nextBytes(raw);
            Duration lifetime = lifetime(purpose);
            tokens.insert(sha256(raw), userId, purpose, now.plus(lifetime), now);
            return Optional.of(new IssuedToken(B64.encodeToString(raw), user.email(), user.displayName(), lifetime));
        });
    }

    /**
     * Read-only check (no lock, changes nothing): the token is a known, unused, unexpired token of {@code purpose},
     * and for {@link EmailTokenPurpose#RESET_PASSWORD} its account is not disabled.
     *
     * @throws EmailTokenInvalidException otherwise, with the reason for the security log
     */
    public EmailToken check(String token, EmailTokenPurpose purpose) {
        if (token == null || token.isBlank()) {
            throw new EmailTokenInvalidException("missing", null);
        }
        byte[] hash = hashOf(token);
        if (hash == null) {
            throw new EmailTokenInvalidException("malformed", null);
        }
        EmailToken row = tokens.find(hash)
                .orElseThrow(() -> new EmailTokenInvalidException("unknown", null));
        requireUsable(row, purpose, () -> users.findById(row.userId()).map(User::getEmail).orElse(null), now());
        if (purpose == EmailTokenPurpose.RESET_PASSWORD) {
            users.findById(row.userId()).filter(User::isDisabled).ifPresent(user -> {
                throw new EmailTokenInvalidException("account_disabled", user.getEmail());
            });
        }
        return row;
    }

    /** Uses a checked confirmation token and marks the email confirmed. Returns the account's email. */
    public String verifyEmail(EmailToken checked) {
        return inTransaction("verifyEmail", () -> {
            Instant now = now();
            EmailTokenRepository.LockedUser user = consume(checked, EmailTokenPurpose.VERIFY_EMAIL, now);
            users.markEmailVerified(user.id(), now);
            return user.email();
        });
    }

    /**
     * Uses a checked reset token and, in the same transaction: sets {@code passwordHash}, marks the email confirmed,
     * and ends every session and remember-me login of the account ({@code sessions_valid_after} = now, all
     * remember-me logins deleted). Returns the account's email.
     */
    public String resetPassword(EmailToken checked, String passwordHash) {
        return inTransaction("resetPassword", () -> {
            Instant now = now();
            EmailTokenRepository.LockedUser user = consume(checked, EmailTokenPurpose.RESET_PASSWORD, now);
            users.resetPassword(user.id(), passwordHash, now);
            // Joins this transaction; the users row is already locked by consume().
            rememberMeTokens.endAllForUser(user.id(), now);
            return user.email();
        });
    }

    /** Locks the owner, re-reads the token (a parallel request may have used it meanwhile) and marks it used. */
    private EmailTokenRepository.LockedUser consume(EmailToken checked, EmailTokenPurpose purpose, Instant now) {
        EmailTokenRepository.LockedUser user = tokens.lockUser(checked.userId())
                .orElseThrow(() -> new EmailTokenInvalidException("unknown", null));
        EmailToken current = tokens.find(checked.tokenHash())
                .orElseThrow(() -> new EmailTokenInvalidException("unknown", user.email()));
        requireUsable(current, purpose, user::email, now);
        if (purpose == EmailTokenPurpose.RESET_PASSWORD && user.disabled()) {
            throw new EmailTokenInvalidException("account_disabled", user.email());
        }
        if (!tokens.markUsed(current.tokenHash(), now)) {
            throw new EmailTokenInvalidException("used", user.email());
        }
        return user;
    }

    private static void requireUsable(EmailToken row, EmailTokenPurpose purpose, Supplier<String> email,
            Instant now) {
        if (row.purpose() != purpose) {
            throw new EmailTokenInvalidException("wrong_purpose", email.get());
        }
        if (row.usedAt() != null) {
            throw new EmailTokenInvalidException("used", email.get());
        }
        if (!now.isBefore(row.expiresAt())) {
            throw new EmailTokenInvalidException("expired", email.get());
        }
    }

    private Duration lifetime(EmailTokenPurpose purpose) {
        return purpose == EmailTokenPurpose.VERIFY_EMAIL ? properties.verifyEmailLinkLifetime()
                : properties.resetPasswordLinkLifetime();
    }

    private Instant now() {
        // Same precision as the DATETIME(6) columns.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Runs {@code work} in its own READ COMMITTED transaction. A lock conflict (deadlock victim, lock wait timeout)
     * is retried once, only when this method owns the transaction.
     */
    private <T> T inTransaction(String operation, Supplier<T> work) {
        boolean ownsTransaction = !TransactionSynchronizationManager.isActualTransactionActive();
        try {
            return readCommitted.execute(status -> work.get());
        } catch (PessimisticLockingFailureException e) {
            if (!ownsTransaction) {
                throw e;
            }
            log.warn("Email token {} lost a lock conflict, retrying once: {}", operation, e.getClass().getSimpleName());
            return readCommitted.execute(status -> work.get());
        }
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /** The stored hash of a raw token as sent in a link (for tests and tooling); null if malformed. */
    public static byte[] hashOf(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return null;
        }
        try {
            return sha256(B64_DECODER.decode(token));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
