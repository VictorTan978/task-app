package com.taskapp.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import com.taskapp.admin.dto.AdminUserPage;
import com.taskapp.admin.dto.AdminUserQuery;
import com.taskapp.admin.dto.AdminUserResponse;
import com.taskapp.admin.dto.ChangeRoleRequest;
import com.taskapp.auth.AccountService;
import com.taskapp.auth.EmailTokenPurpose;
import com.taskapp.auth.EmailTokenRepository;
import com.taskapp.auth.InvalidFieldsException;
import com.taskapp.auth.RememberMeTokenRepository;
import com.taskapp.auth.UnauthenticatedException;
import com.taskapp.auth.User;
import com.taskapp.auth.UserConnection;
import com.taskapp.auth.UserConnectionRepository;
import com.taskapp.auth.UserRepository;
import com.taskapp.security.AuthenticatedUser;
import com.taskapp.security.Role;
import com.taskapp.security.audit.AdminAction;
import com.taskapp.security.audit.AdminAudit;
import com.taskapp.security.audit.AuditOrigin;
import com.taskapp.security.audit.SecurityEvent;
import com.taskapp.security.ratelimit.AdminRateLimiter;
import com.taskapp.task.DeadlockRetry;

import jakarta.validation.Validator;

/**
 * The admin users page (specs/roles-admin.md). The security chain already let only ADMIN / SUPER_ADMIN sessions in;
 * every change is checked again here against the database.
 * <p>
 * Each change, in this order: request validation (400, not counted) > per-admin rate limit (429, every change request
 * counts, also the refused ones) > acting on yourself (409, no database work) > one READ COMMITTED transaction that
 * locks the admin's and the target's {@code users} rows together in id order ({@code SELECT ... ORDER BY id FOR
 * UPDATE}) and then checks, against the locked rows: admin gone (401) or disabled (401 ACCOUNT_DISABLED) > admin's role
 * is USER now (403 FORBIDDEN) > target gone (404) > target SUPER_ADMIN, target ADMIN for an ADMIN, or a role change by
 * an ADMIN (403 INSUFFICIENT_ROLE). So a role changed a moment ago, on either side, is respected. Token rows are then
 * deleted by primary key only (bug B1 lock rules); a transaction that loses a lock conflict is retried once.
 * <p>
 * Every refusal is logged as {@code ADMIN_ACCESS_DENIED}; every change that happened is logged after it committed. A
 * change already in effect (disable a disabled account, enable an active one, the same role) is a 200 that changes and
 * logs nothing.
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final String INVALID_ROLE_MESSAGE = "Role must be USER or ADMIN";

    private final UserRepository users;
    private final UserConnectionRepository connections;
    private final RememberMeTokenRepository rememberMeTokens;
    private final EmailTokenRepository emailTokens;
    private final AccountService accounts;
    private final AdminRateLimiter rateLimiter;
    private final Clock clock;
    private final SpringValidatorAdapter validator;
    private final TransactionTemplate readCommitted;

    public AdminUserService(UserRepository users, UserConnectionRepository connections,
            RememberMeTokenRepository rememberMeTokens, EmailTokenRepository emailTokens, AccountService accounts,
            AdminRateLimiter rateLimiter, Clock clock, Validator validator,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.connections = connections;
        this.rememberMeTokens = rememberMeTokens;
        this.emailTokens = emailTokens;
        this.accounts = accounts;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.validator = new SpringValidatorAdapter(validator);
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ---- list ----

    /**
     * One page of all accounts, newest first ({@code created_at DESC, id DESC}), optionally only those whose email or
     * display name contains {@code q} (ignoring letter case and accents, {@code %} and {@code _} literal). Not limited.
     */
    @Transactional(readOnly = true)
    public AdminUserPage list(AdminUserQuery query) {
        DirectFieldBindingResult errors = new DirectFieldBindingResult(query, "adminUserQuery");
        validator.validate(query, errors);
        if (errors.hasErrors()) {
            throw new InvalidFieldsException(errors);
        }
        String search = query.search();
        Page<User> page = users.search(search == null ? null : UserRepository.containsPattern(search),
                PageRequest.of(query.page(), query.size(), NEWEST_FIRST));
        List<Long> ids = page.getContent().stream().map(User::getId).toList();
        Set<Long> withGoogle = ids.isEmpty() ? Set.of()
                : new HashSet<>(connections.findUserIdsWithProvider(UserConnection.GOOGLE, ids));
        List<AdminUserResponse> content = page.getContent().stream()
                .map(user -> AdminUserResponse.from(user, withGoogle.contains(user.getId())))
                .toList();
        return new AdminUserPage(content, query.page(), query.size(), page.getTotalElements(), page.getTotalPages());
    }

    // ---- changes ----

    /** Disables the account and ends its sessions, remember-me logins and unused reset links. */
    public AdminUserResponse disable(AuthenticatedUser actor, long targetId) {
        return change(actor, targetId, AdminAction.DISABLE, (target, now) -> {
            if (target.isDisabled()) {
                return Applied.unchanged();
            }
            target.disable(now);
            target.endSessionsBefore(now);
            // Users row first (already locked), then token rows by primary key.
            users.saveAndFlush(target);
            rememberMeTokens.deleteAllForUser(target.getId());
            emailTokens.deleteReplacedAndExpired(target.getId(), EmailTokenPurpose.RESET_PASSWORD, now);
            return Applied.changed(SecurityEvent.ADMIN_USER_DISABLED);
        });
    }

    /** Clears the disabled state only; ended sessions stay ended. */
    public AdminUserResponse enable(AuthenticatedUser actor, long targetId) {
        return change(actor, targetId, AdminAction.ENABLE, (target, now) -> {
            if (!target.isDisabled()) {
                return Applied.unchanged();
            }
            target.enable();
            users.saveAndFlush(target);
            return Applied.changed(SecurityEvent.ADMIN_USER_ENABLED);
        });
    }

    /** Ends every session and remember-me login of the account (like the stolen-cookie response). Always logged. */
    public AdminUserResponse revokeSessions(AuthenticatedUser actor, long targetId) {
        return change(actor, targetId, AdminAction.REVOKE_SESSIONS, (target, now) -> {
            target.endSessionsBefore(now);
            users.saveAndFlush(target);
            rememberMeTokens.deleteAllForUser(target.getId());
            return Applied.changed(SecurityEvent.ADMIN_USER_SESSIONS_REVOKED);
        });
    }

    /** USER ↔ ADMIN, super admins only. Takes effect on the user's next request. */
    public AdminUserResponse changeRole(AuthenticatedUser actor, long targetId, ChangeRoleRequest request) {
        Role role = request.assignableRole().orElseThrow(() -> {
            DirectFieldBindingResult errors = new DirectFieldBindingResult(request, "changeRoleRequest");
            errors.rejectValue("role", "InvalidRole", INVALID_ROLE_MESSAGE);
            return new InvalidFieldsException(errors);
        });
        return change(actor, targetId, AdminAction.CHANGE_ROLE, (target, now) -> {
            Role from = target.getRole();
            if (from == role) {
                return Applied.unchanged();
            }
            target.changeRole(role);
            users.saveAndFlush(target);
            return Applied.changed(SecurityEvent.ADMIN_ROLE_CHANGED, "from", from.name(), "to", role.name());
        });
    }

    /**
     * Deletes the account like a self-service delete ({@link AccountService#deleteNow}: owned lists, memberships,
     * connections and email links go; tasks it added to other people's lists stay). Its remember-me logins are deleted
     * first, by primary key. The transaction is run again if it lost a deadlock ({@link DeadlockRetry}).
     */
    public void delete(AuthenticatedUser actor, long targetId) {
        change(actor, targetId, AdminAction.DELETE, (target, now) -> {
            rememberMeTokens.deleteAllForUser(target.getId());
            accounts.deleteNow(target);
            return Applied.deletedNow();
        });
    }

    // ---- shared ----

    /** What a change did to the locked target. */
    @FunctionalInterface
    private interface Change {
        Applied apply(User target, Instant now);
    }

    /**
     * @param deleted the target no longer exists (no response body)
     * @param event   the audit event to log after commit; null = nothing changed
     */
    private record Applied(boolean deleted, SecurityEvent event, String[] extraFields) {

        static Applied unchanged() {
            return new Applied(false, null, new String[0]);
        }

        static Applied changed(SecurityEvent event, String... extraFields) {
            return new Applied(false, event, extraFields);
        }

        static Applied deletedNow() {
            return new Applied(true, SecurityEvent.ADMIN_USER_DELETED, new String[0]);
        }
    }

    private AdminUserResponse change(AuthenticatedUser actor, long targetId, AdminAction action, Change change) {
        AuditOrigin origin = AuditOrigin.current();
        rateLimiter.acquireAction(actor.id(), actor.email());
        if (actor.id() == targetId) {
            throw refused(origin, actor, targetId, action, "self", new CannotModifySelfException());
        }
        Result result = inTransaction(action, () -> {
            Instant now = now();
            List<User> locked = users.findAllByIdForUpdate(List.of(Math.min(actor.id(), targetId),
                    Math.max(actor.id(), targetId)));
            User admin = find(locked, actor.id());
            User target = find(locked, targetId);
            check(origin, actor, admin, target, targetId, action);
            Applied applied = change.apply(target, now);
            AdminUserResponse response = applied.deleted() ? null : respond(target);
            return new Result(response, applied.event(), applied.extraFields());
        });
        if (result.event() != null) {
            AdminAudit.done(result.event(), origin, actor.email(), actor.id(), targetId, action, result.extraFields());
        }
        return result.response();
    }

    private record Result(AdminUserResponse response, SecurityEvent event, String[] extraFields) {
    }

    /** The permission rules of specs/roles-admin.md, against the locked rows. */
    private static void check(AuditOrigin origin, AuthenticatedUser actor, User admin, User target, long targetId,
            AdminAction action) {
        if (admin == null) {
            throw new UnauthenticatedException();
        }
        if (admin.isDisabled()) {
            throw new ActorDisabledException();
        }
        Role actorRole = admin.getRole();
        if (!actorRole.isAdmin()) {
            throw refused(origin, actor, targetId, action, "not_admin", new NotAdminException());
        }
        if (target == null) {
            throw refused(origin, actor, targetId, action, "target_not_found", new UserNotFoundException());
        }
        if (target.getRole() == Role.SUPER_ADMIN) {
            throw refused(origin, actor, targetId, action, "target_super_admin", new InsufficientRoleException());
        }
        boolean superAdmin = actorRole == Role.SUPER_ADMIN;
        if (!superAdmin && (target.getRole() == Role.ADMIN || action == AdminAction.CHANGE_ROLE)) {
            throw refused(origin, actor, targetId, action, "insufficient_role", new InsufficientRoleException());
        }
    }

    private static RuntimeException refused(AuditOrigin origin, AuthenticatedUser actor, long targetId,
            AdminAction action, String reason, RuntimeException response) {
        AdminAudit.denied(origin, actor.email(), actor.id(), Long.toString(targetId), action, reason);
        return response;
    }

    private AdminUserResponse respond(User user) {
        boolean google = connections.findByUserIdAndProvider(user.getId(), UserConnection.GOOGLE).isPresent();
        return AdminUserResponse.from(user, google);
    }

    private static User find(List<User> users, long id) {
        return users.stream().filter(u -> u.getId() == id).findFirst().orElse(null);
    }

    /**
     * Own READ COMMITTED transaction; a lost lock conflict (deadlock victim, lock wait timeout) is retried once. A
     * delete (0.13.0, which locks every list the account touches) is first run under {@link DeadlockRetry}.
     */
    private <T> T inTransaction(AdminAction action, Supplier<T> work) {
        try {
            if (action == AdminAction.DELETE) {
                return DeadlockRetry.run(() -> readCommitted.execute(status -> work.get()));
            }
            return readCommitted.execute(status -> work.get());
        } catch (PessimisticLockingFailureException e) {
            log.warn("Admin {} lost a lock conflict, retrying once: {}", action.logValue(),
                    e.getClass().getSimpleName());
            return readCommitted.execute(status -> work.get());
        }
    }

    private Instant now() {
        // Same precision as the DATETIME(6) columns.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
