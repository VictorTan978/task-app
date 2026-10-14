package com.taskapp.auth;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.security.RememberMeToken;
import com.taskapp.security.RememberMeTokenStore;

/**
 * {@link RememberMeTokenStore} on {@code remember_me_tokens} and {@code users.sessions_valid_after}.
 * <p>
 * Concurrency (bug B1): the multi-statement writes run READ COMMITTED (no gap locks), lock the user's
 * {@code users} row first and then delete by primary key only (see {@link RememberMeTokenRepository}). So writes
 * for different users do not block each other and writes for one user run one after another, without deadlocks.
 * As a safety net only, a transaction that still loses a lock conflict is retried once.
 */
@Component
class DatabaseRememberMeTokenStore implements RememberMeTokenStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRememberMeTokenStore.class);

    private final RememberMeTokenRepository tokens;
    private final UserRepository users;
    private final TransactionTemplate readCommitted;

    DatabaseRememberMeTokenStore(RememberMeTokenRepository tokens, UserRepository users,
            PlatformTransactionManager transactionManager) {
        this.tokens = tokens;
        this.users = users;
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Optional<RememberMeToken> find(byte[] seriesHash) {
        return tokens.findBySeriesHash(seriesHash);
    }

    @Override
    public void create(RememberMeToken token, Instant now) {
        inTransaction("create", () -> {
            tokens.lockOwner(token.userId());
            tokens.deleteExpiredForUser(token.userId(), now);
            tokens.insert(token);
        });
    }

    @Override
    public boolean rotate(byte[] seriesHash, byte[] currentTokenHash, byte[] newTokenHash, Instant now) {
        return tokens.rotate(seriesHash, currentTokenHash, newTokenHash, now);
    }

    @Override
    public void delete(byte[] seriesHash) {
        tokens.deleteBySeriesHash(seriesHash);
    }

    @Override
    public void endAllForUser(long userId, Instant now) {
        inTransaction("endAllForUser", () -> {
            // The UPDATE locks the users row first, like every other multi-row token write.
            users.endSessionsBefore(userId, now);
            tokens.deleteAllForUser(userId);
        });
    }

    /**
     * Runs {@code work} in its own READ COMMITTED transaction, or joins the caller's. A lock conflict (deadlock
     * victim, lock wait timeout) is retried once, only when this method owns the transaction: a joined one is
     * already rolled back for the caller.
     */
    private void inTransaction(String operation, Runnable work) {
        boolean ownsTransaction = !TransactionSynchronizationManager.isActualTransactionActive();
        try {
            readCommitted.executeWithoutResult(status -> work.run());
        } catch (PessimisticLockingFailureException e) {
            if (!ownsTransaction) {
                throw e;
            }
            log.warn("Remember-me {} lost a lock conflict, retrying once: {}", operation, e.getMessage());
            readCommitted.executeWithoutResult(status -> work.run());
        }
    }
}
