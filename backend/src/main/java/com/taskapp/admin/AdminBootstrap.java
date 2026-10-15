package com.taskapp.admin;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.taskapp.auth.User;
import com.taskapp.auth.UserRepository;
import com.taskapp.security.Role;
import com.taskapp.security.audit.AuditOrigin;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.audit.SecurityEventLogger;

/**
 * The first super admin (specs/roles-admin.md AC4): at startup, when {@code app.admin.bootstrap-email}
 * ({@code ADMIN_BOOTSTRAP_EMAIL}) is set and NO super admin exists yet, the account with that email (matched like
 * login: trimmed, lowercased, then ignoring letter case and accents) becomes {@code SUPER_ADMIN} and
 * {@code ADMIN_BOOTSTRAPPED} is logged. Once a super admin exists the setting does nothing, so it can't be used to take
 * over later.
 * <ul>
 * <li>Empty setting: nothing happens.</li>
 * <li>No such account: a warning; checked again on the next start.</li>
 * <li>The account is disabled: skipped with a warning (a super admin nobody could enable; fix it in the database).</li>
 * </ul>
 * Never fails startup. Runs in one READ COMMITTED transaction and locks the account's row before changing it.
 */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final TransactionTemplate readCommitted;
    private final String bootstrapEmail;

    AdminBootstrap(UserRepository users, PlatformTransactionManager transactionManager,
            @Value("${app.admin.bootstrap-email:}") String bootstrapEmail) {
        this.users = users;
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.bootstrapEmail = bootstrapEmail;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = bootstrapEmail == null ? "" : bootstrapEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            return;
        }
        try {
            User made = readCommitted.execute(status -> bootstrap(email));
            if (made != null) {
                // After the commit.
                SecurityEventLogger.log(SecurityEvent.ADMIN_BOOTSTRAPPED, AuditOrigin.NONE, made.getEmail(),
                        "target_id", Long.toString(made.getId()));
            }
        } catch (RuntimeException e) {
            // Never block startup: the setting is checked again on the next start.
            log.error("ADMIN_BOOTSTRAP_EMAIL could not be applied: {}", e.getClass().getName());
        }
    }

    /** The account made super admin, or null. */
    private User bootstrap(String email) {
        if (users.existsByRole(Role.SUPER_ADMIN)) {
            log.info("ADMIN_BOOTSTRAP_EMAIL ignored: a super admin already exists");
            return null;
        }
        Optional<Long> id = users.findIdByEmail(email);
        if (id.isEmpty()) {
            log.warn("ADMIN_BOOTSTRAP_EMAIL: no account with this email yet; checked again on the next start");
            return null;
        }
        User user = users.findByIdForUpdate(id.get()).orElse(null);
        if (user == null) {
            log.warn("ADMIN_BOOTSTRAP_EMAIL: the account was deleted meanwhile; checked again on the next start");
            return null;
        }
        if (user.isDisabled()) {
            log.warn("ADMIN_BOOTSTRAP_EMAIL: the account is disabled, skipped (enable it in the database first)");
            return null;
        }
        user.changeRole(Role.SUPER_ADMIN);
        return users.saveAndFlush(user);
    }
}
