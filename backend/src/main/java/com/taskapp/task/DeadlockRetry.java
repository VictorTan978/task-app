package com.taskapp.task;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * Safety net, not the concurrency design (see {@link TagRepository}): runs a whole transaction again when MySQL
 * chose it as a deadlock victim (SQLState 40001 / error 1213; InnoDB has already rolled it back). One case cannot be
 * designed away: re-creating a tag name whose deleted index entry is not purged yet makes InnoDB's duplicate-key
 * check take a shared next-key lock, and that gap can border another user's tags. Lock wait timeouts are not
 * retried. Call it outside any transaction.
 */
final class DeadlockRetry {

    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(DeadlockRetry.class);

    private DeadlockRetry() {
    }

    static <T> T run(Supplier<T> transaction) {
        for (int attempt = 1;; attempt++) {
            try {
                return transaction.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS || !isDeadlock(e)) {
                    throw e;
                }
                log.warn("Deadlock (attempt {} of {}), retrying the transaction", attempt, MAX_ATTEMPTS);
            }
        }
    }

    static boolean isDeadlock(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || sql.getErrorCode() == 1213)) {
                return true;
            }
        }
        return false;
    }
}
